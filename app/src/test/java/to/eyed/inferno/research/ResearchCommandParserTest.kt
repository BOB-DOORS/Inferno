package to.eyed.inferno.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchCommandParserTest {
    @Test
    fun plainTextIsReleasedOnTheFirstFragment() {
        val parser = ResearchCommandParser()
        assertEquals("hi", parser.accept("hi"))
        assertTrue(parser.calls.isEmpty())
    }

    @Test
    fun possibleLessThanPrefixIsHeldOnlyUntilItIsKnownToBeText() {
        val parser = ResearchCommandParser()
        assertEquals("", parser.accept("<"))
        assertEquals("<hello", parser.accept("hello"))
        assertEquals("", parser.finish())
        assertTrue(parser.calls.isEmpty())
    }

    @Test
    fun oneSearchBlockIsParsedWithoutDisplayingProtocolText() {
        val parser = ResearchCommandParser()
        val output = parser.accept(
            """<search>{"tool":"search_web","arguments":{"query":"first focused query"}}</search>""",
        )

        assertEquals("", output)
        assertEquals(1, parser.calls.size)
        assertEquals("search_web", parser.calls.single().name)
        assertEquals("first focused query", parser.calls.single().string("query"))
        assertEquals("", parser.finish())
    }

    @Test
    fun multipleQueriesAreCollectedFromOneGeneration() {
        val parser = ResearchCommandParser()
        val fragments = listOf(
            "<se",
            """arch>{"tool":"search_web","arguments":{"query":"Qwen mobile performance"}}</search>""",
            " ",
            """<search>{"tool":"search_news","arguments":{"query":"Qwen release","freshness":"week"}}</search>""",
            """<search>{"query":"Android WebView JavaScript rendering"}</search>""",
        )
        val visible = fragments.joinToString("") { parser.accept(it) } + parser.finish()

        assertEquals("", visible)
        assertEquals(3, parser.calls.size)
        assertEquals(listOf("search_web", "search_news", "search_web"), parser.calls.map { it.name })
        assertEquals(
            listOf("Qwen mobile performance", "Qwen release", "Android WebView JavaScript rendering"),
            parser.calls.map { it.string("query") },
        )
    }

    @Test
    fun completeJsonIsAcceptedWhenSmallModelOmitsClosingSearchTag() {
        val parser = ResearchCommandParser()
        parser.accept("""<search>{"tool":"search_web","arguments":{"query":"missing close tag"}}""")

        assertEquals("", parser.finish())
        assertEquals("missing close tag", parser.calls.single().string("query"))
    }

    @Test
    fun singleBracketLegacyMarkerUsesCurrentUserQueryAndIsNotDisplayed() {
        val parser = ResearchCommandParser("what is qwen 3.5?")
        assertEquals("", parser.accept("[INFERNO_TOOL:search_web]"))

        assertEquals(1, parser.calls.size)
        assertEquals("what is qwen 3.5?", parser.calls.single().string("query"))
        assertEquals("", parser.finish())
    }

    @Test
    fun doubleBracketLegacyEnvelopeIsStillRecognized() {
        val parser = ResearchCommandParser()
        val legacy = """[[INFERNO_TOOL:search_web]]{"arguments":{"query":"legacy query"}}[[/INFERNO_TOOL]]"""
        val visible = legacy.chunked(2).joinToString("") { parser.accept(it) } + parser.finish()

        assertEquals("", visible)
        assertEquals("legacy query", parser.calls.single().string("query"))
    }

    @Test
    fun tooManyCommandsAreCapped() {
        val parser = ResearchCommandParser(maxCommands = 2)
        val block = """<search>{"query":"query"}</search>"""
        repeat(5) { parser.accept(block) }

        assertEquals(2, parser.calls.size)
    }

    @Test
    fun malformedTagIsRestoredInsteadOfSilentlyDropped() {
        val parser = ResearchCommandParser()
        val malformed = """<search>{"tool":"search_web","arguments":{}}</search>"""
        val visible = parser.accept(malformed) + parser.finish()

        // Empty arguments are valid structurally, but the default search tool has no usable query.
        assertEquals(1, parser.calls.size)
        assertEquals("", visible)
    }
}
