package com.dsh.noveltts

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import java.util.concurrent.CountDownLatch

/**
 * Main screen: a clean settings UI for the TTS engine.
 *
 *  - Engine status card: shows whether this engine is the system default TTS,
 *    with a shortcut to the system TTS settings.
 *  - Voice card: pick one of the bundled voices.
 *  - Playback card: rate / pitch sliders.
 *  - Server card: the fallback tier URL + a live connectivity test.
 *  - How to use card: Moon Reader setup steps.
 *  - Diagnostics (collapsed): the raw test harness — reads a bundled novel
 *    excerpt with per-sentence latency, plus the "force server only" switch.
 */
class MainActivity : AppCompatActivity() {

    private val ready = CountDownLatch(1)
    private lateinit var engine: TextToSpeech

    // Diagnostics state
    private val sentences = mutableListOf<String>()
    private var index = 0
    private var playing = false
    private var playStart = 0L
    private val logSb = StringBuilder()

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var cacheInfo: TextView
    private lateinit var serverTestResult: TextView
    private lateinit var voiceToggle: MaterialButtonToggleGroup
    private lateinit var rateSlider: Slider
    private lateinit var pitchSlider: Slider
    private lateinit var serverField: TextInputEditText
    private lateinit var forceServer: MaterialSwitch

    // Test hooks (adb): auto-start the excerpt and stop after N sentences.
    private var autoplay = false
    private var limit = Int.MAX_VALUE

