package to.eyed.inferno.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchTokenParserTest {
    @Test fun ordinaryTextPassesThroughImmediately() {
        val parser = ResearchTokenParser()
        val result = parser.accept("A normal answer.")
        assertTrue(result is ResearchTokenUpdate.Visible)
        assertEquals("A normal answer.", (result as ResearchTokenUpdate.Visible).text)
    }

    @Test fun validToolEnvelopeIsParsed() {
        val parser = ResearchTokenParser()
        assertTrue(parser.accept("[[INFERNO_TOOL:search_web]]") is ResearchTokenUpdate.Buffered)
        val result = parser.accept("""{"arguments":{"query":"best gaming PCs"}}[[/INFERNO_TOOL]]""")
        assertTrue(result is ResearchTokenUpdate.Tool)
        assertEquals("best gaming PCs", (result as ResearchTokenUpdate.Tool).call.string("query"))
    }

    @Test fun invalidEnvelopeIsRestoredAsVisibleText() {
        val parser = ResearchTokenParser()
        parser.accept("[[INFERNO_TOOL:search_web]]")
        val result = parser.accept("not-json[[/INFERNO_TOOL]]")
        assertTrue(result is ResearchTokenUpdate.Visible)
        assertTrue((result as ResearchTokenUpdate.Visible).text.contains("not-json"))
    }

    @Test fun incompleteMarkerIsNeverDropped() {
        val parser = ResearchTokenParser()
        parser.accept("[[INFERNO")
        assertEquals("[[INFERNO", parser.finish())
    }
}
