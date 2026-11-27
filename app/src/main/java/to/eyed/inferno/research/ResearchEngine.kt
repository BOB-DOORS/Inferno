package to.eyed.inferno.research

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import android.content.Context
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException

/**
 * Native, API-key-free public-web research. Retrieval and parsing stay on-device; only focused queries and requested
 * public URLs are sent to the selected public search/page hosts. No cloud model or account is involved.
 */
internal class ResearchEngine(context: Context, private val client: OkHttpClient) {
    private val persistentCache = ResearchPersistentCache(context)
    private val javascriptRenderer = JavascriptPageRenderer(context, client)
    private val localIndex = LocalWebIndex(context)
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
                    "search_videos" -> searchVideos(call)
                    "search_academic" -> searchAcademic(call)
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
        val domains = call.string("domains").split(',', ';', ' ')
            .map(String::trim).filter(String::isNotBlank).take(5)
            .mapNotNull { raw ->
                raw.removePrefix("https://").removePrefix("http://").substringBefore('/')
                    .takeIf { it.isNotBlank() && '/' !in it && ' ' !in it }
            }
        val scoped = if (domains.isEmpty()) query else "$query " + domains.joinToString(" ") { "site:$it" }
        val queryWithDate = freshnessQuery(scoped, call.string("freshness"))
        val localBeforeCrawl = localIndex.search(query, MAX_SEARCH_RESULTS).map { hit ->
            SearchHit(hit.title, hit.url, hit.snippet)
        }
        var hits = coroutineScope {
            val ddg = async { searchProvider { searchDuckDuckGo(queryWithDate) } }
            val bing = async { searchProvider { searchBing(queryWithDate) } }
            dedupe(localBeforeCrawl + ddg.await() + bing.await())
        }
        if (hits.size < MIN_SEARCH_RESULTS) {
            hits = dedupe(hits + searchProvider { searchYahoo(queryWithDate) })
        }

        // Search results seed a persistent local crawl. A small batch is processed in this foreground
        // request; the remaining frontier survives process death and is drained by later searches.
        crawlDiscoveredPages(hits)
        val localAfterCrawl = localIndex.search(query, MAX_SEARCH_RESULTS).map { hit ->
            SearchHit(hit.title, hit.url, hit.snippet)
        }
        hits = rankAndDiversify(query, dedupe(hits + localAfterCrawl))

