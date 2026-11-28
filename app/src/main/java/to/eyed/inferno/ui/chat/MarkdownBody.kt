package to.eyed.inferno.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swmansion.enriched.markdown.compose.CodeBlockLayout
import com.swmansion.enriched.markdown.compose.EnrichedMarkdownText
import com.swmansion.enriched.markdown.compose.MarkdownOptions
import com.swmansion.enriched.markdown.compose.markdownStyle
import com.swmansion.enriched.markdown.math.LatexMathPlugin
import com.swmansion.enriched.markdown.math.compose.inlineMath
import com.swmansion.enriched.markdown.math.compose.math
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.whiteA

/**
 * Native Markdown renderer with RaTeX-backed LaTeX. Stable chunks remain composed while the streaming tail
 * changes, avoiding whole-answer reparses. Scrollable code blocks provide their own copy controls.
 */
@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    val chunks = remember(text) { MarkdownChunker.chunks(text) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunks.forEachIndexed { index, chunk -> key(index) { MarkdownChunkView(chunk) } }
    }
}

@Composable
private fun MarkdownChunkView(chunk: String) {
    val uriHandler = LocalUriHandler.current
    val style = remember {
        markdownStyle {
            paragraph {
                fontSize = 16.sp
                lineHeight = 24.sp
                fontFamily = Typography.bodyLarge.fontFamily
                color = Ink.I100
                marginBottom = 10.dp
            }
            h1 {
                fontSize = 22.sp
                lineHeight = 28.sp
                fontWeight = FontWeight.SemiBold
                color = Ink.White
                marginBottom = 8.dp
            }
            h2 {
                fontSize = 19.sp
                lineHeight = 25.sp
                fontWeight = FontWeight.SemiBold
                color = Ink.White
                marginBottom = 8.dp
            }
            h3 {
                fontSize = 16.sp
                lineHeight = 22.sp
                fontWeight = FontWeight.SemiBold
                color = Ink.White
                marginBottom = 6.dp
            }
            h4 {
                fontSize = 15.sp
                lineHeight = 21.sp
                fontWeight = FontWeight.SemiBold
                color = Ink.I100
            }
            h5 {
                color = Ink.White
                fontWeight = FontWeight.SemiBold
            }
            h6 {
                color = Ink.I300
                fontWeight = FontWeight.Medium
            }
            list {
                fontSize = 16.sp
                lineHeight = 23.sp
                color = Ink.I100
                bulletColor = Ink.I300
                markerColor = Ink.I300
                marginBottom = 8.dp
            }
            taskList {
                checkedColor = Ink.White
                borderColor = Ink.I500
                checkmarkColor = Ink.Pitch
                checkedTextColor = Ink.I500
            }
            blockquote {
                fontSize = 15.sp
                lineHeight = 22.sp
                color = Ink.I300
                borderColor = Ink.I500
                backgroundColor = Ink.I900
                cornerRadius = Radii.row
                padding = 12.dp
                marginBottom = 10.dp
            }
            codeBlock {
                fontSize = 13.sp
                lineHeight = 20.sp
                color = Ink.I100
                backgroundColor = Ink.I900
                borderColor = whiteA(0.08f)
                borderWidth = 1.dp
                cornerRadius = Radii.row
                padding = 14.dp
                marginBottom = 12.dp
            }
            code {
                color = Ink.White
                backgroundColor = whiteA(0.10f)
                borderColor = whiteA(0.08f)
            }
            link {
                color = Ink.White
                textDecoration = TextDecoration.Underline
            }
            strong { color = Ink.White }
            table {
                fontSize = 14.sp
                lineHeight = 20.sp
                color = Ink.I100
                headerTextColor = Ink.White
                headerBackgroundColor = whiteA(0.07f)
                rowEvenBackgroundColor = Ink.I900
                rowOddBackgroundColor = Ink.Pitch
                borderColor = whiteA(0.12f)
                cornerRadius = Radii.row
                cellPaddingHorizontal = 10.dp
                cellPaddingVertical = 8.dp
                marginBottom = 12.dp
            }
            image {
                height = 220.dp
                cornerRadius = Radii.row
                marginBottom = 10.dp
            }
            thematicBreak {
                color = whiteA(0.12f)
                marginTop = 14.dp
                marginBottom = 14.dp
            }
            math {
                fontSize = 20.sp
                color = Ink.I100
                backgroundColor = Ink.I900
                padding = 12.dp
                marginBottom = 10.dp
            }
            inlineMath { color = Ink.I100 }
        }
    }

    EnrichedMarkdownText(
        markdown = chunk,
        modifier = Modifier.fillMaxWidth(),
        style = style,
        options = MarkdownOptions(
            admonitions = true,
            superscript = true,
            subscript = true,
            highlight = true,
        ),
        selectable = true,
        codeBlockLayout = CodeBlockLayout.Scrollable,
        plugins = listOf(LatexMathPlugin),
        onLinkClick = { url -> runCatching { uriHandler.openUri(url) } },
    )
}