    // Pre-render card state
    private lateinit var preText: TextInputEditText
    private lateinit var preStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // TTS engines must answer CHECK_TTS_DATA so the system lists them as
        // available. Return our voices as the "available" set.
        if (intent?.action == TextToSpeech.Engine.ACTION_CHECK_TTS_DATA) {
            val available = ArrayList<String>()
            for (v in TtsEngineService.VOICES) available.add(v.name)
            intent.putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available
            )
            setResult(RESULT_OK, intent)
            finish()
            return
        }

        // Test hooks (adb): --ez autoplay true --ei limit N
        autoplay = intent.getBooleanExtra("autoplay", false)
        val lim = intent.getIntExtra("limit", Int.MAX_VALUE)
        limit = if (lim > 0) lim else Int.MAX_VALUE

        // Load the bundled novel excerpt (used only by Diagnostics).
        val raw = resources.openRawResource(R.raw.novel_sample).bufferedReader().use { it.readText() }
        sentences.addAll(splitSentences(raw))
        if (sentences.isEmpty()) sentences.add("测试句子。")

        // Bind DIRECTLY to our engine package (no need to change system default TTS).
        engine = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready()
            } else {
                appendLog("TTS init failed: $status")
            }
        }, "com.dsh.noveltts")

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val latency = System.currentTimeMillis() - playStart
                runOnUiThread {
                    appendLog("[$utteranceId] audio started after ${latency}ms")
                    speakNext()
                }
            }

            override fun onDone(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {
                runOnUiThread { appendLog("[$utteranceId] ERROR") }
            }
        })

        buildUi()
        refreshCacheInfo()

        // adb hook: pre-render without touching the UI.
        //   adb shell am start -n com.dsh.noveltts/.MainActivity         //       -a com.dsh.noveltts.PRERENDER --ei sampleCount 120
        // adb hook: clear the sentence cache (synchronous so a following
        // force-stop cannot race the delete).
        if (intent?.action == "com.dsh.noveltts.CLEAR_CACHE") {
            SentenceCache(this).clear()
            refreshCacheInfo()
            appendLog("=== cache cleared (adb)")
            android.util.Log.i("MainActivity", "cache cleared (adb)")
        }

        if (intent?.action == "com.dsh.noveltts.PRERENDER") {
            val sampleCount = intent.getIntExtra("sampleCount", 0)
            val text = intent.getStringExtra("text")
            when {
                !text.isNullOrBlank() -> startPreRender(text)
                sampleCount > 0 -> startPreRenderUnits(sentences.take(sampleCount))
            }
        }
    }

    private fun ready() {
        engine.language = Locale.SIMPLIFIED_CHINESE
        runOnUiThread {
            updateEngineStatus()
            if (autoplay) startPlayback()
        }
    }

    /** Shared by the Play button and the adb autoplay test hook. */
    private fun startPlayback() {
        index = 0
        playing = true
        ServerTtsClient.baseUrl = serverField.text?.toString()?.trim()
            ?: Settings.DEFAULT_SERVER_URL
        appendLog("=== Play: rate=${rateSlider.value} pitch=${pitchSlider.value} " +
            "server=${ServerTtsClient.baseUrl} limit=$limit")
        speakNext()
    }

    // ---- UI construction ----------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Branding header
        root.addView(
            TextView(this).apply {
                text = "📖  Novel TTS"
                textSize = 30f
                setPadding(28, 48, 28, 8)
            }
        )
        root.addView(
            TextView(this).apply {
                text = "Natural Chinese audiobook reader for Moon Reader"
                textSize = 14f
                setTextColor(0xFF666666.toInt())
                setPadding(28, 0, 28, 20)
            }
        )

        root.addView(engineStatusCard())
        root.addView(readerCard())
        root.addView(voiceCard())
        root.addView(playbackCard())
        root.addView(serverCard())
        root.addView(preRenderCard())
        root.addView(howToCard())
        root.addView(diagnosticsCard())

        val scroll = ScrollView(this).apply {
            addView(root)
        }
        setContentView(scroll)
    }

    private fun card(title: String, content: LinearLayout): MaterialCardView =
        MaterialCardView(this).apply {
            radius = 20f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(20, 12, 20, 12)
            layoutParams = lp
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 18, 24, 18)
                    addView(
                        TextView(this@MainActivity).apply {
                            text = title
                            textSize = 17f
                            setTypeface(
                                android.graphics.Typeface.DEFAULT,
                                android.graphics.Typeface.BOLD
                            )
                            setPadding(0, 0, 0, 12)
                        }
                    )
                    addView(content)
                }
            )
        }

    private fun engineStatusCard(): MaterialCardView {
        statusView = TextView(this).apply { textSize = 14f }
        val btn = MaterialButton(this).apply {
            text = "Open system TTS settings"
        }
        btn.setOnClickListener {
            try {
                startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
            }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(statusView)
        col.addView(btn)
        return card("Engine status", col)
    }

    /** Standalone audiobook reader (no Moon Reader / no TTS framework). */
    private fun readerCard(): MaterialCardView {
        val btn = MaterialButton(this).apply {
            text = "📖 打开小说阅读器（无需 Moon Reader）"
        }
        btn.setOnClickListener {
            startActivity(Intent(this, ReaderActivity::class.java))
        }
        val note = TextView(this).apply {
            text = "读取本地 .txt 小说：可随时播放/暂停，⟲/⟳ 10 秒，" +
                "耳机键同样控制；锁屏后继续朗读。"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 8, 0, 0)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(btn)
        col.addView(note)
        return card("Reader", col)
    }

    private fun voiceCard(): MaterialCardView {
        voiceToggle = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            val saved = Settings.voice(this@MainActivity)
            for (v in TtsEngineService.VOICES) {
                addView(
                    MaterialButton(this@MainActivity).apply {
                        text = v.name.removePrefix("zh-CN-").removeSuffix("Neural")
                        tag = v.name
                        isChecked = v.name == saved
                    }
                )
            }
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    val b = findViewById<MaterialButton>(checkedId)
                    Settings.setVoice(this@MainActivity, b.tag as String)
                }
            }
        }
        return card("Voice", voiceToggle)
    }

    private fun playbackCard(): MaterialCardView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(TextView(this).apply { text = "Speech rate" })
        rateSlider = Slider(this).apply {
            valueFrom = 0.5f
            valueTo = 2.0f
            stepSize = 0.05f
            value = Settings.rate(this@MainActivity).coerceIn(0.5f, 2.0f)
            addOnChangeListener { _, value, _ ->
                Settings.setRate(this@MainActivity, value)
            }
        }
        col.addView(rateSlider)

        col.addView(TextView(this).apply { text = "Pitch" })
        pitchSlider = Slider(this).apply {
            valueFrom = 0.5f
            valueTo = 2.0f
            stepSize = 0.05f
            value = Settings.pitch(this@MainActivity).coerceIn(0.5f, 2.0f)
            addOnChangeListener { _, value, _ ->
                Settings.setPitch(this@MainActivity, value)
            }
        }
        col.addView(pitchSlider)

        col.addView(
            TextView(this).apply {
                text = "Reader playback speed/rate (applies to the audiobook player and the diagnostics harness)."
                textSize = 12f
                setTextColor(0xFF888888.toInt())
            }
        )
        return card("Playback", col)
    }

    private fun serverCard(): MaterialCardView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val input = TextInputLayout(this).apply {
            hint = "Server URL (fallback tier)"
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        serverField = TextInputEditText(this).apply {
            setText(Settings.serverUrl(this@MainActivity))
        }
        input.addView(serverField)
        col.addView(input)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val testBtn = MaterialButton(this).apply { text = "Test connection" }
        testBtn.setOnClickListener {
            ServerTtsClient.baseUrl = serverField.text?.toString()?.trim()
                ?: Settings.DEFAULT_SERVER_URL
            testServer()
        }
        serverTestResult = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 0, 0, 0)
        }
        row.addView(testBtn)
        row.addView(serverTestResult, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        col.addView(
            TextView(this).apply {
                text = "Used only when Edge TTS fails (throttled/offline). " +
                    "Defaults to your Mac mini over Tailscale."
                textSize = 12f
                setTextColor(0xFF888888.toInt())
            }
        )
        return card("Local fallback server", col)
    }

    private fun howToCard(): MaterialCardView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val steps = listOf(
            "1. Open Moon Reader and open a Chinese novel.",
            "2. Tap the reader bar → long-press the chapters icon → \u201cStart TTS\u201d.",
            "3. In Android Settings → Accessibility → Text-to-speech, choose \u201cNovel TTS\u201d as the engine.",
            "4. Use earphone buttons to pause/play — just like music.",
        )
        for (s in steps) {
            col.addView(
                TextView(this).apply {
                    text = s
                    textSize = 14f
                    setPadding(0, 4, 0, 4)
                }
            )
        }
        return card("How to use in Moon Reader", col)
    }

    /**
     * Pre-render queue: paste a passage and render it into the sentence cache
     * AHEAD of playback, so a later Moon Reader / harness pass over the same
     * text finds cache hits and there is no per-sentence generation wait.
     * Renders on a background thread (PreRenderer) with the same cascade and
     * cache keys as live playback.
     */
    private fun preRenderCard(): MaterialCardView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(
            TextView(this).apply {
                text = "Renders the pasted text into the sentence cache now " +
                    "(no audio plays). Later, reading the SAME text is served " +
                    "from cache: no wait between sentences. Cache keys include " +
                    "voice/rate/pitch — this pre-renders with the current " +
                    "Voice and Playback slider values."
                textSize = 12f
                setTextColor(0xFF666666.toInt())
                setPadding(0, 0, 0, 10)
            }
        )

        val input = TextInputLayout(this).apply {
            hint = "Paste text to pre-render (Chinese passage / chapter)"
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        preText = TextInputEditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            minLines = 5
            maxLines = 8
        }
        input.addView(preText)
        col.addView(input)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val loadBtn = MaterialButton(this).apply { text = "Load sample (120 sents)" }
        loadBtn.setOnClickListener {
            preText.setText(sentences.take(120).joinToString(""))
        }
        val goBtn = MaterialButton(this).apply { text = "Pre-render" }
        goBtn.setOnClickListener {
            val text = preText.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) startPreRender(text)
        }
        val cancelBtn = MaterialButton(this).apply { text = "Cancel" }
        cancelBtn.setOnClickListener { PreRenderer.cancel() }
        row.addView(loadBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(goBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(cancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        preStatus = TextView(this).apply {
            textSize = 13f
            text = "Idle. ~120 sentences take a few minutes over Edge/server."
        }
        col.addView(preStatus)

        return card("Pre-render (queue ahead)", col)
    }

    private fun startPreRender(rawText: String) {
        startPreRenderUnits(TextSegments.bySentence(rawText))
    }

    private fun startPreRenderUnits(units: List<String>) {
        val voice = Settings.voice(this)
        val ratePct = rateSlider.value.let { r ->
            // Mirror the TextToSpeech client + TtsEngineService exactly:
            // client sends round(rate*100); engine keys with (int - 100).
            val pct = Math.round(r * 100f) - 100
            if (pct > 0) "+$pct%" else "$pct%"
        }
        val pitchHz = pitchSlider.value.let { p ->
            val hz = Math.round(p * 100f) - 100
            if (hz > 0) "+${hz}Hz" else "${hz}Hz"
        }
        val nTotal = units.size
        preStatus.text = "Pre-rendering $nTotal sentences (voice=$voice $ratePct $pitchHz)…"
        appendLog("=== Pre-render start: $nTotal sentences, voice=$voice rate=$ratePct pitch=$pitchHz")
        PreRenderer.startUnits(this, units, voice, ratePct, pitchHz,
            object : PreRenderer.Listener {
                override fun onProgress(done: Int, total: Int, cached: Int, fetched: Int, failed: Int, text: String) {
                    if (done % 5 == 0 || done == total) {
                        runOnUiThread {
                            preStatus.text = "Pre-rendering $done/$total (cached=$cached fetched=$fetched failed=$failed)"
                        }
                    }
                }

                override fun onFinished(cancelled: Boolean, done: Int, total: Int, cached: Int, fetched: Int, failed: Int) {
                    runOnUiThread {
                        preStatus.text = if (cancelled) {
                            "Cancelled at $done/$total (cached=$cached fetched=$fetched failed=$failed)"
                        } else {
                            "Done $done/$total — cached=$cached fetched=$fetched failed=$failed"
                        }
                        appendLog("=== Pre-render ${if (cancelled) "cancelled" else "done"}: " +
                            "cached=$cached fetched=$fetched failed=$failed")
                        refreshCacheInfo()
                    }
                }
            })
    }

    private fun diagnosticsCard(): MaterialCardView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        forceServer = MaterialSwitch(this).apply {
            text = "Force server-only (skip Edge)"
            isChecked = Settings.forceServer(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setForceServer(this@MainActivity, checked)
                TtsEngineService.forceServerOnly = checked
            }
        }
        col.addView(forceServer)

        // TTS sentence cache: live size + manual clear (auto-trims above the
        // budget in SentenceCache, but a manual clear is handy after a big read).
        val cacheRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cacheInfo = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 16, 0)
        }
        val cacheClear = MaterialButton(this).apply { text = "Clear cache" }
        cacheClear.setOnClickListener {
            Thread {
                SentenceCache(this@MainActivity).clear()
                refreshCacheInfo()
                appendLog("=== TTS cache cleared")
            }.start()
        }
        cacheRow.addView(cacheInfo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cacheRow.addView(cacheClear)
        col.addView(cacheRow)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val playBtn = MaterialButton(this).apply { text = "Play test excerpt" }
        val stopBtn = MaterialButton(this).apply { text = "Stop" }
        playBtn.setOnClickListener { startPlayback() }
        stopBtn.setOnClickListener {
            playing = false
            engine.stop()
            appendLog("=== Stopped")
        }
        row.addView(playBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(stopBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        logView = TextView(this).apply { textSize = 12f; gravity = Gravity.START }
        val scroll = ScrollView(this).apply { addView(logView) }
        col.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 320
        ))

        return card("Diagnostics", col)
    }

    // ---- engine status ------------------------------------------------------

    private fun refreshCacheInfo() {
        Thread {
            try {
                val (entries, bytes) = SentenceCache(this).stats()
                val mb = bytes / 1024.0 / 1024.0
                runOnUiThread {
                    cacheInfo.text = String.format(
                        java.util.Locale.US,
                        "TTS cache: %.1f MB (%d entries)\nauto-trims above %d MB",
                        mb, entries, SentenceCache.BUDGET_MB
                    )
                }
            } catch (e: Exception) {
                runOnUiThread { cacheInfo.text = "TTS cache: unavailable" }
            }
        }.start()
    }

    private fun updateEngineStatus() {
        val isDefault = engine.defaultEngine == packageName
        statusView.text = buildString {
            append(if (isDefault) "✅ " else "⚠️  ")
            append("This engine is ")
            append(if (isDefault) "the default TTS engine" else "installed but NOT the default")
            append("\nVoices: ")
            append(
                TtsEngineService.VOICES.joinToString(", ") {
                    it.name.removePrefix("zh-CN-").removeSuffix("Neural")
                }
            )
        }
    }

    // ---- server test --------------------------------------------------------

    private fun testServer() {
        serverTestResult.text = "Testing…"
        Thread {
            try {
                val (_, media) = ServerTtsClient.synthesize(
                    "你好世界。", Settings.DEFAULT_VOICE, "+0%", "+0Hz"
                )
                runOnUiThread { serverTestResult.text = "✅ OK ($media)" }
            } catch (e: Exception) {
                runOnUiThread { serverTestResult.text = "❌ ${e.message?.take(60)}" }
            }
        }.start()
    }

    // ---- diagnostics playback -----------------------------------------------

    private fun speakNext() {
        if (!playing) return
        if (index >= sentences.size || index >= limit) {
            appendLog("=== Finished at $index (limit=$limit of ${sentences.size} sentences)")
            playing = false
            return
        }
        val text = sentences[index]
        val id = "s${index++}"
        playStart = System.currentTimeMillis()
        engine.setSpeechRate(rateSlider.value)
        engine.setPitch(pitchSlider.value)
        // QUEUE_ADD: the next sentence is synthesized while the current one plays.
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun appendLog(line: String) {
        logSb.insert(0, "$line\n")
        logView.post { logView.text = logSb.toString() }
    }

    override fun onDestroy() {
        try {
            engine.stop()
            engine.shutdown()
        } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun splitSentences(raw: String): List<String> = TextSegments.bySentence(raw)
}
