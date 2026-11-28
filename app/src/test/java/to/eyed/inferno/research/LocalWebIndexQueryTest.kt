package to.eyed.inferno.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWebIndexQueryTest {
    @Test
    fun emptyAndPunctuationOnlyQueriesDoNotReachFts() {
        assertNull(LocalWebIndexQuery.expression(""))
        assertNull(LocalWebIndexQuery.expression(" !!! --- "))
        assertNull(LocalWebIndexQuery.expression("  "))
    }

    @Test
    fun queryTermsAreQuotedAndJoinedAsAnOrSearch() {
        assertEquals(
            "\"android\" OR \"webview\" OR \"javascript\"",
            LocalWebIndexQuery.expression("Android WebView JavaScript"),
        )
    }

    @Test
    fun ftsOperatorsAndPunctuationCannotInjectQuerySyntax() {
        val expression = LocalWebIndexQuery.expression("""android OR "drop table" * test""")!!

        assertTrue(expression.startsWith("\"android\" OR \"or\""))
        assertFalse(expression.contains("*"))
        assertFalse(expression.contains("("))
        assertFalse(expression.contains(")"))
    }

    @Test
    fun duplicateAndShortTokensAreFilteredAndTermCountIsBounded() {
        val expression = LocalWebIndexQuery.expression("a aa aa bb cc dd ee ff gg hh ii jj kk")!!

        assertEquals(
            listOf("aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", "ii", "jj"),
            Regex("\"([^\"]+)\"").findAll(expression).map { it.groupValues[1] }.toList(),
        )
    }
}
