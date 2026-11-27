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
    fun toolMarkerCanBeSplitAcrossArbitraryTokenBoundaries() {
        val parser = ResearchTokenParser()
        val output = listOf(
            "[[INFER",
            "NO_TOOL:search_",
            "web]]",
            """{"arguments":{"query":"Android compiler"}}""",
            "[[/INFERNO_",
            "TOOL]]",
        ).map(parser::accept)

        assertTrue(output.dropLast(1).all { it == ResearchTokenUpdate.Buffered })
        val last = output.last()
        assertTrue(last is ResearchTokenUpdate.Tool)
        assertEquals("Android compiler", (last as ResearchTokenUpdate.Tool).call.string("query"))
    }

    @Test
    fun malformedToolEnvelopeIsRestoredAsVisibleText() {
        val parser = ResearchTokenParser()
        val raw = """[[INFERNO_TOOL:search_web]]{"arguments":{"query":}}[[/INFERNO_TOOL]]"""
        val visible = raw.chunked(3).mapNotNull { part ->
            (parser.accept(part) as? ResearchTokenUpdate.Visible)?.text
        }.joinToString("") + parser.finish()

        assertEquals(raw, visible)
    }

    @Test
    fun oversizedToolEnvelopeIsNeverDispatched() {
        val parser = ResearchTokenParser()
        parser.accept(ResearchTokenParser.START + "search_web]]")
        val result = parser.accept("x".repeat(ResearchTokenParser.MAX_TOOL_PAYLOAD + 1))
        assertTrue(result is ResearchTokenUpdate.Visible)
    }
}
