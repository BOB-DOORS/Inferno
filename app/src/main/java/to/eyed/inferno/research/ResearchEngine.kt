package to.eyed.inferno.research

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException

/**
 * Native, API-key-free public-web research. Retrieval and parsing stay on-device; only focused queries and requested
 * public URLs are sent to the selected public search/page hosts. No cloud model or account is involved.
 */
internal class ResearchEngine(private val client: OkHttpClient) {
    private val pageCache = ConcurrentHashMap<String, CachedPage>()
    private val robotsCache = ConcurrentHashMap<String, RobotsRules>()
    private val hostRequests = ConcurrentHashMap<String, Long>()

    suspend fun execute(call: ResearchToolCall, workspace: ResearchWorkspace): ResearchOutput =
        withContext(Dispatchers.IO) {
            try {
                when (call.name) {
                    "search_web" -> searchWeb(call)
                    "search_news" -> searchNews(call)
                    "search_products" -> searchProducts(call)
                    "open_page" -> openPage(call.string("url"))
                    "extract_product" -> extractProduct(call.string("url"))
                    "search_images" -> searchImages(call)
                    "find_in_page" -> findInPage(call)
                    "follow_links" -> followLinks(call)
                    "compare_sources" -> ResearchOutput("Source comparison", workspace.comparisonNotes())
                    else -> ResearchOutput("Research", "Unsupported tool. Continue with existing evidence.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ResearchOutput("Research error", "Could not complete this operation: ${e.message?.take(180) ?: "network or extraction error"}. Continue using available evidence; do not invent missing facts.")
            }
        }

    private suspend fun searchWeb(call: ResearchToolCall): ResearchOutput {
        val query = prepareQuery(call) ?: return blocked()
        val domains = call.string("domains").split(',', ';', ' ').map(String::trim).filter(String::isNotBlank).take(5)
        val scoped = if (domains.isEmpty()) query else "$query " + domains.joinToString(" ") { "site:${it.removePrefix("https://").removePrefix("http://").substringBefore('/')}" }
        val queryWithDate = freshnessQuery(scoped, call.string("freshness"))
        var hits = runCatching { searchDuckDuckGo(queryWithDate) }.getOrDefault(emptyList())
        if (hits.size < 4) hits = dedupe(hits + runCatching { searchBing(queryWithDate) }.getOrDefault(emptyList()))
        val sources = hits.map { it.source() }
        val evidence = if (hits.isEmpty()) {
            "No result pages were returned by either public search provider. They may be unavailable or rate-limiting this connection. Try a more focused query or open a known public page."
        } else hits.joinToString("\n\n") { hit ->
            buildString {
                append(hit.title).append("\n").append(hit.url)
                if (hit.publishedAt != null) append("\nPublished: ").append(hit.publishedAt)
                if (hit.snippet.isNotBlank()) append("\n").append(hit.snippet.take(500))
            }
        }
        return ResearchOutput("Web search: $query", evidence.take(8_000), sources)
    }

    private suspend fun searchNews(call: ResearchToolCall): ResearchOutput {
        val query = prepareQuery(call) ?: return blocked()
        val raw = fetchText("https://www.bing.com/news/search?q=${enc(freshnessQuery(query, call.string("freshness")))}&format=rss", false)
        val doc = Jsoup.parse(raw.text, raw.url.toString(), Parser.xmlParser())
        val hits = doc.select("item").mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            if (title.isBlank() || safeUrl(link) == null) null else SearchHit(
                title, link, item.selectFirst("description")?.text()?.take(500).orEmpty(),
                item.selectFirst("pubDate")?.text()?.take(100)
            )
        }.distinctBy { normalizeUrl(it.url) }.take(MAX_SEARCH_RESULTS)
        return ResearchOutput(
            "News search: $query",
            hits.joinToString("\n\n") { "${it.title}\n${it.url}\nPublished: ${it.publishedAt.orEmpty()}\n${it.snippet}" }
                .ifBlank { "No results were returned by the public news index." },
            hits.map { it.source() }
        )
    }

    private suspend fun searchProducts(call: ResearchToolCall): ResearchOutput {
        val query = prepareQuery(call) ?: return blocked()
        val discovery = searchWeb(ResearchToolCall("search_web", jsonArgs("query" to query, "domains" to call.string("domains"))))
        val candidates = discovery.sources.take(4)
        if (candidates.isEmpty()) return ResearchOutput("Product search: $query", discovery.evidence)
        val pages = candidates.mapNotNull { source -> runCatching { openPage(source.url) }.getOrNull() }
        val evidence = pages.joinToString("\n\n") { it.evidence }
            .ifBlank { discovery.evidence }
        return ResearchOutput(
            "Product discovery: $query",
            evidence.take(16_000),
            discovery.sources + pages.flatMap { it.sources },
            pages.flatMap { it.images }
        )
    }

    private suspend fun extractProduct(url: String): ResearchOutput {
        val page = openPage(url)
        val structured = page.evidence.lineSequence().filter { line ->
            listOf("product:", "brand:", "price:", "availability:", "rating:", "sku:", "specifications:")
                .any { line.startsWith(it, true) }
        }.joinToString("\n")
        return page.copy(heading = "Product data", evidence = structured.ifBlank { page.evidence })
    }

    private suspend fun searchImages(call: ResearchToolCall): ResearchOutput {
        val query = prepareQuery(call) ?: return blocked()
        val raw = fetchText("https://www.bing.com/images/search?q=${enc(query)}", false)
        val doc = Jsoup.parse(raw.text, raw.url.toString())
        val images = doc.select(".iusc").mapNotNull { node ->
            val metadata = node.attr("m").takeIf(String::isNotBlank)
                ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return@mapNotNull null
            val imageUrl = metadata.string("murl")?.let(::safeUrl)?.toString() ?: return@mapNotNull null
            val pageUrl = metadata.string("purl")?.let(::safeUrl)?.toString() ?: return@mapNotNull null
            ResearchImage(imageUrl, metadata.string("t").orEmpty().ifBlank { "Image result" }, pageUrl)
        }.distinctBy { it.url }.take(8)
        val sources = images.map { ResearchSource(it.alt, it.pageUrl, safeUrl(it.pageUrl)?.host ?: "unknown") }.distinctBy { it.url }
        val evidence = images.mapIndexed { i, image ->
            "${i + 1}. ${image.alt}\nImage URL: ${image.url}\nOriginal page: ${image.pageUrl}"
        }.joinToString("\n\n").ifBlank { "No image results were returned." }
        return ResearchOutput("Image search: $query", evidence, sources, images)
    }

    private suspend fun findInPage(call: ResearchToolCall): ResearchOutput {
        val term = call.string("term").trim().take(120)
        if (term.isBlank()) return ResearchOutput("Find in page", "A non-empty term is required.")
        val page = loadPage(call.string("url"))
        val lines = page.text.split(Regex("(?<=[.!?])\\s+|\\n"))
        val matches = lines.mapIndexedNotNull { index, line ->
            if (!line.contains(term, true)) null
            else lines.subList((index - 1).coerceAtLeast(0), (index + 2).coerceAtMost(lines.size))
                .joinToString(" ").take(700)
        }.distinct().take(8)
        return page.output.copy(heading = "Find in page: $term", evidence = matches.joinToString("\n\n").ifBlank { "No matching passage was found." })
    }

    private suspend fun followLinks(call: ResearchToolCall): ResearchOutput {
        val page = loadPage(call.string("url"))
        val topic = call.string("topic")
        val related = page.links.filter { topic.isBlank() || lexicalScore(topic, it.title) > 0 }
            .take(3)
        val opened = related.mapNotNull { runCatching { openPage(it.url) }.getOrNull() }
        return ResearchOutput(
            "Related pages: $topic",
            opened.joinToString("\n\n") { it.evidence }.ifBlank { "No matching related pages could be opened." }.take(12_000),
            page.output.sources + opened.flatMap { it.sources },
            opened.flatMap { it.images }
        )
    }

    private suspend fun openPage(rawUrl: String): ResearchOutput = loadPage(rawUrl).output

    private suspend fun loadPage(rawUrl: String): PageData {
        val initial = safeUrl(rawUrl) ?: throw IOException("Only public HTTP(S) pages on standard web ports are supported.")
        val key = normalizeUrl(initial.toString())
        pageCache[key]?.takeIf { System.currentTimeMillis() - it.createdAt < PAGE_CACHE_MS }?.let { return it.page }
        val robots = robotsFor(initial)
        if (robots.disallowed.any { pathMatches(initial.encodedPath, it) }) {
            throw IOException("The website's robots.txt disallows automated page access.")
        }
        awaitHostSlot(initial.host, robots.crawlDelayMs)
        val fetched = fetchText(initial.toString(), false)
        val page = withContext(Dispatchers.Default) { parsePage(fetched.text, fetched.url) }
        pageCache[key] = CachedPage(System.currentTimeMillis(), page)
        if (pageCache.size > 100) pageCache.keys.firstOrNull()?.let(pageCache::remove)
        return page
    }

    private fun parsePage(html: String, url: HttpUrl): PageData {
        val doc = Jsoup.parse(html, url.toString())
        val ld = doc.select("script[type=application/ld+json]").map { it.data().ifBlank { it.html() } }.take(12)
        val structured = ld.flatMap { runCatching { flatten(Json.parseToJsonElement(it)) }.getOrDefault(emptyList()) }
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty().ifBlank { doc.title() }.ifBlank { url.host }
        val description = doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content").orEmpty()
        val author = doc.selectFirst("meta[name=author], meta[property=article:author]")?.attr("content")
            ?: structured.firstNotNullOfOrNull { it["author"]?.stringValue() }.orEmpty()
        val published = doc.selectFirst("meta[property=article:published_time], meta[name=date], meta[name=pubdate]")?.attr("content")
            ?: structured.firstNotNullOfOrNull { it["datePublished"]?.stringValue() }
        val products = structured.filter { it.types().any { type -> type.equals("Product", true) } }
            .take(5).joinToString("\n") { productFacts(it) }
        val content = doc.selectFirst("article, main, [role=main]") ?: doc.body()
        val images = linkedMapOf<String, ResearchImage>()
        doc.select("meta[property=og:image], meta[name=twitter:image]").forEach { meta ->
            val urlImage = url.resolve(meta.attr("content"))?.toString()?.let(::safeUrl) ?: return@forEach
            images.putIfAbsent(urlImage.toString(), ResearchImage(urlImage.toString(), title, url.toString()))
        }
        content.select("img[src], img[data-src], img[data-original]").forEach { image ->
            val raw = image.attr("src").ifBlank { image.attr("data-src") }.ifBlank { image.attr("data-original") }
            val imageUrl = url.resolve(raw)?.toString()?.let(::safeUrl) ?: return@forEach
            val alt = image.attr("alt").ifBlank { image.attr("title") }.ifBlank { title }.take(120)
            images.putIfAbsent(imageUrl.toString(), ResearchImage(imageUrl.toString(), alt, url.toString()))
        }
        val links = content.select("a[href]").mapNotNull { anchor ->
            val link = url.resolve(anchor.attr("href"))?.toString() ?: return@mapNotNull null
            if (safeUrl(link) == null) return@mapNotNull null
            val label = anchor.text().trim().ifBlank { anchor.attr("title") }.take(150)
            if (label.length < 3) null else SearchHit(label, link, "")
        }.distinctBy { normalizeUrl(it.url) }.take(30)
        val tables = content.select("table").take(6).joinToString("\n\n") { table ->
            table.select("tr").take(18).joinToString("\n") { row ->
                row.select("th,td").joinToString(" | ") { cell -> cell.text().take(140) }
            }
        }.take(4_000)
        doc.select("script,style,noscript,svg,nav,footer,header,aside,form,button,iframe").remove()
        val cleanRoot = doc.selectFirst("article, main, [role=main]") ?: doc.body()
        val text = cleanRoot.text().replace(Regex("\\s+"), " ").trim().take(MAX_PAGE_TEXT)
        val source = ResearchSource(title.take(220), url.toString(), url.host, description.take(450), published?.take(100))
        val output = ResearchOutput(
            "Page: $title",
            buildString {
                append("URL: ").append(url).append("\nTitle: ").append(title)
                if (author.isNotBlank()) append("\nAuthor: ").append(author)
                if (published != null) append("\nPublished: ").append(published)
                if (description.isNotBlank()) append("\nDescription: ").append(description)
                if (products.isNotBlank()) append("\n\nSTRUCTURED PRODUCT DATA\n").append(products)
                if (tables.isNotBlank()) append("\n\nTABLES\n").append(tables)
                append("\n\nPAGE TEXT\n").append(text)
                if (links.isNotEmpty()) append("\n\nRELATED LINKS\n")
                    .append(links.take(10).joinToString("\n") { "- ${it.title}: ${it.url}" })
            }.take(MAX_PAGE_TEXT),
            listOf(source), images.values.take(8)
        )
        return PageData(output, text, links)
    }

    private fun productFacts(node: JsonObject): String {
        val name = node.string("name").orEmpty()
        val brand = (node["brand"] as? JsonObject)?.string("name") ?: node.string("brand").orEmpty()
        val offer = node["offers"]?.let { if (it is JsonArray) it.firstOrNull() as? JsonObject else it as? JsonObject }
        val price = offer?.string("price") ?: offer?.string("lowPrice").orEmpty()
        val currency = offer?.string("priceCurrency").orEmpty()
        val available = offer?.string("availability")?.substringAfterLast('/').orEmpty()
        val rating = (node["aggregateRating"] as? JsonObject)?.let {
            "${it.string("ratingValue").orEmpty()} / ${it.string("bestRating").orEmpty()} (${it.string("reviewCount") ?: it.string("ratingCount").orEmpty()} reviews)"
        }.orEmpty()
        val specs = node["additionalProperty"]?.let { flatten(it).mapNotNull { item ->
            val key = item.string("name")
            val value = item.string("value")
            if (key.isNullOrBlank() || value.isNullOrBlank()) null else "$key: $value"
        }.take(16).joinToString("; ") }.orEmpty()
        return listOfNotNull(
            "Product: $name".takeIf { name.isNotBlank() },
            "Brand: $brand".takeIf { brand.isNotBlank() },
            "Price: $price $currency".trim().takeIf { price.isNotBlank() },
            "Availability: $available".takeIf { available.isNotBlank() },
            "Rating: $rating".takeIf { rating.isNotBlank() },
            "Specifications: $specs".takeIf { specs.isNotBlank() },
            "Description: ${node.string("description").orEmpty()}".takeIf { !node.string("description").isNullOrBlank() }
        ).joinToString("\n").ifBlank { "Product JSON-LD was present but had no recognized fields." }
    }

    private suspend fun searchDuckDuckGo(query: String): List<SearchHit> {
        val response = fetchText("https://html.duckduckgo.com/html/?q=${enc(query)}", false)
        val doc = Jsoup.parse(response.text, response.url.toString())
        return dedupe(doc.select(".result").mapNotNull { result ->
            val anchor = result.selectFirst("a.result__a") ?: return@mapNotNull null
            val raw = anchor.attr("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = resolveSearchUrl(raw) ?: return@mapNotNull null
            SearchHit(anchor.text().trim(), url, result.selectFirst(".result__snippet")?.text()?.trim().orEmpty())
        })
    }

    private suspend fun searchBing(query: String): List<SearchHit> {
        val response = fetchText("https://www.bing.com/search?q=${enc(query)}", false)
        val doc = Jsoup.parse(response.text, response.url.toString())
        return dedupe(doc.select("li.b_algo").mapNotNull { result ->
            val anchor = result.selectFirst("h2 a") ?: return@mapNotNull null
            val url = safeUrl(anchor.attr("href"))?.toString() ?: return@mapNotNull null
            SearchHit(anchor.text().trim(), url, result.selectFirst(".b_caption p")?.text()?.trim().orEmpty())
        })
    }

    private fun resolveSearchUrl(raw: String): String? {
        val wrapper = raw.toHttpUrlOrNull() ?: "https://duckduckgo.com".toHttpUrlOrNull()?.resolve(raw)
        val target = wrapper?.queryParameter("uddg") ?: wrapper?.toString()
        return target?.let(::safeUrl)?.toString()
    }

    private suspend fun robotsFor(url: HttpUrl): RobotsRules {
        val origin = "${url.scheme}://${url.host}"
        robotsCache[origin]?.let { return it }
        val rules = runCatching {
            val response = fetchText("$origin/robots.txt", false, MAX_ROBOTS_BYTES)
            if (response.code == 404) RobotsRules(emptyList(), 0L) else parseRobots(response.text)
        }.getOrElse { RobotsRules(listOf("/"), 0L) }
        return robotsCache.putIfAbsent(origin, rules) ?: rules
    }

    private fun parseRobots(text: String): RobotsRules {
        var applies = false
        val denied = mutableListOf<String>()
        var crawlDelay = 0L
        text.lineSequence().map { it.substringBefore('#').trim() }.filter { ':' in it }.forEach { line ->
            val key = line.substringBefore(':').trim().lowercase(Locale.ROOT)
            val value = line.substringAfter(':').trim()
            when (key) {
                "user-agent" -> applies = value == "*" || value.equals("InfernoResearch", true)
                "disallow" -> if (applies && value.isNotBlank()) denied += value
                "crawl-delay" -> if (applies) crawlDelay = ((value.toDoubleOrNull() ?: 0.0) * 1_000).toLong().coerceIn(0, 10_000)
            }
        }
        return RobotsRules(denied, crawlDelay)
    }

    private fun pathMatches(path: String, pattern: String): Boolean {
        val rule = pattern.removeSuffix("$")
        return rule.isNotBlank() && path.startsWith(rule)
    }

    private suspend fun awaitHostSlot(host: String, robotsDelay: Long) {
        val previous = hostRequests.put(host, System.currentTimeMillis()) ?: return
        val wait = (maxOf(MIN_HOST_INTERVAL_MS, robotsDelay) - (System.currentTimeMillis() - previous)).coerceAtLeast(0)
        if (wait > 0) delay(wait)
        hostRequests[host] = System.currentTimeMillis()
    }

    private suspend fun fetchText(rawUrl: String, robots: Boolean, maxBytes: Int = MAX_PAGE_BYTES): TextResponse {
        var current = safeUrl(rawUrl) ?: throw IOException("Unsafe URL.")
        if (robots && robotsFor(current).disallowed.any { pathMatches(current.encodedPath, it) }) {
            throw IOException("robots.txt disallows this URL.")
        }
        repeat(MAX_REDIRECTS + 1) {
            val request = Request.Builder().url(current)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/rss+xml,application/xml;q=0.9,*/*;q=0.5")
                .header("Accept-Language", "en-US,en;q=0.8,pt-BR;q=0.6")
                .build()
            val response = awaitResponse(request)
            response.use { res ->
                if (res.code in 300..399) {
                    val location = res.header("Location") ?: throw IOException("Redirect had no Location.")
                    current = current.resolve(location)?.toString()?.let(::safeUrl)
                        ?: throw IOException("Unsafe redirect destination.")
                    return@repeat
                }
                if (!res.isSuccessful) throw IOException("HTTP ${res.code} from ${current.host}.")
                val body = res.body ?: throw IOException("Empty response.")
                val mime = body.contentType()?.let { "${it.type}/${it.subtype}".lowercase(Locale.ROOT) }.orEmpty()
                if (mime.isNotBlank() && !mime.startsWith("text/") && !mime.contains("xml") && !mime.contains("html") && !mime.contains("json")) {
                    throw IOException("Only readable text pages are supported.")
                }
                val bytes = body.byteStream().use { it.readNBytes(maxBytes + 1) }
                if (bytes.size > maxBytes) throw IOException("Page exceeded the safe size limit.")
                val charset = body.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
                return TextResponse(String(bytes, charset), current, res.code)
            }
        }
        throw IOException("Too many redirects.")
    }

    private suspend fun awaitResponse(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resumeWith(Result.success(response))
                else response.close()
            }
        })
    }

    private fun safeUrl(raw: String): HttpUrl? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme !in setOf("http", "https") || url.port !in setOf(80, 443)) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val host = url.host.lowercase(Locale.ROOT).trimEnd('.')
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal")) return null
        return runCatching {
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty() || addresses.any { address ->
                address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                    address.isSiteLocalAddress || address.isMulticastAddress
            }) null else url
        }.getOrNull()
    }

    private fun prepareQuery(call: ResearchToolCall): String? {
        val query = call.string("query").trim().replace(Regex("\\s+"), " ").take(320)
        return query.takeIf { it.isNotBlank() && ResearchSafetyGate.allows(it) }
    }

    private fun blocked() = ResearchOutput("Research unavailable", "This search falls outside the supported safe-research categories. Give a brief safe response without exposing search URLs or instructions.")
    private fun freshnessQuery(query: String, freshness: String): String {
        val days = when (freshness.lowercase(Locale.ROOT)) { "day" -> 1; "week" -> 7; "month" -> 30; "year" -> 365; else -> 0 }
        return if (days == 0) query else "$query after:${java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(days.toLong())}"
    }
    private fun lexicalScore(query: String, text: String): Int {
        val terms = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        return text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).count { it in terms }
    }
    private fun dedupe(hits: List<SearchHit>) = hits.filter { it.title.isNotBlank() && safeUrl(it.url) != null }
        .distinctBy { normalizeUrl(it.url) }.take(MAX_SEARCH_RESULTS)
    private fun normalizeUrl(raw: String) = raw.toHttpUrlOrNull()?.newBuilder()
        ?.removeAllQueryParameters("utm_source")?.removeAllQueryParameters("utm_medium")
        ?.removeAllQueryParameters("utm_campaign")?.fragment(null)?.build()?.toString() ?: raw
    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun JsonObject.string(key: String): String? = this[key]?.stringValue()
    private fun JsonElement.stringValue(): String? = runCatching {
        if (this is JsonObject) this["name"]?.jsonPrimitive?.contentOrNull else jsonPrimitive.contentOrNull
    }.getOrNull()
    private fun JsonObject.types(): List<String> {
        val value = this["@type"] ?: return emptyList()
        return if (value is JsonArray) value.mapNotNull { it.jsonPrimitive.contentOrNull }
        else listOfNotNull(value.jsonPrimitive.contentOrNull)
    }
    private fun flatten(value: JsonElement): List<JsonObject> = when (value) {
        is JsonObject -> listOf(value) + value.values.flatMap(::flatten)
        is JsonArray -> value.flatMap(::flatten)
        else -> emptyList()
    }
    private fun SearchHit.source() = ResearchSource(title, url, url.toHttpUrlOrNull()?.host ?: "unknown", snippet, publishedAt)

    private data class SearchHit(val title: String, val url: String, val snippet: String, val publishedAt: String? = null)
    private data class PageData(val output: ResearchOutput, val text: String, val links: List<SearchHit>)
    private data class CachedPage(val createdAt: Long, val page: PageData)
    private data class RobotsRules(val disallowed: List<String>, val crawlDelayMs: Long)
    private data class TextResponse(val text: String, val url: HttpUrl, val code: Int)

    companion object {
        private const val USER_AGENT = "InfernoResearch/1.0 (Android; respectful public-page reader)"
        private const val MAX_SEARCH_RESULTS = 10
        private const val MAX_PAGE_BYTES = 2_000_000
        private const val MAX_ROBOTS_BYTES = 96_000
        private const val MAX_PAGE_TEXT = 10_000
        private const val MAX_REDIRECTS = 5
        private const val PAGE_CACHE_MS = 45 * 60 * 1_000L
        private const val MIN_HOST_INTERVAL_MS = 700L
    }
}

private object ResearchSafetyGate {
    private val blocked = listOf(
        Regex("\\b(firearms?|guns?|ammunition|explosives?|bombs?|detonators?|switchblades?|weaponized|weapons?)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(cocaine|heroin|methamphetamine|fentanyl|opioids?|marijuana|cannabis|poisons?|toxins?|steroids)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(suicide|self[- ]harm|self[- ]injury|pro[- ]ana|eating disorder)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(pornography|porn|adult sex|explicit sexual content|sex toys?)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(gambling|casino|sports betting|online betting|nicotine|vape|cigarettes?|alcohol|beer|wine|liquor)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(arma de fogo|armas|munição|municoes|explosivos?|bombas?|detonadores?|drogas|cocaína|cocaina|maconha|venenos?|toxinas?|suicídio|suicidio|automutilação|automutilacao|pornografia|cassino|apostas|nicotina|vape|cigarros?|álcool|alcool|cerveja|vinho)\\b", RegexOption.IGNORE_CASE)
    )
    fun allows(query: String): Boolean = blocked.none { it.containsMatchIn(query) }
}

private fun jsonArgs(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.filter { it.second.isNotBlank() }.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })
