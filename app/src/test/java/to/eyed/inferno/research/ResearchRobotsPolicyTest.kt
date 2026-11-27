package to.eyed.inferno.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRobotsPolicyTest {
    @Test
    fun exactCrawlerGroupOverridesWildcardGroup() {
        val policy = ResearchRobotsPolicy.parse(
            """
                User-agent: *
                Disallow: /

                User-agent: InfernoResearch
                Disallow: /private
            """.trimIndent(),
        )

        assertTrue(policy.allows("/public"))
        assertFalse(policy.allows("/private/page"))
    }

    @Test
    fun crawlerProductTokenUsesLongestPrefixMatch() {
        val policy = ResearchRobotsPolicy.parse(
            """
                User-agent: *
                Disallow: /

                User-agent: Inferno
                Disallow: /shared

                User-agent: InfernoResearch
                Allow: /shared
                Disallow: /private
            """.trimIndent(),
        )

        assertTrue(policy.allows("/shared"))
        assertFalse(policy.allows("/private"))
    }

    @Test
    fun longestMatchSupportsWildcardEndAnchorAndAllowTieBreak() {
        val policy = ResearchRobotsPolicy.parse(
            """
                User-agent: *
                Disallow: /private
                Allow: /private/open
                Disallow: /*/secret$
                Crawl-delay: 1.25
            """.trimIndent(),
        )

        assertFalse(policy.allows("/private/closed"))
        assertTrue(policy.allows("/private/open/item"))
        assertFalse(policy.allows("/api/secret"))
        assertTrue(policy.allows("/api/secret/more"))
        assertEquals(1_250L, policy.crawlDelayMs)
    }

    @Test
    fun allowWinsEqualSpecificityTie() {
        val policy = ResearchRobotsPolicy.parse(
            """
                User-agent: *
                Disallow: /same
                Allow: /same
            """.trimIndent(),
        )

        assertTrue(policy.allows("/same/path"))
    }

    @Test
    fun unselectedAgentsDoNotLeakRulesIntoWildcardGroup() {
        val policy = ResearchRobotsPolicy.parse(
            """
                User-agent: ExampleBot
                Disallow: /

                User-agent: *
                Disallow: /private
            """.trimIndent(),
        )

        assertTrue(policy.allows("/public"))
        assertFalse(policy.allows("/private/data"))
    }

    @Test
    fun denyAllPolicyRejectsEveryPath() {
        assertFalse(ResearchRobotsRules.denyAll().allows("/"))
        assertFalse(ResearchRobotsRules.denyAll().allows("/index.html"))
    }

    @Test
    fun invalidAndEmptyRulesDoNotBecomeAccidentalBlocks() {
        val policy = ResearchRobotsPolicy.parse(
            """
                User-agent: *
                Disallow:
                Crawl-delay: NaN
                Crawl-delay: -1
            """.trimIndent(),
        )

        assertTrue(policy.allows("/anything"))
        assertEquals(0L, policy.crawlDelayMs)
    }
}
