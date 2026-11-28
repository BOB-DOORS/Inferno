package to.eyed.inferno.research

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Private, bounded SQLite cache for public research inputs.
 *
 * Only fetched page HTML and robots.txt are stored. Chat messages, prompts, model output, attachments,
 * authentication data, and WebView storage are deliberately outside this database.
 */
internal class ResearchPersistentCache(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    @Synchronized
    fun get(kind: String, key: String, maxAgeMs: Long): String? {
        if (kind !in CACHE_KINDS || key.isBlank()) return null

        val now = System.currentTimeMillis()
        val cursor = readableDatabase.query(
            TABLE,
            arrayOf(COL_CACHED_AT, COL_PAYLOAD),
            "$COL_KIND = ? AND $COL_KEY = ?",
            arrayOf(kind, key),
            null,
            null,
            null,
            "1",
        )
        val result = cursor.use {
            if (!it.moveToFirst()) return@use null
            val cachedAt = it.getLong(0)
            val age = now - cachedAt
            if (age !in 0..maxAgeMs) {
                null
            } else {
                runCatching { ResearchCacheCodec.decode(it.getBlob(1)) }.getOrNull()
            }
        }

        // Expired and corrupt entries are disposable. Remove them instead of repeatedly decoding them.
        if (result == null) {
            writableDatabase.delete(TABLE, "$COL_KIND = ? AND $COL_KEY = ?", arrayOf(kind, key))
        }
        return result
    }

    @Synchronized
    fun put(kind: String, key: String, value: String) {
        if (kind !in CACHE_KINDS || key.isBlank() || key.length > MAX_KEY_CHARS) return
        if (value.isEmpty() || value.length > MAX_ENTRY_CHARS) return

        val payload = runCatching { ResearchCacheCodec.encode(value) }.getOrNull() ?: return
        if (payload.isEmpty() || payload.size > MAX_ENTRY_BYTES) return

        val keep: Int = when (kind) {
            KIND_PAGE -> MAX_PAGE_ENTRIES
            KIND_ROBOTS -> MAX_ROBOTS_ENTRIES
            else -> return
        }
        val values = ContentValues().apply {
            put(COL_KIND, kind)
            put(COL_KEY, key)
            put(COL_CACHED_AT, System.currentTimeMillis())
            put(COL_PAYLOAD, payload)
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val inserted = db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            if (inserted != -1L) {
                db.execSQL(
                    "DELETE FROM $TABLE WHERE $COL_KIND = ? AND $COL_KEY NOT IN " +
                        "(SELECT $COL_KEY FROM $TABLE WHERE $COL_KIND = ? " +
                        "ORDER BY $COL_CACHED_AT DESC LIMIT ?)",
                    arrayOf<Any>(kind, kind, keep),
                )
                trimTotalSize(db)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }

    private fun trimTotalSize(db: SQLiteDatabase) {
        while (true) {
            val cursor = db.rawQuery(
                "SELECT COALESCE(SUM(length($COL_PAYLOAD)), 0) FROM $TABLE",
                null,
            )
            val size = cursor.use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (size <= MAX_TOTAL_BYTES) return

            // Delete concrete rows, not just keys: page and robots records may share a key.
            val removed = db.delete(
                TABLE,
                "rowid IN (SELECT rowid FROM $TABLE ORDER BY $COL_CACHED_AT ASC LIMIT ?)",
                arrayOf(EVICTION_BATCH.toString()),
            )
            if (removed == 0) return
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "$COL_KIND TEXT NOT NULL, " +
                "$COL_KEY TEXT NOT NULL, " +
                "$COL_CACHED_AT INTEGER NOT NULL, " +
                "$COL_PAYLOAD BLOB NOT NULL, " +
                "PRIMARY KEY ($COL_KIND, $COL_KEY))",
        )
        db.execSQL("CREATE INDEX research_cache_age ON $TABLE ($COL_CACHED_AT)")
        db.execSQL("CREATE INDEX research_cache_kind_age ON $TABLE ($COL_KIND, $COL_CACHED_AT DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This database is disposable cache data, never user-authored conversations.
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    companion object {
        const val KIND_PAGE = "page"
        const val KIND_ROBOTS = "robots"

        private val CACHE_KINDS = setOf(KIND_PAGE, KIND_ROBOTS)
        private const val DATABASE_NAME = "research-cache.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE = "research_entries"
        private const val COL_KIND = "cache_kind"
        private const val COL_KEY = "cache_key"
        private const val COL_CACHED_AT = "cached_at"
        private const val COL_PAYLOAD = "payload"
        private const val MAX_KEY_CHARS = 4_096
        private const val MAX_ENTRY_CHARS = 2_000_000
        private const val MAX_ENTRY_BYTES = 2_500_000
        private const val MAX_PAGE_ENTRIES = 32
        private const val MAX_ROBOTS_ENTRIES = 256
        private const val MAX_TOTAL_BYTES = 18L * 1024 * 1024
        private const val EVICTION_BATCH = 4
    }
}

internal object ResearchCacheCodec {
    private const val MAX_DECODED_CHARS = 2_000_000
    private const val READ_BUFFER_CHARS = 8_192

    fun encode(value: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { it.write(value) }
        return output.toByteArray()
    }

    fun decode(payload: ByteArray, maxChars: Int = MAX_DECODED_CHARS): String {
        require(maxChars >= 0) { "maxChars must not be negative" }
        val output = StringBuilder(minOf(payload.size * 2, maxChars))
        GZIPInputStream(ByteArrayInputStream(payload)).use { gzip ->
            InputStreamReader(gzip, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(READ_BUFFER_CHARS)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (output.length > maxChars - count) {
                        throw IOException("Cached payload exceeded the decompression limit.")
                    }
                    output.append(buffer, 0, count)
                }
            }
        }
        return output.toString()
    }
}
