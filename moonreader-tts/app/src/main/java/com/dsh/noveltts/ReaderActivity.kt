package com.dsh.noveltts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Standalone audiobook reader — no Moon Reader, no Android TTS framework.
 * Playback runs in [AudioBookService] (own AudioTrack + MediaSession), so:
 *  - play / pause at ANY time, mid-paragraph included
 *  - previous/next “page” (paragraph block) jump buttons — also the
 *    earphone rewind/forward/next/prev mapping
 *  - keeps reading with the screen off (foreground service)
 *  - position saved per book and restored on reopen
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "reader_prefs"
        private const val KEY_URI = "book_uri"
        private const val KEY_NAME = "book_name"
        private const val KEY_CH = "chapter"
        private const val KEY_BLOCK = "block"
    }

    private val openBook = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) { persistUri(uri); loadBook(uri) } }

    private var novel: Novel? = null
    private var chapterIdx = 0
    private var blockIdx = 0
    private var bookUri: Uri? = null
    private var bookName = ""

    // mirrors of the service state
    private var servicePlaying = false
    private var serviceLoaded = false      // service has the current chapter queued
    private var autoAdvance = true

    private var autoplay = false
    private var sourceSample = false
    private var testFile: String? = null
    private var testLimit = Int.MAX_VALUE
    private var testSpoken = 0

    private lateinit var titleView: TextView
    private lateinit var chapterView: TextView
    private lateinit var statusView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var playBtn: MaterialButton

    private val chapter: Chapter? get() = novel?.chapters?.getOrNull(chapterIdx)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            servicePlaying = intent.getBooleanExtra(AudioBookService.EXTRA_PLAYING, false)
            val reason = intent.getStringExtra(AudioBookService.EXTRA_REASON) ?: ""
            val block = intent.getIntExtra(AudioBookService.EXTRA_BLOCK, blockIdx)
            val pos = intent.getLongExtra(AudioBookService.EXTRA_POS, 0)
            val dur = intent.getLongExtra(AudioBookService.EXTRA_DUR, 0)
            runOnUiThread {
                when (reason) {
                    "block-start" -> {
                        blockIdx = block
                        renderBlockHighlight()
                        maybeSave()
                        testSpoken++
                        if (testSpoken >= testLimit) {
                            AudioBookService.control(this@ReaderActivity, AudioBookService.ACTION_STOP)
                            statusView.text = "=== test limit reached"
                        }
                    }
                    "finished" -> {
                        serviceLoaded = false
                        if (autoAdvance) startNextChapter()
                    }
                    "stopped" -> {
                        serviceLoaded = false
                        servicePlaying = false
                    }
                }
                refreshPlayButton()
                updatePosLine(pos, dur)
                refreshPlayButton()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoplay = intent.getBooleanExtra("autoplay", false)
        sourceSample = intent.getBooleanExtra("sample", false)
        testFile = intent.getStringExtra("file")
        testLimit = intent.getIntExtra("limit", Int.MAX_VALUE)
        buildUi()
        val filter = IntentFilter(AudioBookService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        val saved = prefs().getString(KEY_URI, null)
        when {
            testFile != null -> loadTestFile(testFile!!)
            sourceSample -> loadSample()
            saved != null -> {
                val uri = Uri.parse(saved)
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                loadBook(uri)
            }
            autoplay -> loadSample()
            else -> statusView.text = "打开一个 .txt 小说即可收听；播放键/耳机键随时可用。"
        }
    }

    private fun loadTestFile(name: String) {
        statusView.text = "解析文件 $name…"
        Thread {
            try {
                val f = java.io.File(filesDir, name)
                val uriKey = "file:$name"
                val t0 = System.currentTimeMillis()
                val bytes = f.readBytes()
                val cached = NovelCache.load(this, uriKey, bytes.size.toLong())
                val n = cached ?: parseBytes(bytes, name)?.also {
                    NovelCache.save(this, uriKey, bytes.size.toLong(), it)
                }
                android.util.Log.i("ReaderLoad", "file load ${n?.chapters?.size} chapters in " +
                    "${System.currentTimeMillis() - t0}ms " +
                    (if (cached != null) "(cache)" else "(parsed)"))
                if (n == null) {
                    runOnUiThread { statusView.text = "无法解析文件（非文本/EPUB）" }
                } else {
                    runOnUiThread {
                        onParsed(n, name)
                        if (autoplay) startChapter()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { statusView.text = "打开失败: ${e.message}" }
            }
        }.start()
    }

    private fun loadSample() {
        statusView.text = "解析样本…"
        Thread {
            val raw = resources.openRawResource(R.raw.novel_sample)
                .bufferedReader().use { it.readText() }
            val n = NovelParser.parse(raw)
            runOnUiThread {
                onParsed(n, "三國志演義(樣本)")
                if (autoplay || sourceSample) startChapter()
            }
        }.start()
    }

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun persistUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
        bookUri = uri
        bookName = uri.lastPathSegment?.substringAfterLast('/')?.substringBefore('_') ?: "书"
        prefs().edit().putString(KEY_URI, uri.toString()).putString(KEY_NAME, bookName)
            .putInt(KEY_CH, 0).putInt(KEY_BLOCK, 0).apply()
    }

    private fun loadBook(uri: Uri) {
        statusView.text = "解析中…"
        bookUri = uri
        if (bookName.isBlank()) {
            bookName = prefs().getString(KEY_NAME, null)
                ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBefore('_') ?: "书"
        }
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("无法打开文件")
                val t0 = System.currentTimeMillis()
                val cached = NovelCache.load(this, uri.toString(), bytes.size.toLong())
                val n: Novel? = cached ?: parseBytes(bytes, bookName)?.also {
                    NovelCache.save(this, uri.toString(), bytes.size.toLong(), it)
                }
                android.util.Log.i("ReaderLoad", "loaded ${n?.chapters?.size} chapters in " +
                    "${System.currentTimeMillis() - t0}ms " +
                    (if (cached != null) "(cache)" else "(parsed)"))
                if (n == null) {
                    runOnUiThread { statusView.text = "无法解析（仅支持 .txt 与 .epub）" }
                    return@Thread
                }
                val savedUri = prefs().getString(KEY_URI, null)
                runOnUiThread {
                    if (uri.toString() == savedUri) {
                        val nc = n.chapters.size
                        chapterIdx = prefs().getInt(KEY_CH, 0).coerceIn(0, (nc - 1).coerceAtLeast(0))
                        blockIdx = prefs().getInt(KEY_BLOCK, 0)
                    }
                    onParsed(n, bookName)
                    if (autoplay) startChapter()
                }
            } catch (e: Exception) {
                runOnUiThread { statusView.text = "打开失败: ${e.message}" }
            }
        }.start()
    }

    /** Sniff: EPUB (zip magic) -> EpubParser, else decode txt (UTF-8/GB18030). */
    private fun parseBytes(bytes: ByteArray, name: String): Novel? {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val r = EpubParser.parse(bytes, name)
            if (r.error != null) {
                runOnUiThread { statusView.text = r.error }
                return null
            }
            return r.novel
        }
        var text: String? = null
        try {
            text = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            text = String(bytes, Charset.forName("GB18030"))
        }
        return NovelParser.parse(text)
    }

    private fun onParsed(n: Novel, name: String) {
        novel = n
        bookName = name
        prefs().edit().putString(KEY_NAME, name).apply()
        renderChapter()
    }

    // ---- UI ---------------------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        titleView = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(20, 24, 20, 4)
        }
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF777777.toInt())
            setPadding(20, 0, 20, 8)
        }
        root.addView(titleView)
        root.addView(statusView)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        chapterView = TextView(this).apply {
            textSize = 20f
            setLineSpacing(10f, 1f)
            setPadding(24, 12, 24, 24)
            setTextIsSelectable(true)
        }
        scrollView.addView(chapterView)
        root.addView(scrollView)

        fun row(vararg specs: Pair<MaterialButton, Float>) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 0, 8, 12)
            }
            for ((b, w) in specs) {
                bar.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w))
            }
            root.addView(bar)
        }

        // Icon-only transport buttons (emoji glyphs, no words) so they never
        // overflow. Order: open, prev chapter, -10s, play/pause, +10s, next
        // chapter, stop — mirroring earphone control mapping.
        fun iconBtn(glyph: String, desc: String, weight: Float, cb: () -> Unit) =
            MaterialButton(this).apply {
                text = glyph
                textSize = 26f
                isAllCaps = false
                minWidth = 0
                minHeight = 0
                contentDescription = desc
                setOnClickListener { cb() }
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, weight
                )
            }

        val openB = iconBtn("📂", "打开小说", 1f) {
            openBook.launch(arrayOf("application/epub+zip", "text/*", "text/plain", "application/octet-stream"))
        }
        val prevCh = iconBtn("⏮", "上一章", 1f) { jumpChapter(chapterIdx - 1) }
        val backPg = iconBtn("⏪", "上一段", 1f) { AudioBookService.control(this, AudioBookService.ACTION_PREV_BLOCK) }
        playBtn = iconBtn("▶", "播放/暂停", 1f) { togglePlay() }
        val fwdPg = iconBtn("⏩", "下一段", 1f) { AudioBookService.control(this, AudioBookService.ACTION_NEXT_BLOCK) }
        val nextCh = iconBtn("⏭", "下一章", 1f) { jumpChapter(chapterIdx + 1) }
        val stopB = iconBtn("⏹", "停止", 1f) {
            AudioBookService.control(this, AudioBookService.ACTION_STOP)
            autoAdvance = false
        }
        // Speed dial (also the persisted rate that MainActivity's slider shows).
        val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        var speedIdx = speeds.indexOfFirst { kotlin.math.abs(it - Settings.rate(this)) < 0.01f }
            .takeIf { it >= 0 } ?: 1
        val speedBtn = MaterialButton(this).apply {
            text = fmtSpeed(speeds[speedIdx])
            textSize = 18f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            contentDescription = "朗读速度"
            setOnClickListener {
                speedIdx = (speedIdx + 1) % speeds.size
                Settings.setRate(this@ReaderActivity, speeds[speedIdx])
                text = fmtSpeed(speeds[speedIdx])
                AudioBookService.control(this@ReaderActivity, AudioBookService.ACTION_SPEED)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Two rows so the icon buttons are never clipped.
        row(prevCh to 1f, backPg to 1f, playBtn to 1.25f, fwdPg to 1f, nextCh to 1f)
        row(openB to 1.4f, speedBtn to 1f, stopB to 1f)

        setContentView(root)
        titleView.text = "📖 小说阅读器"
    }

    private fun fmtSpeed(s: Float): String {
        val r = String.format(java.util.Locale.US, "%.2f", s).trimEnd('0').trimEnd('.')
        return "$r×"
    }

    // ---- rendering --------------------------------------------------------

    // Display text for the current chapter is built ONCE per chapter and
    // reused for every highlight update (block change) — rebuilding the whole
    // chapter string per block was wasteful.
    private var chapterDisplayText: String? = null
    private var chapterDisplayStarts: IntArray? = null

    private fun buildChapterDisplay(ch: Chapter): Pair<String, IntArray> {
        val sb = StringBuilder()
        val starts = IntArray(ch.blocks.size)
        for (i in ch.blocks.indices) {
            starts[i] = sb.length
            sb.append(ch.blocks[i]).append("\n\n")
        }
        return sb.toString() to starts
    }

    private fun renderChapter() {
        val n = novel ?: return
        val ch = chapter ?: return
        chapterDisplayText = null
        chapterDisplayStarts = null
        titleView.text = "${n.title}\n${ch.title}"
        statusView.text = "第 ${chapterIdx + 1}/${n.chapters.size} 章"
        renderBlockHighlight()
    }

    private fun renderBlockHighlight() {
        val ch = chapter ?: return
        if (chapterDisplayText == null) {
            val (t, st) = buildChapterDisplay(ch)
            chapterDisplayText = t
            chapterDisplayStarts = st
        }
        val text = chapterDisplayText ?: return
        val starts = chapterDisplayStarts ?: return
        val sp = SpannableString(text)
        val b = blockIdx.coerceIn(0, ch.blocks.size - 1)
        val end = if (b + 1 < ch.blocks.size) starts[b + 1] else text.length
        sp.setSpan(BackgroundColorSpan(0x30FFB300.toInt()), starts[b], end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        chapterView.setText(sp, TextView.BufferType.SPANNABLE)
        chapterView.post {
            try {
                val layout = chapterView.layout ?: return@post
                val line = layout.getLineForOffset(starts[b])
                scrollView.scrollTo(0, (layout.getLineTop(line) - 180).coerceAtLeast(0))
            } catch (_: Exception) {}
        }
    }

    private fun updatePosLine(pos: Long, dur: Long) {
        val mm = pos / 60000; val ss = (pos % 60000) / 1000
        val dm = dur / 60000; val ds = (dur % 60000) / 1000
        val ch = chapter
        statusView.text = buildString {
            append("第 ${chapterIdx + 1}/${novel?.chapters?.size ?: 0} 章")
            if (ch != null) append("  ·  块 ${blockIdx + 1}/${ch.blocks.size}")
            append("  ·  %d:%02d / %d:%02d".format(mm, ss, dm, ds))
        }
    }

    private fun refreshPlayButton() {
        playBtn.text = if (servicePlaying) "⏸" else "▶"
    }

    // ---- playback ---------------------------------------------------------

    private fun startChapter() {
        val n = novel ?: return
        val ch = chapter ?: return
        if (ch.blocks.isEmpty()) return
        autoAdvance = true
        serviceLoaded = true
        // warm the audio cache for the whole chapter in the background
        prewarm(chapterIdx)
        val from = blockIdx.coerceIn(0, ch.blocks.size - 1)
        AudioBookService.start(this, "${n.title} · ${ch.title}", ch.blocks, from)
    }

    private fun togglePlay() {
        if (novel == null) return
        if (serviceLoaded) {
            AudioBookService.control(this,
                if (servicePlaying) AudioBookService.ACTION_PAUSE else AudioBookService.ACTION_PLAY)
        } else {
            startChapter()
        }
    }

    private fun jumpChapter(k: Int) {
        val n = novel ?: return
        if (k < 0 || k >= n.chapters.size) return
        AudioBookService.control(this, AudioBookService.ACTION_STOP)
        autoAdvance = false
        chapterIdx = k
        blockIdx = 0
        renderChapter()
        save()
        startChapter()
    }

    private fun startNextChapter() {
        val n = novel ?: return
        if (chapterIdx + 1 >= n.chapters.size) {
            statusView.text = "全书播放完毕"
            return
        }
        chapterIdx++
        blockIdx = 0
        renderChapter()
        save()
        startChapter()
    }

    private fun prewarm(k: Int) {
        val n = novel ?: return
        val units = mutableListOf<String>()
        for (i in k until n.chapters.size) {
            for (b in n.chapters[i].blocks) {
                if (units.size >= 600) break
                units.add(b)
            }
            if (units.size >= 600) break
        }
        if (units.isNotEmpty()) {
            PreRenderer.startUnits(applicationContext, units,
                Settings.voice(this), "+0%", "+0Hz", null)
        }
    }

    // ---- persistence ------------------------------------------------------

    private var lastSaved = 0L

    private fun maybeSave() {
        val now = System.currentTimeMillis()
        if (now - lastSaved < 5000) return
        lastSaved = now
        save()
    }

    private fun save() {
        if (novel == null) return
        prefs().edit()
            .putString(KEY_URI, bookUri?.toString() ?: prefs().getString(KEY_URI, null))
            .putString(KEY_NAME, bookName)
            .putInt(KEY_CH, chapterIdx)
            .putInt(KEY_BLOCK, blockIdx)
            .apply()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    override fun onDestroy() {
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        save()
        super.onDestroy()
    }
}
