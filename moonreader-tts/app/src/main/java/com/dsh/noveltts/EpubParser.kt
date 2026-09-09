package com.dsh.noveltts

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

/**
 * Minimal EPUB reader: unzips in memory, follows META-INF/container.xml ->
 * OPF (manifest + spine), extracts each spine item's XHTML into paragraphs
 * via XmlPullParser, and builds the same paragraph-sized speak blocks as the
 * txt path. No external dependencies (offline build).
 */
object EpubParser {

    private const val TAG = "EpubParser"

    data class Result(val novel: Novel?, val error: String?)

    /** Returns null if [bytes] is not an EPUB (caller falls back to txt). */
    fun parse(bytes: ByteArray, fallbackTitle: String): Result {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return Result(null, null)   // not a zip -> not an epub
        }
        return try {
            val entries = readEntries(bytes) ?: return Result(null, "损坏的 EPUB（无法解压）")
            val opfPath = findOpf(entries) ?: return Result(null, "EPUB 缺少 container.xml / OPF")
            val (bookTitle, chapters) = parseOpf(entries, opfPath, fallbackTitle)
            if (chapters.isEmpty()) Result(null, "EPUB 中没有可读章节")
            else Result(Novel(bookTitle, chapters), null)
        } catch (e: Exception) {
            Log.e(TAG, "epub parse failed", e)
            Result(null, "EPUB 解析失败: ${e.message}")
        }
    }

    // ---- zip --------------------------------------------------------------

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray>? {
        val out = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory) {
                        val buf = zip.readBytes()
                        out[normalize(e.name)] = buf
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }

    private fun normalize(name: String): String =
        name.trimStart('/').replace('\\', '/')

    private fun findOpf(entries: Map<String, ByteArray>): String? {
        val container = entries["META-INF/container.xml"] ?: return null
        val text = String(container, Charsets.UTF_8)
        val m = Regex("""rootfile[^>]*full-path\s*=\s*["']([^"']+)["']""").find(text)
        return m?.groupValues?.get(1)?.let { normalize(it) }
    }

    // ---- OPF --------------------------------------------------------------

    private class OpfItem(val id: String, val href: String)

    private fun parseOpf(
        entries: Map<String, ByteArray>,
        opfPath: String,
        fallbackTitle: String
    ): Pair<String, List<Chapter>> {
        val baseDir = opfPath.substringBeforeLast('/', "")
        val items = HashMap<String, OpfItem>()
        val spineOrder = ArrayList<String>()
        var title = fallbackTitle

        val opf = String(entries[opfPath] ?: ByteArray(0), Charsets.UTF_8)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(opf))
        var pendingManifest = false
        var pendingSpine = false
        var pendingTitle = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    when (name) {
                        "manifest" -> pendingManifest = true
                        "spine" -> pendingSpine = true
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            if (id.isNotEmpty() && href.isNotEmpty()) {
                                items[id] = OpfItem(id, resolve(baseDir, href))
                            }
                        }
                        "itemref" -> {
                            val ref = parser.getAttributeValue(null, "idref") ?: ""
                            if (ref.isNotEmpty()) spineOrder.add(ref)
                        }
                        "title" -> if (!pendingTitle) {
                            pendingTitle = true
                            title = parser.nextText().trim().ifEmpty { fallbackTitle }
                        }
                    }
                    if (name == "manifest") pendingManifest = true
                    if (name == "spine") pendingSpine = true
                }
            }
            parser.next()
        }

        val chapters = ArrayList<Chapter>()
        var index = 0
        for (idref in spineOrder) {
            val item = items[idref] ?: continue
            val raw = entries[item.href] ?: continue
            val extracted = extractXhtml(raw) ?: continue
            if (extracted.paragraphs.isEmpty()) continue
            index++
            val chTitle = extracted.title.ifEmpty { "第 $index 节" }
            chapters.add(Chapter(chTitle, NovelParser.makeBlocks(extracted.paragraphs)))
        }
        return title to chapters
    }

    private fun resolve(baseDir: String, href: String): String {
        var h = href.replace('\\', '/')
        val parts = ArrayList<String>()
        if (baseDir.isNotEmpty()) parts.addAll(baseDir.split('/'))
        for (seg in h.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    // ---- xhtml extraction -------------------------------------------------

    private class Xhtml(val title: String, val paragraphs: List<String>)

    private fun extractXhtml(raw: ByteArray): Xhtml? {
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(
                StringReader(String(raw, Charsets.UTF_8).removePrefix("\uFEFF"))
            )
            val sb = StringBuilder()
            var firstHeading = ""
            var depth = 0
            var skipDepth = -1   // inside <script>/<style>/<head>
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val n = parser.name.lowercase()
                        if (skipDepth < 0 && (n == "script" || n == "style" || n == "head")) {
                            skipDepth = depth
                        } else if (skipDepth < 0) {
                            when (n) {
                                "p", "div", "blockquote", "li", "h1", "h2", "h3", "h4", "h5", "h6", "br" ->
                                    if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                            }
                        }
                        depth++
                    }
                    XmlPullParser.TEXT -> {
                        if (skipDepth < 0) {
                            val t = parser.text
                            if (t != null && t.isNotBlank()) sb.append(t.trim())
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val n = parser.name.lowercase()
                        depth--
                        if (skipDepth >= 0 && depth <= skipDepth) {
                            skipDepth = -1
                        } else if (n == "h1" || n == "h2" || n == "h3") {
                            if (firstHeading.isEmpty()) firstHeading = sb.toString().substringAfterLast('\n').trim()
                        }
                    }
                }
                parser.next()
            }
            val paragraphs = sb.toString()
                .replace(Regex("""[ \t]+"""), "")
                .split(Regex("""\n+"""))
                .map { it.trim() }
                .filter { it.length >= 2 }
            Xhtml(firstHeading.trim(), paragraphs)
        } catch (e: Exception) {
            null
        }
    }
}
