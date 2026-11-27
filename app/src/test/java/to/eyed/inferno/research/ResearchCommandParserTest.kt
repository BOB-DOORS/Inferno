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
    fun oneSearchBlockCanCarryMultipleIndependentQueries() {
        val parser = ResearchCommandParser()
        parser.accept(
            """<search>{"tool":"search_web","queries":["first query","second query","third query"]}</search>""",
        )

        assertEquals(3, parser.calls.size)
        assertEquals(listOf("first query", "second query", "third query"), parser.calls.map { it.string("query") })
    }

    @Test
    fun leadingWhitespaceBeforeSearchTagDoesNotLeakIntoChat() {
        val parser = ResearchCommandParser()
        val visible = parser.accept("\n  " + """<search>{"query":"query after whitespace"}</search>""") + parser.finish()

        assertEquals("", visible)
        assertEquals("query after whitespace", parser.calls.single().string("query"))
    }

    @Test
    fun queryArrayCanBeNestedInsideArguments() {
        val parser = ResearchCommandParser()
        parser.accept(
            """<search>{"tool":"search_web","arguments":{"queries":["nested one","nested two"],"freshness":"week"}}</search>""",
        )

        assertEquals(listOf("nested one", "nested two"), parser.calls.map { it.string("query") })
        assertTrue(parser.calls.all { it.string("freshness") == "week" })
    }

    @Test
    fun invalidToolBlockRequestsDirectAnswerFallbackWithoutLeakingSyntax() {
        val parser = ResearchCommandParser()
        val visible = parser.accept("""<search>{"tool":"nonexistent_tool","arguments":{}}</search>""") + parser.finish()

        assertEquals("", visible)
        assertTrue(parser.calls.isEmpty())
        assertTrue(parser.needsTextFallback)
    }

    @Test
    fun completeJsonIsAcceptedWhenSmallModelOmitsClosingSearchTag() {
        val parser = ResearchCommandParser()
        parser.accept("""<search>{"tool":"search_web","arguments":{"query":"missing close tag"}}""")

        assertEquals("", parser.finish())
        assertEquals("missing close tag", parser.calls.single().string("query"))
    }

    @Test
    fun argumentFreeLegacyMarkerFallsBackToTextInsteadOfSearchingOrDisplaying() {
        val parser = ResearchCommandParser()
        assertEquals("", parser.accept("[INFERNO_TOOL:search_web]"))

        assertTrue(parser.calls.isEmpty())
        assertTrue(parser.needsTextFallback)
        assertEquals("", parser.finish())
    }

    @Test
    fun truncatedLegacyMarkerAlsoRequestsTextFallback() {
        val parser = ResearchCommandParser()
        assertEquals("", parser.accept("[[INFERNO_TOOL:search_web"))
        assertEquals("", parser.finish())
        assertTrue(parser.needsTextFallback)
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
    fun emptySearchArgumentsAreCapturedWithoutLeakingTheTag() {
        val parser = ResearchCommandParser()
        val malformed = """<search>{"tool":"search_web","arguments":{}}</search>"""
        val visible = parser.accept(malformed) + parser.finish()

        // The engine rejects a missing query; command syntax itself is never shown in chat.
        assertEquals(1, parser.calls.size)
        assertEquals("", visible)
    }
}