        val pages = coroutineScope {
            hits.take(MAX_AUTO_OPEN_RESULTS).map { hit -> async { openPageSafely(hit.url) } }
                .awaitAll()
                .filterNotNull()
        }
        val sources = linkedMapOf<String, ResearchSource>()
        pages.flatMap { it.sources }.forEach { sources.putIfAbsent(it.url, it) }
        hits.map { it.source() }.forEach { sources.putIfAbsent(it.url, it) }
        val snippets = if (hits.isEmpty()) {
            "No results were returned by DuckDuckGo, Bing, or Yahoo. A provider may be unavailable, rate-limiting this connection, or disallowing automated access in robots.txt."
        } else hits.joinToString("\n\n") { hit ->
            buildString {
                append(hit.title).append("\n").append(hit.url)
                if (hit.publishedAt != null) append("\nPublished: ").append(hit.publishedAt)
                if (hit.snippet.isNotBlank()) append("\n").append(hit.snippet.take(500))
            }
        }
        val pageEvidence = pages.joinToString("\n\n") { it.evidence }
        val evidence = buildString {
            append("SEARCH RESULTS\n").append(snippets)
            if (pageEvidence.isNotBlank()) append("\n\nOPENED TOP PAGES\n").append(pageEvidence)
        }
        return ResearchOutput("Web search: $query", evidence.take(15_000), sources.values.toList())
    }

    /**
     * Adds search hits and public sitemap entries to the durable frontier, then indexes a bounded number of pages.
     * Every actual page request goes through loadPage(), which enforces HTTPS, DNS/SSRF, robots.txt, redirects,
     * per-host pacing, response-size limits and the same JS fallback as ordinary research.
     */
    private suspend fun crawlDiscoveredPages(hits: List<SearchHit>) {
        hits.take(MAX_CRAWL_SEEDS).forEach { localIndex.enqueue(it.url, depth = 0, priority = 8) }

        val seedHosts = hits.mapNotNull { hit -> safeUrl(hit.url) }
            .distinctBy { it.host }
            .take(MAX_SITEMAP_HOSTS)
        for (seed in seedHosts) {
            if (!localIndex.shouldProbeSitemap(seed.host)) continue
            try {
                val sitemapUrl = seed.newBuilder().encodedPath("/sitemap.xml").query(null).fragment(null).build()
                val response = fetchText(sitemapUrl.toString(), robots = true, maxBytes = MAX_SITEMAP_BYTES)
                if (response.code in 200..299 && response.text.isNotBlank()) {
                    enqueueSitemapEntries(response.text, response.url)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Sitemaps are optional. A failed probe must never block normal indexed search.
            } finally {
                localIndex.markSitemapChecked(seed.host)
            }
        }

        val candidates = localIndex.takeNext(MAX_CRAWL_PAGES_PER_QUERY)
        for (candidate in candidates) {
            if (localIndex.isIndexedFresh(candidate.url)) continue
            try {
                val page = loadPage(candidate.url)
                val source = page.output.sources.firstOrNull()
                val finalUrl = source?.url ?: candidate.url
                localIndex.indexPage(
                    finalUrl,
                    source?.title ?: page.output.heading.removePrefix("Page: "),
                    page.text,
                )
                if (candidate.depth < MAX_CRAWL_DEPTH) {
                    page.links.asSequence()
                        .filter { it.title.length >= MIN_CRAWL_LINK_TITLE }
                        .take(MAX_LINKS_PER_CRAWLED_PAGE)
                        .forEach { link -> localIndex.enqueue(link.url, candidate.depth + 1, priority = 3) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Failed, disallowed, non-HTML and unsafe targets are dropped; they are never retried in a tight loop.
            }
        }
    }

    private suspend fun enqueueSitemapEntries(xml: String, sitemapUrl: HttpUrl) {
        val document = Jsoup.parse(xml, sitemapUrl.toString(), Parser.xmlParser())
        val pageLocations = document.select("urlset > url > loc")
            .map { it.text().trim() }
            .filter(String::isNotBlank)
            .take(MAX_SITEMAP_PAGE_URLS)
        pageLocations.forEach { localIndex.enqueue(it, depth = 0, priority = 6) }

        // Sitemap indexes can point to child sitemap documents; follow only a tiny bounded number.
        if (pageLocations.isEmpty()) {
            val childSitemaps = document.select("sitemapindex > sitemap > loc")
                .map { it.text().trim() }
                .filter(String::isNotBlank)
                .take(MAX_CHILD_SITEMAPS)
            for (child in childSitemaps) {
                try {
                    val response = fetchText(child, robots = true, maxBytes = MAX_SITEMAP_BYTES)
                    if (response.code in 200..299) {
                        val childDocument = Jsoup.parse(response.text, response.url.toString(), Parser.xmlParser())
                        childDocument.select("urlset > url > loc")
                            .take(MAX_SITEMAP_PAGE_URLS / MAX_CHILD_SITEMAPS)
                            .map { it.text().trim() }
                            .filter(String::isNotBlank)
                            .forEach { localIndex.enqueue(it, depth = 0, priority = 6) }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A malformed child sitemap does not prevent indexing the remaining frontier.
                }
            }
        }
    }

    private suspend fun searchProvider(block: suspend () -> List<SearchHit>): List<SearchHit> =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }

    private suspend fun openPageSafely(url: String): ResearchOutput? =
        try {
            openPage(url)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private fun rankAndDiversify(query: String, hits: List<SearchHit>): List<SearchHit> {
        val ranked = dedupe(hits).sortedWith(
            compareByDescending<SearchHit> { lexicalScore(query, it.title + " " + it.snippet) }
                .thenByDescending { it.snippet.length },
        )
        val selected = ArrayList<SearchHit>(MAX_SEARCH_RESULTS)
        val usedHosts = HashSet<String>()
        ranked.forEach { hit ->
            val host = safeUrl(hit.url)?.host ?: return@forEach
            if (usedHosts.add(host) && selected.size < MAX_SEARCH_RESULTS) selected += hit
        }
        ranked.forEach { hit ->
            if (selected.size < MAX_SEARCH_RESULTS &&
                selected.none { normalizeUrl(it.url) == normalizeUrl(hit.url) }
            ) selected += hit
        }
        return selected
    }

    private suspend fun searchNews(call: ResearchToolCall): ResearchOutput {
        val query = prepareQuery(call) ?: return blocked()
        val raw = fetchText("https://www.bing.com/news/search?q=${enc(freshnessQuery(query, call.string("freshness")))}&format=rss", true)
        val doc = Jsoup.parse(raw.text, raw.url.toString(), Parser.xmlParser())
        val hits = doc.select("item").mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            if (title.isBlank() || safeUrl(link) == null) null else SearchHit(
                title, link, item.selectFirst("description")?.text()?.take(500).orEmpty(),
                item.selectFirst("pubDate")?.text()?.take(100)
            )
        }.filter { ResearchSafetyGate.allows(it.title + " " + it.snippet + " " + it.url) }
            .distinctBy { normalizeUrl(it.url) }.take(MAX_SEARCH_RESULTS)
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
        val pages = coroutineScope {
            candidates.map { source -> async { openPageSafely(source.url) } }
                .awaitAll()
                .filterNotNull()
        }
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
        val raw = fetchText("https://www.bing.com/images/search?q=${enc(query)}", true)
        val doc = Jsoup.parse(raw.text, raw.url.toString())
        val images = doc.select(".iusc[data-m], .iusc[m], a.iusc")
            .mapNotNull { node ->
                val rawMetadata = node.attr("m").ifBlank { node.attr("data-m") }
                val metadata = rawMetadata.takeIf(String::isNotBlank)
                    ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                val thumbnail = node.selectFirst("img")
                val imageUrl = metadata?.string("murl")?.let(::safeUrl)?.toString()
                    ?: thumbnail?.let { img ->
                        listOf(img.absUrl("data-src"), img.absUrl("src"))
                            .firstNotNullOfOrNull { safeUrl(it)?.toString() }
                    }
                    ?: return@mapNotNull null
                val pageUrl = metadata?.string("purl")?.let(::safeUrl)?.toString()
                    ?: node.selectFirst("a[href]")?.absUrl("href")?.let(::resolveSearchUrl)
                    ?: return@mapNotNull null
                val alt = metadata?.string("t").orEmpty().ifBlank {
                    thumbnail?.attr("alt")?.takeIf(String::isNotBlank)
                        ?: node.attr("title").takeIf(String::isNotBlank)
                        ?: "Image result"
                }
                ResearchImage(imageUrl, alt.take(140), pageUrl)
            }
            .filter { ResearchSafetyGate.allows(it.alt + " " + it.pageUrl) }
            .distinctBy { normalizeUrl(it.url) }
            .take(MAX_IMAGE_RESULTS)
        val evidence = if (images.isEmpty()) {
            "The image index returned no usable public image links. Do not invent images or claim results were found."
        } else {
            "Retrieved ${images.size} real images for '$query'. The app displays them automatically after the answer. " +
                "Titles: " + images.mapIndexed { index, image -> "${index + 1}. ${image.alt}" }.joinToString("; ")
        }
        return ResearchOutput("Image search: $query", evidence, images = images, renderImages = true)
    }

    private suspend fun searchVideos(call: ResearchToolCall): ResearchOutput {
        val query = prepareQuery(call) ?: return blocked()
        val raw = fetchText(
            "https://www.bing.com/videos/search?q=${enc(freshnessQuery(query, call.string("freshness")))}",
            true,
        )
        val doc = Jsoup.parse(raw.text, raw.url.toString())
        val hits = doc.select(".mc_vtvc, .mc_vtvc[data-m], .mc_c")
            .mapNotNull { node ->
                val rawMetadata = node.attr("m").ifBlank { node.attr("data-m") }
                val metadata = rawMetadata.takeIf(String::isNotBlank)
                    ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                val anchor = node.selectFirst("a[href]")
                val url = metadata?.string("purl")?.let(::safeUrl)?.toString()
                    ?: anchor?.absUrl("href")?.let(::resolveSearchUrl)
                    ?: return@mapNotNull null
                val title = metadata?.string("t").orEmpty().ifBlank {
                    anchor?.attr("title")?.takeIf(String::isNotBlank) ?: anchor?.text()?.trim().orEmpty()
                }
                if (title.isBlank() || !ResearchSafetyGate.allows(title + " " + url)) return@mapNotNull null
                val duration = metadata?.string("du") ?: metadata?.string("dur")
                val publisher = metadata?.string("pub") ?: metadata?.string("provider")
                val snippet = buildString {
                    publisher?.takeIf(String::isNotBlank)?.let { append("Publisher: ").append(it).append(". ") }
                    duration?.takeIf(String::isNotBlank)?.let { append("Duration: ").append(it).append(". ") }
                    node.selectFirst(".mc_vtvc_meta, .mc_vtvc_caption, .mc_vtvc_subtitle")
                        ?.text()?.takeIf(String::isNotBlank)?.let { append(it.take(280)) }
                }
                SearchHit(title.take(220), url, snippet, metadata?.string("pubDate"))
            }
            .distinctBy { normalizeUrl(it.url) }
            .take(MAX_SEARCH_RESULTS)
        return ResearchOutput(
            "Video search: $query",
            hits.joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet}" }
                .ifBlank { "No usable public video results were returned by the video index." },
            hits.map { it.source() },
        )
    }

    private suspend fun searchAcademic(call: ResearchToolCall): ResearchOutput {
        val query = prepareQuery(call) ?: return blocked()
        val response = fetchText(
            "https://api.crossref.org/works?query.bibliographic=${enc(query)}&rows=8",
            true,
            MAX_ACADEMIC_BYTES,
        )
        val items = runCatching {
            Json.parseToJsonElement(response.text).jsonObject["message"]?.jsonObject?.get("items") as? JsonArray
        }.getOrNull() ?: JsonArray(emptyList())
        val hits = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val title = (item["title"] as? JsonArray)?.firstOrNull()
                ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?.trim().orEmpty()
            val url = item.string("URL")?.let(::safeUrl)?.toString()
                ?: item.string("DOI")?.let { safeUrl("https://doi.org/$it")?.toString() }
                ?: return@mapNotNull null
            if (title.isBlank() || !ResearchSafetyGate.allows(title + " " + url)) return@mapNotNull null
            val authors = (item["author"] as? JsonArray).orEmpty().take(5).mapNotNull { authorElement ->
                val author = authorElement as? JsonObject ?: return@mapNotNull null
                listOf(author.string("given"), author.string("family"))
                    .filterNotNull().joinToString(" ").takeIf(String::isNotBlank)
            }.joinToString(", ")
            val journal = (item["container-title"] as? JsonArray)?.firstOrNull()
                ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
            val dateObject = (item["published-print"] ?: item["published-online"] ?: item["created"]) as? JsonObject
            val dateParts = (dateObject?.get("date-parts") as? JsonArray)?.firstOrNull() as? JsonArray
            val published = dateParts?.mapNotNull { part ->
                runCatching { part.jsonPrimitive.contentOrNull }.getOrNull()
            }?.joinToString("-")
            val abstractText = item.string("abstract")?.let { Jsoup.parse(it).text().take(800) }.orEmpty()
            val snippet = buildString {
                if (authors.isNotBlank()) append("Authors: ").append(authors).append("\n")
                if (journal.isNotBlank()) append("Journal: ").append(journal).append("\n")
                if (abstractText.isNotBlank()) append("Abstract: ").append(abstractText)
            }
            SearchHit(title.take(220), url, snippet, published)
        }.distinctBy { normalizeUrl(it.url) }.take(MAX_SEARCH_RESULTS)
        return ResearchOutput(
            "Academic search: $query",
            hits.joinToString("\n\n") { hit ->
                buildString {
                    append(hit.title).append("\n").append(hit.url)
                    hit.publishedAt?.let { append("\nPublished: ").append(it) }
                    if (hit.snippet.isNotBlank()) append("\n").append(hit.snippet)
                }
            }.ifBlank { "Crossref returned no matching scholarly records." },
            hits.map { it.source() },
        )
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

    private suspend fun openPage(rawUrl: String): ResearchOutput {
        val page = loadPage(rawUrl)
        val source = page.output.sources.firstOrNull()
        if (source != null) {
            localIndex.indexPage(source.url, source.title, page.text)
            page.links.take(MAX_LINKS_PER_CRAWLED_PAGE).forEach { link ->
                localIndex.enqueue(link.url, depth = 1, priority = 2)
            }
        }
        return page.output
    }

    private suspend fun loadPage(rawUrl: String): PageData {
        val initial = safeUrl(rawUrl) ?: throw IOException("Only public HTTPS pages on port 443 are supported.")
        val key = normalizeUrl(initial.toString())
        val initialRobots = robotsFor(initial)
        if (!robotsAllows(initial, initialRobots)) {
            throw IOException("The website's robots.txt disallows automated page access.")
        }

        val memoryCached = pageCache[key]?.takeIf {
            System.currentTimeMillis() - it.createdAt in 0..PAGE_CACHE_MS
        }
        if (memoryCached != null) {
            val finalUrl = memoryCached.page.output.sources.firstOrNull()?.url?.let(::safeUrl) ?: initial
            val finalRobots = if (sameOrigin(initial, finalUrl)) initialRobots else robotsFor(finalUrl)
            if (!robotsAllows(finalUrl, finalRobots)) {
                throw IOException("The redirected page is disallowed by its robots.txt policy.")
            }
            return memoryCached.page
        }

        val saved = persistentCache.get(ResearchPersistentCache.KIND_PAGE, key, PAGE_CACHE_MS)
        if (saved != null) {
            val separator = saved.indexOf('\n')
            if (separator > 0) {
                val cachedUrl = safeUrl(saved.substring(0, separator)) ?: initial
                val cachedRobots = if (sameOrigin(initial, cachedUrl)) initialRobots else robotsFor(cachedUrl)
                if (!robotsAllows(cachedUrl, cachedRobots)) {
                    throw IOException("The cached redirect target is disallowed by its robots.txt policy.")
                }
                val cachedHtml = saved.substring(separator + 1)
                val cachedPage = runCatching {
                    withContext(Dispatchers.Default) { parsePage(cachedHtml, cachedUrl) }
                }.getOrNull()
                if (cachedPage != null) {
                    rememberPage(key, cachedPage)
                    return cachedPage
                }
            }
        }

        val fetched = fetchText(initial.toString(), true)
        var pageHtml = fetched.text
        var page = withContext(Dispatchers.Default) { parsePage(pageHtml, fetched.url) }
        if (ResearchPageHeuristics.shouldRenderJavascript(pageHtml, page.text)) {
            val renderedHtml = javascriptRenderer.render(fetched.url.toString(), pageHtml)
            if (!renderedHtml.isNullOrBlank()) {
                val renderedPage = withContext(Dispatchers.Default) { parsePage(renderedHtml, fetched.url) }
                if (renderedPage.text.length > page.text.length + MIN_JS_TEXT_GAIN || page.text.isBlank()) {
                    pageHtml = renderedHtml
                    page = renderedPage
                }
            }
        }

        persistentCache.put(ResearchPersistentCache.KIND_PAGE, key, fetched.url.toString() + "\n" + pageHtml)
        rememberPage(key, page)
        return page
    }

    private fun rememberPage(key: String, page: PageData) {
        pageCache[key] = CachedPage(System.currentTimeMillis(), page)
        while (pageCache.size > MAX_MEMORY_PAGES) {
            val oldestCandidate = pageCache.keys.firstOrNull { it != key } ?: break
            pageCache.remove(oldestCandidate)
        }
    }

    private fun robotsAllows(url: HttpUrl, rules: RobotsRules): Boolean {
        val pathAndQuery = url.encodedPath + url.encodedQuery?.let { "?$it" }.orEmpty()
        return rules.policy.allows(pathAndQuery)
    }

    private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port

    private fun parsePage(html: String, url: HttpUrl): PageData {
        val doc = Jsoup.parse(html, url.toString())
        val ld = doc.select("script[type=application/ld+json]").map { it.data().ifBlank { it.html() } }.take(12)
        val structured = ld.asSequence()
            .flatMap { raw -> runCatching { flatten(Json.parseToJsonElement(raw)) }.getOrDefault(emptyList()).asSequence() }
            .take(MAX_STRUCTURED_OBJECTS)
            .toList()
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
        doc.select("meta[property=og:image], meta[name=twitter:image]").take(MAX_META_IMAGES).forEach { meta ->
            val urlImage = url.resolve(meta.attr("content"))?.toString()?.let(::safeUrl) ?: return@forEach
            images.putIfAbsent(urlImage.toString(), ResearchImage(urlImage.toString(), title, url.toString()))
        }
        content.select("img[src], img[data-src], img[data-original]").take(MAX_PAGE_IMAGES).forEach { image ->
            val raw = image.attr("src").ifBlank { image.attr("data-src") }.ifBlank { image.attr("data-original") }
            val imageUrl = url.resolve(raw)?.toString()?.let(::safeUrl) ?: return@forEach
            val alt = image.attr("alt").ifBlank { image.attr("title") }.ifBlank { title }.take(120)
            images.putIfAbsent(imageUrl.toString(), ResearchImage(imageUrl.toString(), alt, url.toString()))
        }
        val links = content.select("a[href]").take(MAX_LINK_CANDIDATES).mapNotNull { anchor ->
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
        if (!ResearchSafetyGate.allows(title + " " + description + " " + text.take(4_000))) {
            throw IOException("This page is outside the supported safe-research categories.")
        }
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
        val response = fetchText("https://html.duckduckgo.com/html/?q=${enc(query)}", true)
        val doc = Jsoup.parse(response.text, response.url.toString())
        return dedupe(doc.select(".result").mapNotNull { result ->
            val anchor = result.selectFirst("a.result__a") ?: return@mapNotNull null
            val raw = anchor.attr("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = resolveSearchUrl(raw) ?: return@mapNotNull null
            SearchHit(anchor.text().trim(), url, result.selectFirst(".result__snippet")?.text()?.trim().orEmpty())
        })
    }

    private suspend fun searchBing(query: String): List<SearchHit> {
        val response = fetchText("https://www.bing.com/search?q=${enc(query)}", true)
        val doc = Jsoup.parse(response.text, response.url.toString())
        return dedupe(doc.select("li.b_algo").mapNotNull { result ->
            val anchor = result.selectFirst("h2 a") ?: return@mapNotNull null
            val url = safeUrl(anchor.attr("href"))?.toString() ?: return@mapNotNull null
            SearchHit(anchor.text().trim(), url, result.selectFirst(".b_caption p")?.text()?.trim().orEmpty())
        })
    }

    private suspend fun searchYahoo(query: String): List<SearchHit> {
        val response = fetchText("https://search.yahoo.com/search?p=${enc(query)}", true)
        val doc = Jsoup.parse(response.text, response.url.toString())
        return dedupe(doc.select("div.algo, li.algo").mapNotNull { result ->
            val anchor = result.selectFirst("h3 a, a.ac-al-obj") ?: return@mapNotNull null
            val url = resolveSearchUrl(anchor.absUrl("href")) ?: return@mapNotNull null
            SearchHit(anchor.text().trim(), url, result.selectFirst(".compText, .compTitle, p")?.text()?.trim().orEmpty())
        })
    }

    private fun resolveSearchUrl(raw: String): String? {
        val wrapper = raw.toHttpUrlOrNull() ?: "https://duckduckgo.com".toHttpUrlOrNull()?.resolve(raw)
        val yahooTarget = wrapper?.encodedPath?.substringAfter("/RU=", "")
            ?.takeIf(String::isNotBlank)?.substringBefore("/RK=")
            ?.let { runCatching { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull() }
        val target = wrapper?.queryParameter("uddg") ?: wrapper?.queryParameter("url") ?: yahooTarget ?: wrapper?.toString()
        return target?.let(::safeUrl)?.toString()
    }

    private suspend fun robotsFor(url: HttpUrl): RobotsRules {
        val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
        val now = System.currentTimeMillis()
        robotsCache[origin]?.let { cached ->
            if (now - cached.cachedAt in 0..ROBOTS_CACHE_MS) return cached
            robotsCache.remove(origin, cached)
        }

        val cachedText = persistentCache.get(ResearchPersistentCache.KIND_ROBOTS, origin, ROBOTS_CACHE_MS)
        if (cachedText != null) {
            return RobotsRules(ResearchRobotsPolicy.parse(cachedText), now)
                .also { robotsCache[origin] = it }
        }

        val response = try {
            fetchText("$origin/robots.txt", false, MAX_ROBOTS_BYTES)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return RobotsRules(ResearchRobotsRules.denyAll(), now)
        }
        if (response.code != 404 && response.code !in 200..299) {
            return RobotsRules(ResearchRobotsRules.denyAll(), now)
        }

        val robotsText = if (response.code == 404) "User-agent: *\n" else response.text
        persistentCache.put(ResearchPersistentCache.KIND_ROBOTS, origin, robotsText)
        return RobotsRules(ResearchRobotsPolicy.parse(robotsText), now)
            .also { robotsCache[origin] = it }
    }

    private suspend fun awaitHostSlot(host: String, robotsDelay: Long) {
        val previous = hostRequests.put(host, System.currentTimeMillis()) ?: return
        val wait = (maxOf(MIN_HOST_INTERVAL_MS, robotsDelay) - (System.currentTimeMillis() - previous)).coerceAtLeast(0)
        if (wait > 0) delay(wait)
        hostRequests[host] = System.currentTimeMillis()
    }

    private suspend fun fetchText(rawUrl: String, robots: Boolean, maxBytes: Int = MAX_PAGE_BYTES): TextResponse {
        var current = safeUrl(rawUrl) ?: throw IOException("Unsafe URL.")
        repeat(MAX_REDIRECTS + 1) {
            if (robots) {
                val rules = robotsFor(current).policy
                val pathAndQuery = current.encodedPath + current.encodedQuery?.let { "?$it" }.orEmpty()
                if (!rules.allows(pathAndQuery)) throw IOException("robots.txt disallows this URL.")
                awaitHostSlot(current.host, rules.crawlDelayMs)
            }

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
                if (res.code == 404 && current.encodedPath == "/robots.txt") {
                    return TextResponse("", current, 404)
                }
                if (!res.isSuccessful) throw IOException("HTTP " + res.code + " from " + current.host + ".")
                val body = res.body ?: throw IOException("Empty response.")
                val mime = body.contentType()?.let { it.type + "/" + it.subtype }.orEmpty().lowercase(Locale.ROOT)
                if (mime.isNotBlank() && !mime.startsWith("text/") && !mime.contains("xml") &&
                    !mime.contains("html") && !mime.contains("json")
                ) {
                    throw IOException("Only readable text pages are supported.")
                }
                val bytes = body.byteStream().use { readBounded(it, maxBytes) }
                val charset = body.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
                return TextResponse(String(bytes, charset), current, res.code)
            }
        }
        throw IOException("Too many redirects.")
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        if (maxBytes <= 0) throw IOException("Invalid response size limit.")
        val output = ByteArrayOutputStream(minOf(maxBytes, INITIAL_RESPONSE_BUFFER_BYTES))
        val buffer = ByteArray(RESPONSE_READ_BUFFER_BYTES)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maxBytes) throw IOException("Page exceeded the safe size limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
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

    private fun safeUrl(raw: String): HttpUrl? = ResearchUrlPolicy.safeUrl(raw)

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
    private fun flatten(value: JsonElement): List<JsonObject> {
        val pending = ArrayDeque<JsonElement>()
        val objects = ArrayList<JsonObject>()
        pending.addLast(value)
        var visited = 0

        while (pending.isNotEmpty() && visited < MAX_JSONLD_NODES && objects.size < MAX_JSONLD_OBJECTS) {
            when (val current = pending.removeFirst()) {
                is JsonObject -> {
                    objects += current
                    current.values.asSequence().take(MAX_JSONLD_CHILDREN).forEach { pending.addLast(it) }
                }
                is JsonArray -> current.asSequence().take(MAX_JSONLD_CHILDREN).forEach { pending.addLast(it) }
                else -> Unit
            }
            visited++
        }
        return objects
    }
    private fun SearchHit.source() = ResearchSource(title, url, url.toHttpUrlOrNull()?.host ?: "unknown", snippet, publishedAt)

    private data class SearchHit(val title: String, val url: String, val snippet: String, val publishedAt: String? = null)
    private data class PageData(val output: ResearchOutput, val text: String, val links: List<SearchHit>)
    private data class CachedPage(val createdAt: Long, val page: PageData)
    private data class RobotsRules(val policy: ResearchRobotsRules, val cachedAt: Long = System.currentTimeMillis())
    private data class TextResponse(val text: String, val url: HttpUrl, val code: Int)

    companion object {
        private const val USER_AGENT = "InfernoResearch/1.0 (Android; respectful public-page reader)"
        private const val MAX_SEARCH_RESULTS = 10
        private const val MAX_CRAWL_SEEDS = 8
        private const val MAX_SITEMAP_HOSTS = 2
        private const val MAX_SITEMAP_BYTES = 512_000
        private const val MAX_SITEMAP_PAGE_URLS = 24
        private const val MAX_CHILD_SITEMAPS = 2
        private const val MAX_CRAWL_PAGES_PER_QUERY = 3
        private const val MAX_CRAWL_DEPTH = 1
        private const val MAX_LINKS_PER_CRAWLED_PAGE = 8
        private const val MIN_CRAWL_LINK_TITLE = 3
        private const val MIN_SEARCH_RESULTS = 5
        private const val MAX_AUTO_OPEN_RESULTS = 2
        private const val MAX_IMAGE_RESULTS = 6
        private const val MAX_ACADEMIC_BYTES = 1_200_000
        private const val MAX_META_IMAGES = 4
        private const val MAX_PAGE_IMAGES = 48
        private const val MAX_LINK_CANDIDATES = 120
        private const val MAX_STRUCTURED_OBJECTS = 256
        private const val MAX_JSONLD_OBJECTS = 128
        private const val MAX_JSONLD_NODES = 512
        private const val MAX_JSONLD_CHILDREN = 64
        private const val MAX_PAGE_BYTES = 2_000_000
        private const val MAX_ROBOTS_BYTES = 96_000
        private const val MAX_PAGE_TEXT = 10_000
        private const val MAX_REDIRECTS = 5
        private const val PAGE_CACHE_MS = 6 * 60 * 60 * 1_000L
        private const val ROBOTS_CACHE_MS = 24 * 60 * 60 * 1_000L
        private const val MAX_MEMORY_PAGES = 24
        private const val MIN_JS_TEXT_GAIN = 80
        private const val MIN_HOST_INTERVAL_MS = 700L
        private const val INITIAL_RESPONSE_BUFFER_BYTES = 64 * 1024
        private const val RESPONSE_READ_BUFFER_BYTES = 8 * 1024
    }
}

internal object ResearchSafetyGate {
    private val blocked = listOf(
        Regex("\\b(firearms?|guns?|ammunition|explosives?|bombs?|detonators?|switchblades?|weapons?|knives?|blades?|tasers?|pepper spray|mace|stun guns?|weaponized)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(cocaine|heroin|methamphetamine|fentanyl|opioids?|marijuana|cannabis|poisons?|toxins?|pesticides|steroids|intoxicants?|hallucinogens?|controlled substances)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(suicide|self[- ]harm|self[- ]injury|pro[- ]ana|eating disorder)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(pornography|porn|adult sex|explicit sexual content|sex toys?)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(gambling|casino|sports betting|online betting|prediction markets?|betting sites?|nicotine|vape|cigarettes?|alcohol|beer|wine|liquor|dangerous challenge|dangerous stunt)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(arma de fogo|armas|munição|municoes|explosivos?|bombas?|detonadores?|drogas|cocaína|cocaina|maconha|venenos?|toxinas?|suicídio|suicidio|automutilação|automutilacao|pornografia|cassino|apostas|nicotina|vape|cigarros?|álcool|alcool|cerveja|vinho)\\b", RegexOption.IGNORE_CASE)
    )
    fun allows(query: String): Boolean = blocked.none { it.containsMatchIn(query) }
}

private fun jsonArgs(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.filter { it.second.isNotBlank() }.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })
