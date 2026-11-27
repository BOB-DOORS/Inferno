package to.eyed.inferno.research

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Network bridge for the temporary JavaScript WebView.
 *
 * Chromium never opens a socket for a page resource: every HTTP(S) GET is fetched through the research OkHttp
 * client, whose DNS implementation returns the exact public addresses that passed ResearchUrlPolicy. Redirects
 * are followed manually and revalidated; robots rules are applied to each origin/path. No cookie, authorization,
 * proxy, or other ambient credential headers are forwarded from WebView.
 */
internal class ResearchWebResourceLoader(private val client: OkHttpClient) {
    private data class CachedRobots(val rules: ResearchRobotsRules, val expiresAt: Long)

    private val robotsCache = ConcurrentHashMap<String, CachedRobots>()
    private val requestCount = AtomicInteger()
    private val totalBytes = AtomicLong()

    fun intercept(
        request: WebResourceRequest,
        initialPage: HttpUrl,
        activeCalls: MutableSet<Call>,
    ): WebResourceResponse {
        if (!request.method.equals("GET", ignoreCase = true)) {
            return blocked(405, "Only GET requests are allowed.")
        }
        if (requestCount.incrementAndGet() > MAX_RESOURCE_REQUESTS) {
            return blocked(429, "Research resource request limit reached.")
        }

        var current = ResearchUrlPolicy.safeUrl(request.url.toString())
            ?: return blocked(403, "URL rejected by research network policy.")
        if (request.isForMainFrame && !sameOrigin(current, initialPage)) {
            return blocked(403, "Cross-origin top-level navigation is blocked.")
        }

        val forwardedHeaders = request.requestHeaders
            .filterKeys { it.lowercase() in FORWARDED_REQUEST_HEADERS }
            .filterValues { it.length <= MAX_HEADER_VALUE_CHARS }

        repeat(MAX_REDIRECTS + 1) {
            current = ResearchUrlPolicy.safeUrl(current.toString())
                ?: return blocked(403, "Redirect target rejected by research network policy.")

            if (!robotsFor(current, activeCalls).allows(pathAndQuery(current))) {
                return blocked(403, "robots.txt disallows this resource.")
            }

            val requestBuilder = Request.Builder()
                .url(current)
                .header("User-Agent", USER_AGENT)
                .header("Accept", forwardedHeaders["Accept"] ?: "*/*")

            forwardedHeaders["Accept-Language"]?.let { requestBuilder.header("Accept-Language", it) }
            forwardedHeaders["Origin"]?.takeIf { it.length <= MAX_HEADER_VALUE_CHARS }
                ?.let { requestBuilder.header("Origin", it) }
            forwardedHeaders["Referer"]?.takeIf { it.length <= MAX_HEADER_VALUE_CHARS }
                ?.let { requestBuilder.header("Referer", it) }
            forwardedHeaders["Range"]?.let { requestBuilder.header("Range", it) }

            val call = client.newCall(requestBuilder.build())
            activeCalls.add(call)
            var response: Response? = null
            try {
                val result = call.execute()
                response = result
                if (result.code in REDIRECT_CODES) {
                    val location = result.header("Location")
                        ?: return blocked(result.code, "Redirect did not include a destination.")
                    current = current.resolve(location)?.toString()?.let(ResearchUrlPolicy::safeUrl)
                        ?: return blocked(403, "Redirect target rejected by research network policy.")
                    if (request.isForMainFrame && !sameOrigin(current, initialPage)) {
                        return blocked(403, "Cross-origin top-level redirect is blocked.")
                    }
                    return@repeat
                }

                val body = result.body
                val bytes = if (body == null) {
                    ByteArray(0)
                } else {
                    if (body.contentLength() > MAX_RESOURCE_BYTES.toLong()) {
                        return blocked(413, "Resource exceeded the research size limit.")
                    }
                    body.byteStream().use { readBounded(it, MAX_RESOURCE_BYTES) }
                }

                val newTotal = totalBytes.addAndGet(bytes.size.toLong())
                if (newTotal > MAX_TOTAL_RESOURCE_BYTES) {
                    return blocked(413, "Page exceeded the total research resource budget.")
                }

                val mediaType = body?.contentType()
                val mimeType = mediaType?.let { it.type + "/" + it.subtype } ?: "application/octet-stream"
                val encoding = mediaType?.charset(StandardCharsets.UTF_8)?.name() ?: "utf-8"
                val headers = result.headers.names()
                    .filter { it.lowercase() in SAFE_RESPONSE_HEADERS }
                    .associateWith { name -> result.header(name).orEmpty() }

                return WebResourceResponse(
                    mimeType,
                    encoding,
                    result.code,
                    result.message.ifBlank { "OK" }.take(MAX_REASON_CHARS),
                    headers,
                    ByteArrayInputStream(bytes),
                )
            } catch (_: IOException) {
                return blocked(502, "Resource fetch failed.")
            } catch (_: IllegalArgumentException) {
                return blocked(502, "Resource response was invalid.")
            } finally {
                response?.close()
                activeCalls.remove(call)
            }
        }

        return blocked(508, "Too many resource redirects.")
    }

