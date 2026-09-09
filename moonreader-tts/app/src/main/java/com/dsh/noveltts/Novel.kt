package com.dsh.noveltts

import java.io.File

/**
 * Parsed novel: chapters, each a list of speak-blocks (paragraph-sized text).
 *
 * A "block" is the unit handed to the TTS engine as one utterance — blocks are
 * built at SENTENCE boundaries and sized for smooth audiobook playback
 * (BLOCK_TARGET chars ≈ 40-50 s of speech), so pauses between utterances are
 * rare and intra-block audio is gap-free by construction.
 */
data class Novel(val title: String, val chapters: List<Chapter>)

data class Chapter(val title: String, val blocks: List<String>) {
    val text: String get() = blocks.joinToString("\n\n")
}

object NovelParser {

    /** 第X回/章/节/卷… or 序章/楔子/… headings (X may use ○ = U+25CB zero). */
    private val CHAPTER_RE =
        Regex("^\\s*(第[0-9０-９○〇一二三四五六七八九十百千零两]+[章节回卷部集篇]|序章|楔子|引子|前言|序言|后记|尾声|番外).{0,80}$")

    private const val BLOCK_TARGET = 240   // chars ≈ 40-50 s of speech at zh TTS pace
    private const val BLOCK_MAX = 900      // hard cap per utterance (Edge/Kokoro safe)

    fun looksLikeChapter(line: String): Boolean = CHAPTER_RE.matches(line.trim())

    /**
     * Parse raw novel text into chapters. Handles:
     *  - chapter headings 第X回/章/节… (anywhere a line starts with one)
     *  - blank-line separated paragraphs (book layout, wrapped lines joined)
     *  - one-paragraph-per-line files (webnovel export, no blank lines)
     *  - hard-wrapped text with no blank lines (old-book layout) — treated as
     *    one continuous flow, re-flowed into sentence-bounded blocks
     */
    fun parse(raw0: String): Novel {
        val raw = raw0.replace("\r\n", "\n").replace("\r", "\n")
        val lines = raw.split("\n")
        val title = lines.firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: "小说"

        // 1) slice into chapters by heading lines.
        val rawChapters = mutableListOf<Pair<String, MutableList<String>>>()
        var cur: Pair<String, MutableList<String>>? = null
        for (line in lines) {
            val t = line.trim()
            if (t.isNotEmpty() && CHAPTER_RE.matches(t)) {
                cur = t to mutableListOf()
                rawChapters.add(cur)
            } else {
                if (cur == null) {
                    // Text before the first heading (front matter). Keep as an
                    // unnamed chapter only if it is real content, not a title.
                    cur = "开头" to mutableListOf()
                    rawChapters.add(cur)
                }
                cur.second.add(line)
            }
        }
        if (rawChapters.isEmpty()) rawChapters.add(title to lines.toMutableList())

        val chapters = rawChapters.map { (chTitle, contentLines) ->
            val paragraphs = toParagraphs(contentLines)
            val blocks = buildBlocks(paragraphs)
            Chapter(chTitle, blocks)
        }.filter { it.blocks.isNotEmpty() }

        return Novel(title, chapters)
    }

    /** Blank-line groups = paragraphs; no blank lines = per-line paragraphs. */
    private fun toParagraphs(lines: List<String>): List<String> {
        val nonBlank = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (nonBlank.isEmpty()) return emptyList()
        val hasBlank = lines.any { it.isBlank() }
        if (hasBlank) {
            val out = mutableListOf<String>()
            var cur = StringBuilder()
            for (line in lines) {
                val t = line.trim()
                if (t.isEmpty()) {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder() }
                } else {
                    cur.append(t)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }
        // No blank lines at all: short fixed-width lines ending mid-sentence
        // (wrapped layout) vs. one-paragraph-per-line exports. Heuristic:
        // if most lines are short AND lack sentence enders, join everything.
        val shortNoEnd = nonBlank.count { it.length <= 55 && !it.endsWithAny("。！？…」』") }
        return if (nonBlank.size > 1 && shortNoEnd.toDouble() / nonBlank.size > 0.5) {
            listOf(nonBlank.joinToString(""))
        } else {
            nonBlank
        }
    }

    private fun String.endsWithAny(suffixes: String): Boolean =
        suffixes.any { this.endsWith(it) }

    /** Paragraphs -> sentence-bounded blocks of roughly BLOCK_TARGET chars. */
    private fun buildBlocks(paragraphs: List<String>): List<String> {
        val blocks = mutableListOf<String>()
        for (p in paragraphs) {
            val sentences = TextSegments.bySentence(p)
            var cur = StringBuilder()
            for (s in sentences) {
                if (cur.isNotEmpty() && (cur.length + s.length > BLOCK_TARGET || cur.length >= BLOCK_MAX)) {
                    blocks.add(cur.toString())
                    cur = StringBuilder()
                }
                cur.append(s)
                if (cur.length >= BLOCK_MAX) {
                    blocks.add(cur.toString())
                    cur = StringBuilder()
                }
            }
            if (cur.isNotEmpty()) blocks.add(cur.toString())
        }
        return blocks
    }
}
