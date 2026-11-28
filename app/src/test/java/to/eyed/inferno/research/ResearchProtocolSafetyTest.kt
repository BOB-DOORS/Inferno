package to.eyed.inferno.research

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchProtocolSafetyTest {
    @Test
    fun imageSearchResultsAreAppRenderedAndExcludedFromSources() {
        val workspace = ResearchWorkspace()
        workspace.add(
            ResearchOutput(
                "Image search",
                "Two relevant images found.",
                images = listOf(
                    ResearchImage("https://images.example/a.jpg", "A sample picture", "https://page.example/a"),
                    ResearchImage("https://images.example/b.jpg", "Another picture", "https://page.example/b"),
                ),
                renderImages = true,
            ),
        )
        assertTrue(workspace.imageMarkdownAppendix().contains("![A sample picture](https://images.example/a.jpg)"))
        assertTrue(workspace.imageMarkdownAppendix().contains("![Another picture](https://images.example/b.jpg)"))
        assertEquals("", workspace.citationAppendix())
    }

    @Test
    fun plainAnswerToolContextIsEmptyWithoutEvidence() {
        assertEquals("", ResearchWorkspace().modelContext())
    }


    @Test
    fun followUpPromptReceivesPreviousQueriesAndEvidence() {
        val workspace = ResearchWorkspace()
        workspace.recordCall(
            ResearchToolCall(
                "search_web",
                JsonObject(mapOf("query" to JsonPrimitive("original query"))),
            ),
        )
        workspace.add(ResearchOutput("Web search", "No relevant result was found."))

        val prompt = ResearchPrompt.compose(null, workspace, toolsEnabled = true)

        assertEquals(1, workspace.toolCalls)
        assertTrue(prompt.contains("RESEARCH ACTIONS ALREADY TAKEN"))
        assertTrue(prompt.contains("search_web: original query"))
        assertTrue(prompt.contains("No relevant result was found."))
        assertTrue(prompt.contains("Never repeat an already executed query"))
    }

    @Test
    fun sourceAppendixDoesNotIncludeImageSearchOrigins() {
        val workspace = ResearchWorkspace()
        workspace.add(
            ResearchOutput(
                "Image search",
                "Found an image",
                images = listOf(ResearchImage("https://images.example/a.jpg", "picture", "https://origin.example")),
                renderImages = true,
            ),
        )
        assertEquals("", workspace.citationAppendix())
    }
}
