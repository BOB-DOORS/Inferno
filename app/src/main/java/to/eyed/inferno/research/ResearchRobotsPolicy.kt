package to.eyed.inferno.research

/** Parsed RFC-style robots rules for the one crawler identity used by this app. */
internal data class ResearchRobotsRules(
    val rules: List<ResearchRobotsRule>,
    val crawlDelayMs: Long,
    val denyAll: Boolean = false,
) {
    fun allows(pathAndQuery: String): Boolean {
        if (denyAll) return false
        val matches = rules.filter { it.expression.containsMatchIn(pathAndQuery) }
        if (matches.isEmpty()) return true

        val strongest = matches.maxOf { it.specificity }
        // RFC 9309 uses the most-specific match; Allow wins when equally specific.
        return matches.any { it.specificity == strongest && it.allow }
    }

    companion object {
        fun denyAll() = ResearchRobotsRules(emptyList(), 0L, denyAll = true)
    }
}

internal data class ResearchRobotsRule(
    val allow: Boolean,
    val specificity: Int,
    val expression: Regex,
)

internal object ResearchRobotsPolicy {
    private data class Group(
        val agents: List<String>,
        val rules: List<ResearchRobotsRule>,
        val crawlDelayMs: Long,
    )

    fun parse(text: String, userAgent: String = CRAWLER_NAME): ResearchRobotsRules {
        val groups = mutableListOf<Group>()
        val agents = mutableListOf<String>()
        val rules = mutableListOf<ResearchRobotsRule>()
        var crawlDelayMs = 0L
        var hasDirective = false

        fun finishGroup() {
            if (agents.isNotEmpty()) {
                groups += Group(agents.toList(), rules.toList(), crawlDelayMs)
            }
            agents.clear()
            rules.clear()
            crawlDelayMs = 0L
            hasDirective = false
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach

            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            when (key) {
                "user-agent" -> {
                    if (hasDirective) finishGroup()
                    if (value.isNotBlank()) agents += value
                }
                "allow", "disallow" -> {
                    if (agents.isEmpty() || value.isBlank()) return@forEach
                    hasDirective = true
                    compileRule(value, key == "allow")?.let(rules::add)
                }
                "crawl-delay" -> {
                    if (agents.isNotEmpty()) {
                        hasDirective = true
                        val parsed = value.toDoubleOrNull()
                        if (parsed != null && parsed.isFinite() && parsed >= 0.0) {
                            crawlDelayMs = maxOf(crawlDelayMs, (parsed * 1_000).toLong().coerceIn(0L, MAX_CRAWL_DELAY_MS))
                        }
                    }
                }
            }
        }
        finishGroup()

        val matchingGroups = groups.mapNotNull { group ->
            val specificity = group.agents
                .filter { it != "*" && userAgent.startsWith(it, ignoreCase = true) }
                .maxOfOrNull(String::length)
            specificity?.let { group to it }
        }
        val strongestMatch = matchingGroups.maxOfOrNull { it.second }
        val selected = if (strongestMatch != null) {
            matchingGroups.filter { it.second == strongestMatch }.map { it.first }
        } else {
            groups.filter { group -> group.agents.any { it == "*" } }
        }

        return ResearchRobotsRules(
            rules = selected.flatMap { it.rules },
            crawlDelayMs = selected.maxOfOrNull { it.crawlDelayMs } ?: 0L,
        )
    }

    private fun compileRule(path: String, allow: Boolean): ResearchRobotsRule? {
        if (path.length > MAX_RULE_CHARS) return null
        val anchored = path.endsWith('$')
        val pattern = if (anchored) path.dropLast(1) else path
        if (pattern.isEmpty()) return null

        val expression = buildString {
            append('^')
            pattern.forEach { character ->
                if (character == '*') append(".*") else append(Regex.escape(character.toString()))
            }
            if (!anchored) append(".*")
            else append('$')
        }
        val regex = runCatching { Regex(expression) }.getOrNull() ?: return null
        val specificity = pattern.count { it != '*' }
        return ResearchRobotsRule(allow, specificity, regex)
    }

    private const val CRAWLER_NAME = "InfernoResearch"
    private const val MAX_RULE_CHARS = 512
    private const val MAX_CRAWL_DELAY_MS = 10_000L
}
