package to.eyed.inferno.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(ResearchPageHeuristics.shouldRenderJavascript("<script>boot()</script>", ""))
    }

    @Test fun unsupportedSchemesAndLoopbackUrlsAreRejected() {
        assertFalse(ResearchUrlPolicy.isSafePublicUrl("file:///etc/passwd"))
        assertFalse(ResearchUrlPolicy.isSafePublicUrl("http://127.0.0.1/"))
        assertFalse(ResearchUrlPolicy.isSafePublicUrl("http://localhost/"))
        assertFalse(ResearchUrlPolicy.isSafePublicUrl("ftp://example.com/resource"))
    }
}
