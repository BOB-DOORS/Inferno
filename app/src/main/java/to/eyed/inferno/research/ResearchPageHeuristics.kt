package to.eyed.inferno.research

/** Cheap static-HTML classifier; it runs after the first HTTP response and before launching a WebView. */
internal object ResearchPageHeuristics {
    private val appRoot = Regex("""<(?:div|main|body)[^>]*(?:id|data-reactroot)=["'](?:root|app|__next|__nuxt)["']""", RegexOption.IGNORE_CASE)
    private val framework = Regex(
        """(?:__NEXT_DATA__|__NUXT__|data-reactroot|/_next/static/|/assets/index-[^"']+\.js|react-dom|webpackJsonp|vite/client|ng-version)""",
        RegexOption.IGNORE_CASE,
    )
    private val scriptTag = Regex("<script\\b", RegexOption.IGNORE_CASE)

    fun shouldRenderJavascript(html: String, extractedText: String): Boolean {
        val textLength = extractedText.trim().length
        if (textLength >= MAX_STATIC_TEXT) return false
        val hasScripts = scriptTag.containsMatchIn(html)
        val hasFramework = framework.containsMatchIn(html)
        val hasApplicationRoot = appRoot.containsMatchIn(html)
        return (hasApplicationRoot && textLength < 2_600) ||
            (hasFramework && textLength < 3_800) ||
            (hasScripts && textLength < 420)
    }

    const val MAX_STATIC_TEXT = 4_000
}
