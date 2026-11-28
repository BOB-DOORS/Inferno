package to.eyed.inferno.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRankingTest {
    @Test
    fun titlesAreWeightedMoreHeavilyThanIncidentalSnippetMatches() {
        val titleHit = ResearchRanking.relevanceScore(
            query = "Redmi Note 15 Pro",
            title = "Redmi Note 15 Pro review",
            snippet = "",
        )
        val snippetHit = ResearchRanking.relevanceScore(
            query = "Redmi Note 15 Pro",
            title = "A general Android phone guide",
            snippet = "This article briefly mentions the Redmi Note 15 Pro.",
        )
        assertTrue(titleHit > snippetHit)
    }

    @Test
    fun exactTitlePhraseGetsARelevanceBoost() {
        val phraseHit = ResearchRanking.relevanceScore(
            "offline web search engine",
            "Offline web search engine for Android",
            "A full guide.",
        )
        val partialHit = ResearchRanking.relevanceScore(
            "offline web search engine",
            "Offline search tools",
            "Web indexing and crawling are covered.",
        )
        assertTrue(phraseHit > partialHit)
    }

    @Test
    fun queryBroadeningRemovesFillerButPreservesMeaningfulSearchTerms() {
        assertEquals(
            "best android offline search engine 2026",
            ResearchRanking.broadenQuery("What is the best Android offline search engine for 2026?"),
        )
    }

    @Test
    fun shortTechnicalTermsRemainSearchable() {
        assertTrue(ResearchRanking.relevanceScore("AI assistant", "AI assistant model", "") > 0.0)
    }

    @Test
    fun rankingNeverReturnsNanForEmptyOrPunctuationQueries() {
        assertEquals(0.0, ResearchRanking.relevanceScore("!!!", "Nothing", "Nothing"), 0.0)
        assertTrue(ResearchRanking.relevanceScore("mobile", "", "").isFinite())
    }
}
