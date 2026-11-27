package to.eyed.inferno.research

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Shared SSRF guard used by both OkHttp and the JavaScript renderer. */
internal object ResearchUrlPolicy {
    private data class HostCheck(val allowed: Boolean, val checkedAt: Long)
    private val hostCache = ConcurrentHashMap<String, HostCheck>()

    fun isSafePublicUrl(raw: String): Boolean = safeUrl(raw) != null

    fun safeUrl(raw: String): HttpUrl? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (!((url.scheme == "https" && url.port == 443) || (url.scheme == "http" && url.port == 80))) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val host = url.host.lowercase(Locale.ROOT).trimEnd('.')
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".onion") ||
            host.endsWith(".test") || host.endsWith(".invalid") || host.endsWith(".example")) return null
        if (!ResearchSafetyGate.allows(host + url.encodedPath + url.encodedQuery.orEmpty())) return null

        val now = System.currentTimeMillis()
        val allowed = hostCache[host]?.takeIf { now - it.checkedAt in 0..HOST_CACHE_MS }?.allowed
            ?: resolvePublicHost(host).also { hostCache[host] = HostCheck(it, now) }
        return url.takeIf { allowed }
    }

    private fun resolvePublicHost(host: String): Boolean = runCatching {
        val addresses = InetAddress.getAllByName(host)
        addresses.isNotEmpty() && addresses.all(::isPublicAddress)
    }.getOrDefault(false)

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> {
                val a = bytes[0]
                val b = bytes[1]
                val c = bytes[2]
                !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && b in 64..127) ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && (b == 168 || (b == 0 && c == 0) || (b == 0 && c == 2))) ||
                    (a == 198 && b in 18..19) ||
                    (a == 198 && b == 51 && c == 100) ||
                    (a == 203 && b == 0 && c == 113))
            }
            is Inet6Address -> {
                val first = bytes[0]
                val second = bytes[1]
                val uniqueLocal = (first and 0xfe) == 0xfc
                val linkLocal = first == 0xfe && (second and 0xc0) == 0x80
                val multicast = first == 0xff
                !(uniqueLocal || linkLocal || multicast)
            }
            else -> false
        }
    }

    private const val HOST_CACHE_MS = 5 * 60 * 1_000L
}
