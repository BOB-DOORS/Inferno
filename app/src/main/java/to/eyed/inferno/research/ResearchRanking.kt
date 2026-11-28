package to.eyed.inferno.research

import java.util.Locale

/** Lightweight, deterministic ranking helpers for the on-device metasearch pipeline. */
internal object ResearchRanking {
    private val stopWords = setOf(
        "the", "and", "for", "with", "from", "that", "this", "what", "when", "where", "which",
        "who", "how", "why", "are", "was", "were", "can", "could", "would", "should", "about",
        "please", "find", "show", "tell", "give", "does", "did", "not", "into", "onto", "than",
        "de", "da", "do", "das", "dos", "um", "uma", "uns", "umas", "para", "com", "por",
        "que", "qual", "quais", "como", "onde", "quando", "sao", "ser", "foi", "sua", "seu",
    )

    fun broadenQuery(query: String): String = tokens(query)
        .filter { it !in stopWords }
        .distinct()
        .take(MAX_QUERY_TERMS)
        .joinToString(" ")

    /**
     * Title matches dominate snippet matches. Coverage rewards documents that address the whole query,
     * while an exact phrase in a title receives a modest boost. This is deterministic and allocation-light.
     */
    fun relevanceScore(query: String, title: String, snippet: String): Double {
        val terms = tokens(query).distinct()
        if (terms.isEmpty()) return 0.0

        val titleText = title.lowercase(Locale.ROOT)
        val titleTerms = tokens(title).toHashSet()
        val snippetTerms = tokens(snippet).toHashSet()
        val titleHits = terms.count(titleTerms::contains)
        val snippetHits = terms.count(snippetTerms::contains)
        val covered = terms.count { it in titleTerms || it in snippetTerms }
        val coverage = covered.toDouble() / terms.size
        val titleCoverage = titleHits.toDouble() / terms.size
        val exactPhrase = query.trim().lowercase(Locale.ROOT).let { it.length >= MIN_PHRASE_CHARS && titleText.contains(it) }

        return titleHits * TITLE_WEIGHT +
            snippetHits * SNIPPET_WEIGHT +
            coverage * COVERAGE_WEIGHT +
            titleCoverage * TITLE_COVERAGE_WEIGHT +
            if (exactPhrase) EXACT_PHRASE_BONUS else 0.0
    }

    private fun tokens(value: String): List<String> = value.lowercase(Locale.ROOT)
        .split(TOKEN_SEPARATOR)
        .filter { it.length > MIN_TOKEN_LENGTH }

    private const val MAX_QUERY_TERMS = 9
    private const val MIN_TOKEN_LENGTH = 2
    private const val MIN_PHRASE_CHARS = 6
    private const val TITLE_WEIGHT = 3.2
    private const val SNIPPET_WEIGHT = 0.8
    private const val COVERAGE_WEIGHT = 4.0
    private const val TITLE_COVERAGE_WEIGHT = 2.5
    private const val EXACT_PHRASE_BONUS = 3.5
    private val TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
}
