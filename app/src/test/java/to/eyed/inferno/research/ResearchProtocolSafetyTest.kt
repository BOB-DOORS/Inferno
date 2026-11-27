package to.eyed.inferno.research

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchProtocolSafetyTest {
    @Test
    fun toolArgumentsIgnoreUnexpectedJsonTypesInsteadOfThrowing() {
        val call = ResearchToolCall(
            "search_web",
            buildJsonObject {
                put("query", "weather forecast")
                put("domains", JsonObject(emptyMap()))
                put("freshness", JsonPrimitive(7))
            },
        )

        assertEquals("weather forecast", call.string("query"))
        assertEquals("fallback", call.string("domains", "fallback"))
        assertEquals("7", call.string("freshness"))
        assertEquals("", call.string("missing"))
    }

    @Test
    fun commandCanBeSplitAcrossArbitraryTokenBoundaries() {
        val parser = ResearchCommandParser()
        val output = listOf(
            "<sea",
            "rch>",
            """{"tool":"search_web","arguments":{"query":"Android compiler"}}""",
            "</se",
            "arch>",
        ).joinToString("") { parser.accept(it) } + parser.finish()

        assertEquals("", output)
        assertEquals("Android compiler", parser.calls.single().string("query"))
    }

    @Test
    fun unsupportedToolSyntaxIsHiddenAndRequestsAPlainAnswer() {
        val parser = ResearchCommandParser()
        val malformed = """<search>{"tool":"run_shell","arguments":{"command":"unexpected"}}</search>"""
        val visible = malformed.chunked(3).joinToString("") { parser.accept(it) } + parser.finish()

        assertEquals("", visible)
        assertTrue(parser.calls.isEmpty())
        assertTrue(parser.needsTextFallback)
    }

    @Test
    fun oversizedToolEnvelopeIsNeverDispatchedOrDisplayed() {
        val parser = ResearchCommandParser()
        parser.accept("<search>")
        parser.accept("x".repeat(8_001))
        assertEquals("", parser.finish())
        assertTrue(parser.calls.isEmpty())
        assertTrue(parser.needsTextFallback)
    }
}
