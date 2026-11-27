package to.eyed.inferno.research

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

internal data class LocalIndexHit(
    val url: String,
    val title: String,
    val snippet: String,
)

internal data class CrawlCandidate(
    val url: String,
    val depth: Int,
)

/**
 * First-party, on-device web index.
 *
 * This is deliberately an incremental index, not a claim that the phone has downloaded the whole Internet.
 * Search providers and user-requested seed URLs populate a persistent crawl frontier. The crawler fetches a
 * small number of public pages per search, and indexes their readable text using Android SQLite FTS4.
 *
 * All network policy (HTTPS, DNS/SSRF, robots.txt and host pacing) remains in ResearchEngine before a page is
 * handed to this index. The database contains public page text, crawl metadata and pending public URLs only.
 */
internal class LocalWebIndex(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    @Synchronized
    fun search(query: String, limit: Int = DEFAULT_RESULT_LIMIT): List<LocalIndexHit> {
        val match = LocalWebIndexQuery.expression(query) ?: return emptyList()
        val safeLimit = limit.coerceIn(1, MAX_RESULT_LIMIT)
        return runCatching {
            readableDatabase.rawQuery(
                "SELECT url, title, snippet($FTS_TABLE, 2, '[', ']', ' … ', 14) " +
                    "FROM $FTS_TABLE WHERE $FTS_TABLE MATCH ? LIMIT ?",
                arrayOf(match, safeLimit.toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val url = cursor.getString(0)
                        val title = cursor.getString(1)
                        val snippet = cursor.getString(2)
                        if (url.isNotBlank() && title.isNotBlank()) {
                            add(LocalIndexHit(url, title, snippet.orEmpty()))
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun enqueue(url: String, depth: Int, priority: Int = 0) {
        if (url.isBlank() || url.length > MAX_URL_CHARS || depth !in 0..MAX_CRAWL_DEPTH) return
        if (!ResearchUrlPolicy.isSafePublicUrl(url)) return

        val values = ContentValues().apply {
            put(COL_URL, url)
            put(COL_DEPTH, depth)
            put(COL_PRIORITY, priority.coerceIn(0, MAX_PRIORITY))
            put(COL_QUEUED_AT, System.currentTimeMillis())
        }
        val db = writableDatabase
        db.insertWithOnConflict(QUEUE_TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        db.execSQL(
            "DELETE FROM $QUEUE_TABLE WHERE rowid IN (" +
                "SELECT rowid FROM $QUEUE_TABLE ORDER BY $COL_QUEUED_AT ASC " +
                "LIMIT -1 OFFSET $MAX_FRONTIER)",
        )
    }

    @Synchronized
    fun takeNext(limit: Int = DEFAULT_CRAWL_BATCH): List<CrawlCandidate> {
        val safeLimit = limit.coerceIn(1, MAX_CRAWL_BATCH)
        val db = writableDatabase
        val found = ArrayList<CrawlCandidate>(safeLimit)
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT $COL_URL, $COL_DEPTH FROM $QUEUE_TABLE " +
                    "ORDER BY $COL_PRIORITY DESC, $COL_QUEUED_AT ASC LIMIT ?",
                arrayOf(safeLimit.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    found += CrawlCandidate(cursor.getString(0), cursor.getInt(1))
                }
            }
            found.forEach { db.delete(QUEUE_TABLE, "$COL_URL = ?", arrayOf(it.url)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return found
    }

    @Synchronized
    fun isIndexedFresh(url: String, maxAgeMs: Long = PAGE_RECRAWL_MS): Boolean {
        val cursor = readableDatabase.query(
            META_TABLE,
            arrayOf(COL_CRAWLED_AT),
            "$COL_URL = ?",
            arrayOf(url),
            null,
            null,
            null,
            "1",
        )
        return cursor.use {
            if (!it.moveToFirst()) false
            else System.currentTimeMillis() - it.getLong(0) in 0..maxAgeMs
        }
    }

    @Synchronized
    fun indexPage(url: String, title: String, body: String) {
        if (url.isBlank() || title.isBlank() || body.isBlank()) return
        if (url.length > MAX_URL_CHARS || !ResearchUrlPolicy.isSafePublicUrl(url)) return
        if (!ResearchSafetyGate.allows(title + " " + body.take(SAFETY_TEXT_LIMIT))) return

        val safeTitle = title.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE_CHARS)
        val safeBody = body.replace(Regex("\\s+"), " ").trim().take(MAX_BODY_CHARS)
        if (safeTitle.isBlank() || safeBody.length < MIN_INDEX_TEXT_CHARS) return

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(FTS_TABLE, "$COL_URL = ?", arrayOf(url))
            db.insertOrThrow(
                FTS_TABLE,
                null,
                ContentValues().apply {
                    put(COL_URL, url)
                    put(COL_TITLE, safeTitle)
                    put(COL_BODY, safeBody)
                },
            )
            db.insertWithOnConflict(
                META_TABLE,
                null,
                ContentValues().apply {
                    put(COL_URL, url)
                    put(COL_CRAWLED_AT, System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            trimIndex(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun shouldProbeSitemap(host: String): Boolean {
        if (host.isBlank() || host.length > MAX_HOST_CHARS) return false
        val cursor = readableDatabase.query(
            SITEMAP_TABLE,
            arrayOf(COL_CHECKED_AT),
            "$COL_HOST = ?",
            arrayOf(host),
            null,
            null,
            null,
            "1",
        )
        return cursor.use {
            !it.moveToFirst() || System.currentTimeMillis() - it.getLong(0) !in 0..SITEMAP_RECHECK_MS
        }
    }

    @Synchronized
    fun markSitemapChecked(host: String) {
        if (host.isBlank() || host.length > MAX_HOST_CHARS) return
        writableDatabase.insertWithOnConflict(
            SITEMAP_TABLE,
            null,
            ContentValues().apply {
                put(COL_HOST, host)
                put(COL_CHECKED_AT, System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun stats(): Pair<Int, Int> {
        val db = readableDatabase
        val pages = db.rawQuery("SELECT COUNT(*) FROM $META_TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val queued = db.rawQuery("SELECT COUNT(*) FROM $QUEUE_TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        return pages to queued
    }

    private fun trimIndex(db: SQLiteDatabase) {
        val staleUrls = ArrayList<String>()
        db.rawQuery(
            "SELECT $COL_URL FROM $META_TABLE ORDER BY $COL_CRAWLED_AT DESC " +
                "LIMIT -1 OFFSET $MAX_INDEXED_PAGES",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) staleUrls += cursor.getString(0)
        }
        staleUrls.forEach { url ->
            db.delete(FTS_TABLE, "$COL_URL = ?", arrayOf(url))
            db.delete(META_TABLE, "$COL_URL = ?", arrayOf(url))
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE $FTS_TABLE USING fts4(" +
                "$COL_URL, $COL_TITLE, $COL_BODY, tokenize=unicode61)",
        )
        db.execSQL(
            "CREATE TABLE $META_TABLE (" +
                "$COL_URL TEXT PRIMARY KEY, $COL_CRAWLED_AT INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE $QUEUE_TABLE (" +
                "$COL_URL TEXT PRIMARY KEY, $COL_DEPTH INTEGER NOT NULL, " +
                "$COL_PRIORITY INTEGER NOT NULL, $COL_QUEUED_AT INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE $SITEMAP_TABLE (" +
                "$COL_HOST TEXT PRIMARY KEY, $COL_CHECKED_AT INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX local_web_queue_order ON $QUEUE_TABLE ($COL_PRIORITY DESC, $COL_QUEUED_AT ASC)")
        db.execSQL("CREATE INDEX local_web_meta_age ON $META_TABLE ($COL_CRAWLED_AT DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Rebuild only this disposable derived index; conversations and model data live elsewhere.
        db.execSQL("DROP TABLE IF EXISTS $FTS_TABLE")
        db.execSQL("DROP TABLE IF EXISTS $META_TABLE")
        db.execSQL("DROP TABLE IF EXISTS $QUEUE_TABLE")
        db.execSQL("DROP TABLE IF EXISTS $SITEMAP_TABLE")
        onCreate(db)
    }

    companion object {
        private const val DATABASE_NAME = "local-web-index.db"
        private const val DATABASE_VERSION = 1
        private const val FTS_TABLE = "web_pages_fts"
        private const val META_TABLE = "web_page_meta"
        private const val QUEUE_TABLE = "web_crawl_frontier"
        private const val SITEMAP_TABLE = "web_sitemaps"
        private const val COL_URL = "url"
        private const val COL_TITLE = "title"
        private const val COL_BODY = "body"
        private const val COL_DEPTH = "depth"
        private const val COL_PRIORITY = "priority"
        private const val COL_QUEUED_AT = "queued_at"
        private const val COL_CRAWLED_AT = "crawled_at"
        private const val COL_HOST = "host"
        private const val COL_CHECKED_AT = "checked_at"
        private const val DEFAULT_RESULT_LIMIT = 10
        private const val MAX_RESULT_LIMIT = 20
        private const val DEFAULT_CRAWL_BATCH = 3
        private const val MAX_CRAWL_BATCH = 4
        private const val MAX_FRONTIER = 3_000
        private const val MAX_INDEXED_PAGES = 1_500
        private const val MAX_URL_CHARS = 4_096
        private const val MAX_HOST_CHARS = 253
        private const val MAX_TITLE_CHARS = 240
        private const val MAX_BODY_CHARS = 18_000
        private const val MIN_INDEX_TEXT_CHARS = 100
        private const val SAFETY_TEXT_LIMIT = 2_000
        private const val MAX_PRIORITY = 10
        private const val MAX_CRAWL_DEPTH = 1
        private const val PAGE_RECRAWL_MS = 7 * 24 * 60 * 60 * 1_000L
        private const val SITEMAP_RECHECK_MS = 7 * 24 * 60 * 60 * 1_000L
    }
}

/** Builds a safe FTS4 OR-query from user words; never passes user syntax into MATCH unescaped. */
internal object LocalWebIndexQuery {
    fun expression(query: String): String? {
        val tokens = query.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .distinct()
            .take(MAX_QUERY_TERMS)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" OR ") { token -> "\"$token\"" }
    }

    private const val MAX_QUERY_TERMS = 10
}