/**
 * Splits markdown into independently renderable chunks on blank lines, keeping fenced code blocks
 * (even unterminated, mid-stream) and list runs intact so numbering and fences never break across
 * chunks. Pure Kotlin: covered by MarkdownChunkerTest.
 */
object MarkdownChunker {
    private val fenceStart = Regex("^(`{3,}|~{3,})(.*)$")

    fun chunks(source: String): List<String> {
        val chunks = mutableListOf<String>()
        val current = mutableListOf<String>()
        // CommonMark fences: the opener is 3+ of one char (a backtick opener's info string may not contain a
        // backtick, so "```ls``` lists files" is inline code); the closer is the same char, at least as long, alone.
        // A plain toggle would flip on such lines and glue the rest of the answer into one re-parsed chunk.
        var fenceChar: Char? = null
        var fenceLen = 0
        var mathBlock = false
        val lines = source.split("\n")

        fun flush() {
            if (current.any { it.isNotBlank() }) chunks.add(current.joinToString("\n"))
            current.clear()
        }

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            val m = fenceStart.matchEntire(trimmed)
            if (m != null) {
                val marker = m.groupValues[1]
                val rest = m.groupValues[2]
                if (fenceChar == null) {
                    if (marker[0] == '~' || !rest.contains('`')) {
                        fenceChar = marker[0]; fenceLen = marker.length
                        current.add(line); continue
                    }
                } else if (marker[0] == fenceChar && marker.length >= fenceLen && rest.isBlank()) {
                    fenceChar = null; fenceLen = 0
                    current.add(line); continue
                }
                // Otherwise ordinary content (inside a fence, or inline code text).
            }
            if (fenceChar == null && trimmed == "\$\$") {
                mathBlock = !mathBlock
                current.add(line)
                continue
            }
            if (trimmed.isEmpty() && fenceChar == null && !mathBlock) {
                val prevIsItem = current.lastOrNull()?.let(::isListItem) ?: false
                var nextIsItem = false
                for (i in (index + 1) until lines.size) {
                    if (lines[i].isNotBlank()) { nextIsItem = isListItem(lines[i]); break }
                }
                if (prevIsItem && nextIsItem) current.add(line) else flush()
                continue
            }
            current.add(line)
        }
        flush()
        return chunks
    }

    /** "- ", "* ", "+ ", "> " or "1." / "12)" style markers (an indented continuation counts too). */
    fun isListItem(line: String): Boolean {
        val t = line.trim()
        if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") || t.startsWith("> ")) return true
        if (line.startsWith("  ") && t.isNotEmpty()) return true
        val mark = t.indexOfFirst { it == '.' || it == ')' }
        if (mark <= 0 || mark > 3) return false
        return t.substring(0, mark).all { it.isDigit() }
    }
}
