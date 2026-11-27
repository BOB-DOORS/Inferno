package to.eyed.inferno.research

/**
 * Fast, allocation-light signal check after static extraction. It recognizes common SPA shells without
 * sending ordinary article pages through a full WebView. Strong static text always wins over framework hints.
 */
internal object ResearchPageHeuristics {
    private val appRoot = Regex(
        """<(?:div|main|body)[^>]*(?:id=["'](?:root|app|__next|__nuxt|svelte)["']|data-reactroot|data-v-app|ng-version)""",
        RegexOption.IGNORE_CASE,
    )
    private val framework = Regex(
        """(?:__NEXT_DATA__|__NUXT__|__APOLLO_STATE__|__INITIAL_STATE__|data-reactroot|data-v-app|ng-version|/_next/static/|/_nuxt/|/_app/immutable/|astro-island|vite/client|webpackJsonp|react-dom)""",
        RegexOption.IGNORE_CASE,
    )
    private val applicationBundle = Regex(
        """<script\b[^>]*(?:type=["']module["']|src=["'][^"']*(?:/assets/|/static/|/build/|\.chunk\.js|\.bundle\.js|/main[.-]|/app[.-]|/index[.-]))""",
        RegexOption.IGNORE_CASE,
    )
    private val scriptTag = Regex("<script\\b", RegexOption.IGNORE_CASE)

    fun shouldRenderJavascript(html: String, extractedText: String): Boolean {
        val textLength = extractedText.trim().length
        if (textLength >= MAX_STATIC_TEXT) return false

        val hasApplicationRoot = appRoot.containsMatchIn(html)
        val hasFramework = framework.containsMatchIn(html)
        val hasApplicationBundle = applicationBundle.containsMatchIn(html)
        val scriptCount = scriptTag.findAll(html).take(MAX_SCRIPT_PROBES).count()

        return (hasApplicationRoot && textLength < ROOT_TEXT_LIMIT) ||
            (hasFramework && textLength < FRAMEWORK_TEXT_LIMIT) ||
            (hasApplicationBundle && textLength < BUNDLE_TEXT_LIMIT) ||
            (textLength < EMPTY_TEXT_LIMIT && scriptCount >= MIN_EMPTY_PAGE_SCRIPTS)
    }

    const val MAX_STATIC_TEXT = 4_000
    private const val ROOT_TEXT_LIMIT = 2_600
    private const val FRAMEWORK_TEXT_LIMIT = 3_800
    private const val BUNDLE_TEXT_LIMIT = 1_800
    private const val EMPTY_TEXT_LIMIT = 120
    private const val MIN_EMPTY_PAGE_SCRIPTS = 2
    private const val MAX_SCRIPT_PROBES = 3
}
