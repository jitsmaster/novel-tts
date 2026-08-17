package com.dsh.noveltts

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/**
 * SQLite sentence cache: key = sha256(voice|rate|pitch|text) -> raw audio bytes.
 * Repeated sentences in a novel (and re-reads) are served instantly without
 * hitting Edge TTS, which also drastically reduces throttling pressure.
 */
class SentenceCache(context: Context) : SQLiteOpenHelper(context, "tts_cache.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE cache (key TEXT PRIMARY KEY, audio BLOB NOT NULL, created INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    companion object {
        fun key(voice: String, ratePct: String, pitchHz: String, text: String): String {
            val input = "$voice|$ratePct|$pitchHz|$text"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append("%02x".format(b))
            return sb.toString()
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
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("cache", null, null)
    }
}
