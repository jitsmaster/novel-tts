package com.dsh.noveltts

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SQLite sentence cache: key = sha256(voice|rate|pitch|text) -> raw audio bytes.
 * Repeated sentences in a novel (and re-reads) are served instantly without
 * hitting Edge TTS / the server, which also drastically reduces throttling.
 *
 * The cache is BOUNDED: it auto-evicts the oldest sentences (by creation time)
 * once it exceeds [BUDGET_MB], in the background, and reclaims the file space.
 * Previously every unique (voice, rate, pitch, sentence) audio blob was stored
 * forever — a few weeks of nightly reading grew the DB past 3.6 GB.
 */
class SentenceCache(context: Context) : SQLiteOpenHelper(context, "tts_cache.db", null, DB_VERSION) {

    companion object {
        private const val DB_VERSION = 2
        private const val TAG = "SentenceCache"

        /** Soft ceiling for the on-device cache. Server/Kokoro audio is WAV
         * (24 kHz mono ~48 KB/s, ~0.2-0.4 MB per spoken sentence), Edge audio
         * is mp3 and far smaller. 1 GiB holds many thousands of sentences of
         * instant replay (an unbounded DB used to grow past 3.6 GB); older
         * entries are re-fetched on demand if re-read.
         *
         * NOTE: keep this generous — a pre-rendered chapter is useless if the
         * trimmer evicts it before Moon Reader plays it back. */
        const val BUDGET_MB = 1024L
        private const val BUDGET_BYTES = BUDGET_MB * 1024 * 1024

        /** After this many inserts, check the size on the background thread. */
        private const val CHECK_EVERY_PUTS = 25

        /** Cap per cleanup run so a big one-time trim never stalls playback. */
        private const val MAX_DELETE_BYTES_PER_RUN = 128L * 1024 * 1024

        /** DBs created before v2 have no index and no auto_vacuum; trimming
         * their rows does not shrink the file, so run one full VACUUM. */
        @Volatile
        private var needsFullVacuum = false

        fun key(voice: String, ratePct: String, pitchHz: String, text: String): String {
            val input = "$voice|$ratePct|$pitchHz|$text"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append("%02x".format(b))
            return sb.toString()
        }
    }

    private val cleaner: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-cache-cleaner").apply { isDaemon = true }
    }
    private var putsSinceCheck = 0

    init {
        // Trim an already-over-budget DB right away (e.g. after an upgrade),
        // without waiting for more inserts to accumulate.
        cleaner.execute { try { evictIfNeeded() } catch (e: Exception) { Log.w(TAG, "initial eviction failed: ${e.message}") } }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // auto_vacuum=FULL: freed pages are reclaimed from the FILE at the end
        // of each deleting transaction, so evicting rows also gives the disk
        // space back (no manual VACUUM needed on fresh DBs). Legacy v1 DBs
        // (created without auto_vacuum) get one background VACUUM via
        // needsFullVacuum. busy_timeout keeps the background trim from erroring
        // while the engine reads/writes concurrently.
        // NOTE: pragmas that return rows must run via rawQuery (execSQL throws)
        // and the cursor must be stepped for the pragma to take effect.
        try {
            db.rawQuery("PRAGMA auto_vacuum = FULL", null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA busy_timeout = 10000", null).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "onConfigure pragmas failed: ${e.message}")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE cache (key TEXT PRIMARY KEY, audio BLOB NOT NULL, created INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_cache_created ON cache(created)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_cache_created ON cache(created)")
            needsFullVacuum = true
        }
    }

    @Synchronized
    fun get(key: String): ByteArray? {
        val db = readableDatabase
        db.query("cache", arrayOf("audio"), "key=?", arrayOf(key), null, null, null).use { c ->
            if (c.moveToFirst()) {
                return c.getBlob(0)
            }
        }
        return null
    }

    @Synchronized
    fun put(key: String, audio: ByteArray) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("key", key)
            put("audio", audio)
            put("created", System.currentTimeMillis())
        }
        db.insertWithOnConflict("cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        if (++putsSinceCheck >= CHECK_EVERY_PUTS) {
            putsSinceCheck = 0
            cleaner.execute { try { evictIfNeeded() } catch (e: Exception) { Log.w(TAG, "eviction failed: ${e.message}") } }
        }
    }

    /** Drop a single entry. Used when stored bytes turn out to be
     * undecodable, so the next attempt re-fetches instead of replaying the
     * same bad blob forever. */
    @Synchronized
    fun remove(key: String) {
        try {
            writableDatabase.delete("cache", "key=?", arrayOf(key))
        } catch (e: Exception) {
            Log.w(TAG, "remove failed: ${e.message}")
        }
    }

    @Synchronized
    fun clear() {
        val db = writableDatabase
        db.delete("cache", null, null)
        compactNow(db)
        Log.i(TAG, "cache cleared")
    }

    /** Returns (entryCount, totalBytes). */
    @Synchronized
    fun stats(): Pair<Long, Long> {
        val db = readableDatabase
        var count = 0L
        var bytes = 0L
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(length(audio)),0) FROM cache", null).use { c ->
            if (c.moveToFirst()) {
                count = c.getLong(0)
                bytes = c.getLong(1)
            }
        }
        return count to bytes
    }

    /** Delete the oldest rows until under budget (background thread). */
    private fun evictIfNeeded() {
        val db = writableDatabase
        var total = totalBytes(db)
        var over = total - BUDGET_BYTES
        if (over <= 0 && !needsFullVacuum) return

        var deletedRows = 0
        var deletedBytes = 0L
        if (over > 0) {
            db.beginTransaction()
            try {
                while (over > 0 && deletedBytes < MAX_DELETE_BYTES_PER_RUN) {
                    val victims = ArrayList<Pair<String, Long>>()
                    db.rawQuery(
                        "SELECT key, length(audio) AS sz FROM cache ORDER BY created ASC LIMIT 200",
                        null
                    ).use { c ->
                        while (c.moveToNext()) victims.add(c.getString(0) to c.getLong(1))
                    }
                    if (victims.isEmpty()) break
                    for ((k, sz) in victims) {
                        if (db.delete("cache", "key=?", arrayOf(k)) > 0) {
                            deletedRows++
                            deletedBytes += sz
                        }
                    }
                    over -= victims.sumOf { it.second }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            total = totalBytes(db)
            Log.i(
                TAG,
                "evicted $deletedRows rows (~${deletedBytes / 1024 / 1024} MiB); " +
                    "cache ${total / 1024 / 1024} MiB / ${BUDGET_MB} MiB budget"
            )
        }
        // Fresh DBs (auto_vacuum=FULL) reclaimed the space automatically at
        // commit; only legacy v1 DBs still need a one-time full VACUUM.
        if (needsFullVacuum) {
            needsFullVacuum = false
            try {
                db.execSQL("VACUUM")
                Log.i(TAG, "full VACUUM done (legacy file compacted)")
            } catch (e: Exception) {
                needsFullVacuum = true
                Log.w(TAG, "VACUUM deferred (busy?): ${e.message}")
            }
        }
    }

    private fun totalBytes(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT COALESCE(SUM(length(audio)),0) FROM cache", null).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 0L
    }

    private fun compactNow(db: SQLiteDatabase) {
        // Clear() should hand the space back even on legacy v1 DBs.
        if (!needsFullVacuum) return
        needsFullVacuum = false
        try {
            db.execSQL("VACUUM")
            Log.i(TAG, "full VACUUM done (file compacted)")
        } catch (e: Exception) {
            needsFullVacuum = true
            Log.w(TAG, "compact deferred: ${e.message}")
        }
    }
}
