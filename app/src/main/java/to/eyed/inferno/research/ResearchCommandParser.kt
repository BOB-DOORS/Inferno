package to.eyed.inferno.research

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

/**
 * Parses a command-only first generation pass without adding a classifier pass.
 *
 * Normal text is streamed immediately. Only a possible leading '<' / '[' protocol prefix is buffered;
 * once it ceases to match, the buffered characters are emitted and the parser becomes a zero-cost passthrough.
 * A tool pass may contain several adjacent command blocks. Text after the first valid block is suppressed so a
 * model cannot accidentally leak raw tool syntax into the chat bubble.
 */
internal class ResearchCommandParser(
    private val fallbackQuery: String = "",
    private val maxCommands: Int = MAX_COMMANDS,
) {
    private enum class Mode {
        PROBE,
        TAG_BODY,
        LEGACY_SINGLE_HEADER,
        LEGACY_DOUBLE_HEADER,
        LEGACY_BODY,
        TEXT,
        AFTER_COMMAND,
        PROBE_NEXT,
        SUPPRESS,
    }

    private var mode = Mode.PROBE
    private val candidate = StringBuilder()
    private val header = StringBuilder()
    private val body = StringBuilder()
    private val commands = ArrayList<ResearchToolCall>()

    val calls: List<ResearchToolCall> get() = commands.toList()

    fun accept(fragment: String): String {
        if (mode == Mode.TEXT) return fragment
        if (mode == Mode.SUPPRESS) return ""

        val visible = StringBuilder()
        for (character in fragment) {
            when (mode) {
                Mode.PROBE, Mode.PROBE_NEXT -> acceptProbe(character, visible)
                Mode.TAG_BODY -> acceptTagBody(character, visible)
                Mode.LEGACY_SINGLE_HEADER -> acceptSingleHeader(character, visible)
                Mode.LEGACY_DOUBLE_HEADER -> acceptDoubleHeader(character, visible)
                Mode.LEGACY_BODY -> acceptLegacyBody(character, visible)
                Mode.TEXT -> visible.append(character)
                Mode.AFTER_COMMAND -> {
                    when {
                        character.isWhitespace() -> Unit
                        character == '<' || character == '[' -> {
                            candidate.setLength(0)
                            candidate.append(character)
                            mode = Mode.PROBE_NEXT
                        }
                        else -> mode = Mode.SUPPRESS
                    }
                }
                Mode.SUPPRESS -> Unit
            }
        }
        return visible.toString()
    }

    /** Returns any incomplete ordinary text that was held while checking for a command prefix. */
    fun finish(): String {
        // A few small models omit a closing tag after a complete JSON object. Parse only a complete JSON
        // object here; malformed or truncated command text is restored as ordinary visible text.
        when (mode) {
            Mode.TAG_BODY -> parseTagBody(body.toString())
            Mode.LEGACY_BODY -> parseLegacyBody(body.toString())
            else -> Unit
        }

        if (commands.isNotEmpty()) return ""
        return when (mode) {
            Mode.PROBE, Mode.PROBE_NEXT -> candidate.toString()
            Mode.TAG_BODY -> "<search>" + body
            Mode.LEGACY_SINGLE_HEADER -> LEGACY_SINGLE_START + header
            Mode.LEGACY_DOUBLE_HEADER -> LEGACY_DOUBLE_START + header
            Mode.LEGACY_BODY -> LEGACY_DOUBLE_START + header + "]]" + body
            else -> ""
        }
    }

    private fun acceptProbe(character: Char, visible: StringBuilder) {
        if (candidate.isEmpty()) {
            if (character == '<' || character == '[') {
                candidate.append(character)
            } else {
                mode = Mode.TEXT
                visible.append(character)
            }
            return
        }

        candidate.append(character)
        when {
            candidate.toString() == TAG_START -> {
                candidate.setLength(0)
                body.setLength(0)
                mode = Mode.TAG_BODY
            }
            candidate.toString() == LEGACY_SINGLE_START -> {
                candidate.setLength(0)
                header.setLength(0)
                mode = Mode.LEGACY_SINGLE_HEADER
            }
            candidate.toString() == LEGACY_DOUBLE_START -> {
                candidate.setLength(0)
                header.setLength(0)
                mode = Mode.LEGACY_DOUBLE_HEADER
            }
            COMMAND_PREFIXES.any { it.startsWith(candidate.toString()) } -> Unit
            else -> {
                if (mode == Mode.PROBE_NEXT) {
                    // After a valid command batch, ignore any non-command completion text.
                    candidate.setLength(0)
                    mode = Mode.SUPPRESS
                } else {
                    visible.append(candidate)
                    candidate.setLength(0)
                    mode = Mode.TEXT
                }
            }
        }
    }

    private fun acceptTagBody(character: Char, visible: StringBuilder) {
        body.append(character)
        if (body.endsWith(TAG_END)) {
            body.setLength(body.length - TAG_END.length)
            if (parseTagBody(body.toString())) {
                mode = Mode.AFTER_COMMAND
            } else if (commands.isEmpty()) {
                visible.append(TAG_START).append(body).append(TAG_END)
                mode = Mode.TEXT
            } else {
                mode = Mode.SUPPRESS
            }
            body.setLength(0)
        } else if (body.length > MAX_PAYLOAD_CHARS) {
            if (commands.isEmpty()) visible.append(TAG_START).append(body)
            body.setLength(0)
            mode = if (commands.isEmpty()) Mode.TEXT else Mode.SUPPRESS
        }
    }

    private fun acceptSingleHeader(character: Char, visible: StringBuilder) {
        if (character == ']') {
            addLegacyCall(header.toString())
            header.setLength(0)
            mode = if (commands.isEmpty()) Mode.TEXT else Mode.AFTER_COMMAND
        } else {
            header.append(character)
            if (header.length > MAX_TOOL_NAME_CHARS) {
                if (commands.isEmpty()) visible.append(LEGACY_SINGLE_START).append(header)
                header.setLength(0)
                mode = if (commands.isEmpty()) Mode.TEXT else Mode.SUPPRESS
            }
        }
    }

    private fun acceptDoubleHeader(character: Char, visible: StringBuilder) {
        header.append(character)
        if (header.endsWith("]]")) {
            header.setLength(header.length - 2)
            body.setLength(0)
            mode = Mode.LEGACY_BODY
        } else if (header.length > MAX_TOOL_NAME_CHARS) {
            if (commands.isEmpty()) visible.append(LEGACY_DOUBLE_START).append(header)
            header.setLength(0)
            mode = if (commands.isEmpty()) Mode.TEXT else Mode.SUPPRESS
        }
    }

    private fun acceptLegacyBody(character: Char, visible: StringBuilder) {
        body.append(character)
        if (body.endsWith(LEGACY_END)) {
            body.setLength(body.length - LEGACY_END.length)
            if (parseLegacyBody(body.toString())) {
                mode = Mode.AFTER_COMMAND
            } else if (commands.isEmpty()) {
                visible.append(LEGACY_DOUBLE_START).append(header).append("]]").append(body).append(LEGACY_END)
                mode = Mode.TEXT
            } else {
                mode = Mode.SUPPRESS
            }
            body.setLength(0)
            header.setLength(0)
        } else if (body.length > MAX_PAYLOAD_CHARS) {
            if (commands.isEmpty()) visible.append(LEGACY_DOUBLE_START).append(header).append("]]").append(body)
            body.setLength(0)
            mode = if (commands.isEmpty()) Mode.TEXT else Mode.SUPPRESS
        }
    }

    private fun parseTagBody(raw: String): Boolean {
        val parsed = runCatching {
            Json { isLenient = true; ignoreUnknownKeys = true }.parseToJsonElement(raw.trim()).jsonObject
        }.getOrNull()

        if (parsed == null) {
            val query = raw.trim().take(MAX_QUERY_CHARS)
            return addCall("search_web", queryArguments(query))
        }

        val tool = parsed.string("tool") ?: parsed.string("name") ?: "search_web"
        val arguments = parsed["arguments"] as? JsonObject
            ?: JsonObject(parsed.filterKeys { it != "tool" && it != "name" && it != "queries" })

        val queries = parsed["queries"] as? JsonArray
        if (tool == "search_web" && queries != null) {
            var added = false
            queries.asSequence()
                .mapNotNull { element -> runCatching { element.jsonPrimitive.contentOrNull }.getOrNull() }
                .filter(String::isNotBlank)
                .take((maxCommands - commands.size).coerceAtLeast(0))
                .forEach { query ->
                    val queryArguments = JsonObject(arguments + ("query" to JsonPrimitive(query.take(MAX_QUERY_CHARS))))
                    added = addCall(tool, queryArguments) || added
                }
            return added
        }
        return addCall(tool, arguments)
    }

    private fun parseLegacyBody(raw: String): Boolean {
        val arguments = runCatching {
            Json { isLenient = true; ignoreUnknownKeys = true }.parseToJsonElement(raw.trim()).jsonObject
        }.getOrNull() ?: return false
        val name = header.toString().trim().ifBlank { "search_web" }
        val args = arguments["arguments"] as? JsonObject ?: arguments
        return addCall(name, args)
    }

    private fun addLegacyCall(rawName: String): Boolean {
        val name = rawName.trim().removeSuffix("]]").trim()
        if (name.isBlank()) return false
        return addCall(name, queryArguments(fallbackQuery))
    }

    private fun addCall(rawName: String, arguments: JsonObject): Boolean {
        val name = rawName.trim().lowercase()
        if (name !in ALLOWED_TOOLS || commands.size >= maxCommands) return false
        if (!ResearchSafetyGate.allows(arguments.toString())) return false
        commands += ResearchToolCall(name, arguments)
        return true
    }

    private fun queryArguments(query: String): JsonObject = buildJsonObject {
        if (query.isNotBlank()) put("query", query.take(MAX_QUERY_CHARS))
    }

    private fun JsonObject.string(key: String): String? = runCatching {
        this[key]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        const val TAG_START = "<search>"
        const val TAG_END = "</search>"
        private const val LEGACY_SINGLE_START = "[INFERNO_TOOL:"
        private const val LEGACY_DOUBLE_START = "[[INFERNO_TOOL:"
        private const val LEGACY_END = "[[/INFERNO_TOOL]]"
        private const val MAX_COMMANDS = 5
        private const val MAX_PAYLOAD_CHARS = 8_000
        private const val MAX_TOOL_NAME_CHARS = 40
        private const val MAX_QUERY_CHARS = 320
        private val COMMAND_PREFIXES = listOf(TAG_START, LEGACY_SINGLE_START, LEGACY_DOUBLE_START)
        private val ALLOWED_TOOLS = setOf(
            "search_web",
            "search_news",
            "search_products",
            "open_page",
            "extract_product",
            "search_images",
            "find_in_page",
            "follow_links",
            "compare_sources",
        )
    }
}
