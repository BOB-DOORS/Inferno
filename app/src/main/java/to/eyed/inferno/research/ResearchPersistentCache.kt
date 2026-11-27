package to.eyed.inferno.research

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Private, bounded SQLite cache for already-parsed public research inputs. It stores fetched HTML and robots.txt
 * only; it never stores chat messages, prompts, model output, attachments, or authentication cookies.
 */
internal class ResearchPersistentCache(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    @Synchronized
    fun get(kind: String, key: String, maxAgeMs: Long): String? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE,
            arrayOf(COL_CACHED_AT, COL_PAYLOAD),
            "$COL_KIND = ? AND $COL_KEY = ?",
            arrayOf(kind, key),
            null,
            null,
            null,
            "1",
        )
        var expired = false
        val result = cursor.use {
            if (!it.moveToFirst()) return@use null
            val cachedAt = it.getLong(0)
            if (System.currentTimeMillis() - cachedAt !in 0..maxAgeMs) {
                expired = true
                null
            } else {
                runCatching { ResearchCacheCodec.decode(it.getBlob(1)) }.getOrNull()
            }
        }
        if (expired || result == null) {
            writableDatabase.delete(TABLE, "$COL_KIND = ? AND $COL_KEY = ?", arrayOf(kind, key))
        }
        return result
    }

    @Synchronized
    fun put(kind: String, key: String, value: String) {
        if (value.isEmpty() || value.length > MAX_ENTRY_CHARS) return
        val payload = runCatching { ResearchCacheCodec.encode(value) }.getOrNull() ?: return
        if (payload.size > MAX_ENTRY_BYTES) return

        val values = ContentValues().apply {
            put(COL_KIND, kind)
            put(COL_KEY, key)
            put(COL_CACHED_AT, System.currentTimeMillis())
            put(COL_PAYLOAD, payload)
        }
        val db = writableDatabase
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)

        val keep = when (kind) {
            KIND_PAGE -> MAX_PAGE_ENTRIES
            KIND_ROBOTS -> MAX_ROBOTS_ENTRIES
            else -> 0
        }
        if (keep > 0) {
            db.execSQL(
                "DELETE FROM $TABLE WHERE $COL_KIND = ? AND $COL_KEY NOT IN " +
                    "(SELECT $COL_KEY FROM $TABLE WHERE $COL_KIND = ? ORDER BY $COL_CACHED_AT DESC LIMIT ?)",
                arrayOf(kind, kind, keep),
            )
        }
        trimTotalSize(db)
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }

    private fun trimTotalSize(db: SQLiteDatabase) {
        while (true) {
            val cursor = db.rawQuery("SELECT COALESCE(SUM(length($COL_PAYLOAD)), 0) FROM $TABLE", null)
            val size = cursor.use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (size <= MAX_TOTAL_BYTES) return
            db.execSQL(
                "DELETE FROM $TABLE WHERE $COL_KEY IN " +
                    "(SELECT $COL_KEY FROM $TABLE ORDER BY $COL_CACHED_AT ASC LIMIT 4)",
            )
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This database contains disposable cache entries, never user-authored conversations.
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    companion object {
        const val KIND_PAGE = "page"
        const val KIND_ROBOTS = "robots"

        private const val DATABASE_NAME = "research-cache.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE = "research_entries"
        private const val COL_KIND = "cache_kind"
        private const val COL_KEY = "cache_key"
        private const val COL_CACHED_AT = "cached_at"
        private const val COL_PAYLOAD = "payload"
        private const val MAX_ENTRY_CHARS = 2_000_000
        private const val MAX_ENTRY_BYTES = 2_500_000
        private const val MAX_PAGE_ENTRIES = 32
        private const val MAX_ROBOTS_ENTRIES = 256
        private const val MAX_TOTAL_BYTES = 18L * 1024 * 1024
    }
}

internal object ResearchCacheCodec {
    fun encode(value: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { it.write(value) }
        return output.toByteArray()
    }

    fun decode(payload: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(payload)).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
