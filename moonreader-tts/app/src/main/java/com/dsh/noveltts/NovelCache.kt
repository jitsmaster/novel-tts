package com.dsh.noveltts

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * On-disk cache of a parsed Novel, keyed by the source uri + byte size.
 * Re-opening a book (nightly listening sessions) skips the whole
 * decode/parse and starts instantly.
 *
 * Format (UTF-8, one record per line — blocks never contain newlines):
 *   <novelTitle>
 *   <sourceSizeBytes>
 *   <chapterCount>
 *   <blockCount>            <- per chapter
 *   <chapterTitle>
 *   <block text>            <- blockCount lines
 *   ...
 */
object NovelCache {

    private const val TAG = "NovelCache"

    private fun key(uri: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "novel_cache").apply { mkdirs() }

    private fun file(context: Context, uri: String): File =
        File(dir(context), key(uri) + ".novel")

    fun save(context: Context, uri: String?, sizeBytes: Long, novel: Novel) {
        if (uri == null) return
        try {
            val sb = StringBuilder()
            sb.append(novel.title.replace('\n', ' ')).append('\n')
            sb.append(sizeBytes).append('\n')
            sb.append(novel.chapters.size).append('\n')
            for (ch in novel.chapters) {
                sb.append(ch.blocks.size).append('\n')
                sb.append(ch.title.replace('\n', ' ')).append('\n')
                for (b in ch.blocks) sb.append(b).append('\n')
            }
            val f = file(context, uri)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) tmp.copyTo(f, overwrite = true)
            Log.i(TAG, "saved ${novel.chapters.size} chapters, ${sizeBytes} bytes for $uri")
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    fun load(context: Context, uri: String?, sizeBytes: Long): Novel? {
        if (uri == null) return null
        return try {
            val f = file(context, uri)
            if (!f.exists()) return null
            val lines = f.readLines(Charsets.UTF_8)
            var i = 0
            val title = lines[i++]
            val storedSize = lines[i++].toLongOrNull() ?: return null
            if (storedSize != sizeBytes) return null
            val chapterCount = lines[i++].toIntOrNull() ?: return null
            val chapters = ArrayList<Chapter>(chapterCount)
            repeat(chapterCount) {
                if (i >= lines.size) throw Exception("truncated")
                val blockCount = lines[i++].toIntOrNull() ?: throw Exception("bad block count")
                val chTitle = lines[i++]
                val blocks = ArrayList<String>(blockCount)
                repeat(blockCount) {
                    if (i >= lines.size) throw Exception("truncated")
                    blocks.add(lines[i++])
                }
                chapters.add(Chapter(chTitle, blocks))
            }
            Novel(title, chapters)
        } catch (e: Exception) {
            Log.w(TAG, "cache load failed: ${e.message}")
            null
        }
    }
}
