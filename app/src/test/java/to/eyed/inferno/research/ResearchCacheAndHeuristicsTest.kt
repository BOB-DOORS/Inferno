package to.eyed.inferno.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class ResearchCacheAndHeuristicsTest {
    @Test fun compressedPersistentPayloadRoundTripsUnicodeAndMarkup() {
        val original = "<main>Olá, 世界 👋</main>\n".repeat(800)
        assertEquals(original, ResearchCacheCodec.decode(ResearchCacheCodec.encode(original)))
    }

    @Test fun javascriptShellIsDetected() {
        val html = """<html><head><script src="/_next/static/chunks/app.js"></script></head><body><div id="__next"></div></body></html>"""
        assertTrue(ResearchPageHeuristics.shouldRenderJavascript(html, ""))
    }

    @Test fun contentRichStaticPageDoesNotPayForAWebView() {
        val html = """<html><body><main><p>Static content</p><script src="/bundle.js"></script></main></body></html>"""
        val text = "Relevant article text ".repeat(300)
        assertFalse(ResearchPageHeuristics.shouldRenderJavascript(html, text))
    }

    @Test fun emptyStaticPageWithScriptsFallsBackToRenderer() {
        assertTrue(ResearchPageHeuristics.shouldRenderJavascript("<script>boot()</script><script>mount()</script>", ""))
    }

    @Test fun compressedPayloadCannotExceedDecodeLimit() {
        val payload = ResearchCacheCodec.encode("x".repeat(4_096))
        try {
            ResearchCacheCodec.decode(payload, 100)
            throw AssertionError("Expected bounded decompression to fail")
        } catch (_: IOException) {
            // Expected: malformed or oversized cache content is discarded.
        }
    }

    @Test fun javascriptFrameworkShellsAreDetectedWithoutRenderingRichPages() {
        assertTrue(
            ResearchPageHeuristics.shouldRenderJavascript(
                """<div data-v-app></div><script type="module" src="/assets/index-a1b2.js"></script>""",
                "",
            ),
        )
        assertTrue(
            ResearchPageHeuristics.shouldRenderJavascript(
                """<script type="module" src="/_app/immutable/entry/start.js"></script>""",
                "",
            ),
        )
        assertTrue(
            ResearchPageHeuristics.shouldRenderJavascript(
                """<app-root ng-version="18"></app-root>""",
                "",
            ),
        )
        assertFalse(
            ResearchPageHeuristics.shouldRenderJavascript(
                """<script src="/analytics.js"></script><main>Small static page</main>""",
                "Small static page",
            ),
        )
    }

    @Test fun publicAddressClassifierRejectsReservedAndPrivateRanges() {
        val blocked = listOf(
            "0.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.0.0.1",
            "192.0.2.1",
            "192.168.1.1",
            "198.19.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "2001:db8::1",
            "fc00::1",
            "fe80::1",
            "2002::1",
        )
        blocked.forEach { address ->
            assertFalse("$address must not be considered public", ResearchUrlPolicy.isPublicAddress(InetAddress.getByName(address)))
        }
    }

    @Test fun globallyRoutableAddressesRemainAllowed() {
        assertTrue(ResearchUrlPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(ResearchUrlPolicy.isPublicAddress(InetAddress.getByName("192.0.1.1")))
        assertTrue(ResearchUrlPolicy.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun unsupportedSchemesAndLoopbackUrlsAreRejected() {
        assertFalse(ResearchUrlPolicy.isSafePublicUrl("file:///etc/passwd"))
        assertFalse(ResearchUrlPolicy.isSafePublicUrl("http://127.0.0.1/"))
        assertFalse(ResearchUrlPolicy.isSafePublicUrl("http://localhost/"))
        assertFalse(ResearchUrlPolicy.isSafePublicUrl("ftp://example.com/resource"))
        assertFalse(ResearchUrlPolicy.isSafePublicUrl("http://example.com/"))
        assertFalse(ResearchUrlPolicy.isSafePublicUrl("https://example.com:8443/"))
    }
}
