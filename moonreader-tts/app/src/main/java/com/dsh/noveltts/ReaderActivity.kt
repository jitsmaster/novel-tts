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
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("无法打开文件")
                var text: String? = null
                try {
                    text = Charset.forName("UTF-8").newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                } catch (_: Exception) {
                    text = String(bytes, Charset.forName("GB18030"))
                }
                val n = NovelParser.parse(text)
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
            openBook.launch(arrayOf("text/*", "text/plain", "application/octet-stream"))
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
        // Two rows so the icon buttons are never clipped.
        row(prevCh to 1f, backPg to 1f, playBtn to 1.25f, fwdPg to 1f, nextCh to 1f)
        row(openB to 2f, stopB to 1f)

        setContentView(root)
        titleView.text = "📖 小说阅读器"
    }

    // ---- rendering --------------------------------------------------------

    private fun renderChapter() {
        val n = novel ?: return
        val ch = chapter ?: return
        titleView.text = "${n.title}\n${ch.title}"
        statusView.text = "第 ${chapterIdx + 1}/${n.chapters.size} 章"
        renderBlockHighlight()
    }

    private fun renderBlockHighlight() {
        val ch = chapter ?: return
        val sb = StringBuilder()
        val starts = IntArray(ch.blocks.size)
        for (i in ch.blocks.indices) {
            starts[i] = sb.length
            sb.append(ch.blocks[i]).append("\n\n")
        }
        val sp = SpannableString(sb.toString())
        val b = blockIdx.coerceIn(0, ch.blocks.size - 1)
        val end = if (b + 1 < ch.blocks.size) starts[b + 1] else sb.length
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
