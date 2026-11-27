package to.eyed.inferno.research

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared SSRF guard for native HTTP and WebView requests.
 *
 * The same short-lived, validated DNS answers are also returned to OkHttp's Dns implementation. This prevents
 * a hostname from passing validation with a public answer and then resolving to a private address at connect time.
 */
internal object ResearchUrlPolicy {
    private data class HostCheck(val addresses: List<InetAddress>, val checkedAt: Long)

    private val hostCache = ConcurrentHashMap<String, HostCheck>()
    private val cacheInsertions = AtomicInteger()

    fun isSafePublicUrl(raw: String): Boolean = safeUrl(raw) != null

    fun safeUrl(raw: String): HttpUrl? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.port != 443) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null

        val host = url.host.lowercase(Locale.ROOT).trimEnd('.')
        if (!isAllowedHostname(host)) return null
        if (!ResearchSafetyGate.allows(host + url.encodedPath + url.encodedQuery.orEmpty())) return null

        return url.takeIf { runCatching { lookupPublicAddresses(host) }.isSuccess }
    }

    /**
     * OkHttp DNS hook. Never returns a mixed public/private answer set: one unsafe address rejects the hostname.
     */
    fun lookupPublicAddresses(hostname: String): List<InetAddress> {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        if (!isAllowedHostname(host)) throw UnknownHostException("Research host is not public.")

        val now = System.currentTimeMillis()
        hostCache[host]?.takeIf { now - it.checkedAt in 0..DNS_CACHE_MS }?.let { return it.addresses }

        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (error: Exception) {
            throw UnknownHostException("Could not resolve research host: $host").apply { initCause(error) }
        }
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            throw UnknownHostException("Research host resolved to a non-public address.")
        }

        hostCache[host] = HostCheck(addresses, now)
        if (cacheInsertions.incrementAndGet() % CACHE_MAINTENANCE_INTERVAL == 0) {
            hostCache.entries.removeIf { now - it.value.checkedAt !in 0..DNS_CACHE_MS }
            // Research can touch arbitrary domains. Bound retained DNS state on low-memory devices.
            if (hostCache.size > MAX_CACHED_HOSTS) hostCache.clear()
        }
        return addresses
    }

    private fun isAllowedHostname(host: String): Boolean =
        host.isNotBlank() &&
            host.length <= MAX_HOST_CHARS &&
            host != "localhost" &&
            !host.endsWith(".localhost") &&
            !host.endsWith(".local") &&
            !host.endsWith(".internal") &&
            !host.endsWith(".onion") &&
            !host.endsWith(".test") &&
            !host.endsWith(".invalid") &&
            !host.endsWith(".example")

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false

        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> {
                val a = bytes[0]
                val b = bytes[1]
                val c = bytes[2]
                !(
                    a == 0 || a == 10 || a == 127 || a >= 224 ||
                        (a == 100 && b in 64..127) ||
                        (a == 169 && b == 254) ||
                        (a == 172 && b in 16..31) ||
                        (a == 192 && ((b == 0 && (c == 0 || c == 2)) || b == 168)) ||
                        (a == 192 && b == 88 && c == 99) ||
                        (a == 198 && b in 18..19) ||
                        (a == 198 && b == 51 && c == 100) ||
                        (a == 203 && b == 0 && c == 113)
                    )
            }
            is Inet6Address -> {
                val first = bytes[0]
                val second = bytes[1]
                val globalUnicast = (first and 0xe0) == 0x20
                val documentation = first == 0x20 && second == 0x01 &&
                    bytes[2] == 0x0d && bytes[3] == 0xb8
                val teredo = first == 0x20 && second == 0x01 && bytes[2] == 0x00 && bytes[3] == 0x00
                val sixToFour = first == 0x20 && second == 0x02
                globalUnicast && !documentation && !teredo && !sixToFour
            }
            else -> false
        }
    }

    private const val DNS_CACHE_MS = 60_000L
    private const val MAX_CACHED_HOSTS = 512
    private const val CACHE_MAINTENANCE_INTERVAL = 16
    private const val MAX_HOST_CHARS = 253
}