    private fun robotsFor(url: HttpUrl, activeCalls: MutableSet<Call>): ResearchRobotsRules {
        val origin = url.scheme + "://" + url.host
        val now = System.currentTimeMillis()
        robotsCache[origin]?.takeIf { now < it.expiresAt }?.let { return it.rules }

        var current = (origin + "/robots.txt").toHttpUrlOrNull()
            ?: return ResearchRobotsRules.denyAll()
        repeat(MAX_ROBOTS_REDIRECTS + 1) {
            current = ResearchUrlPolicy.safeUrl(current.toString())
                ?: return ResearchRobotsRules.denyAll()

            val call = client.newCall(
                Request.Builder()
                    .url(current)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/plain,*/*;q=0.1")
                    .build(),
            )
            activeCalls.add(call)
            var response: Response? = null
            try {
                val result = call.execute()
                response = result
                if (result.code in REDIRECT_CODES) {
                    val location = result.header("Location") ?: return ResearchRobotsRules.denyAll()
                    current = current.resolve(location)?.toString()?.let(ResearchUrlPolicy::safeUrl)
                        ?: return ResearchRobotsRules.denyAll()
                    return@repeat
                }

                val rules = when {
                    result.code == 404 -> ResearchRobotsPolicy.parse("User-agent: *\n")
                    result.code in 200..299 -> {
                        val body = result.body ?: return ResearchRobotsRules.denyAll()
                        if (body.contentLength() > MAX_ROBOTS_BYTES.toLong()) return ResearchRobotsRules.denyAll()
                        val text = String(body.byteStream().use { readBounded(it, MAX_ROBOTS_BYTES) }, StandardCharsets.UTF_8)
                        ResearchRobotsPolicy.parse(text)
                    }
                    else -> ResearchRobotsRules.denyAll()
                }
                val ttl = if (rules.denyAll) FAILED_ROBOTS_TTL_MS else ROBOTS_TTL_MS
                robotsCache[origin] = CachedRobots(rules, now + ttl)
                return rules
            } catch (_: IOException) {
                val denied = ResearchRobotsRules.denyAll()
                robotsCache[origin] = CachedRobots(denied, now + FAILED_ROBOTS_TTL_MS)
                return denied
            } finally {
                response?.close()
                activeCalls.remove(call)
            }
        }

        val denied = ResearchRobotsRules.denyAll()
        robotsCache[origin] = CachedRobots(denied, now + FAILED_ROBOTS_TTL_MS)
        return denied
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, INITIAL_BUFFER_BYTES))
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                total++
                if (total > maxBytes) throw IOException("Response exceeded the byte limit.")
                output.write(single)
                continue
            }
            total += count
            if (total > maxBytes) throw IOException("Response exceeded the byte limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun blocked(status: Int, reason: String) = WebResourceResponse(
        "text/plain",
        "utf-8",
        status.coerceIn(400, 599),
        reason.take(MAX_REASON_CHARS),
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port

    private fun pathAndQuery(url: HttpUrl): String =
        url.encodedPath + url.encodedQuery?.let { "?$it" }.orEmpty()

    companion object {
        private const val USER_AGENT = "InfernoResearch/1.0 (Android; isolated JavaScript page reader)"
        private const val MAX_RESOURCE_REQUESTS = 96
        private const val MAX_RESOURCE_BYTES = 2_000_000
        private const val MAX_TOTAL_RESOURCE_BYTES = 8_000_000L
        private const val MAX_ROBOTS_BYTES = 96_000
        private const val MAX_REDIRECTS = 4
        private const val MAX_ROBOTS_REDIRECTS = 3
        private const val ROBOTS_TTL_MS = 24 * 60 * 60 * 1_000L
        private const val FAILED_ROBOTS_TTL_MS = 5 * 60 * 1_000L
        private const val INITIAL_BUFFER_BYTES = 32 * 1024
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val MAX_HEADER_VALUE_CHARS = 2_048
        private const val MAX_REASON_CHARS = 120
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val FORWARDED_REQUEST_HEADERS = setOf(
            "accept",
            "accept-language",
            "origin",
            "referer",
            "range",
        )
        private val SAFE_RESPONSE_HEADERS = setOf(
            "access-control-allow-origin",
            "access-control-allow-methods",
            "access-control-allow-headers",
            "access-control-expose-headers",
            "cache-control",
            "content-range",
            "content-security-policy",
            "etag",
            "expires",
            "last-modified",
            "retry-after",
            "vary",
            "x-content-type-options",
        )
    }
}
