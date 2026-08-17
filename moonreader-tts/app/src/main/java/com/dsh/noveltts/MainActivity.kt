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
    private lateinit var serverTestResult: TextView
    private lateinit var voiceToggle: MaterialButtonToggleGroup
    private lateinit var rateSlider: Slider
    private lateinit var pitchSlider: Slider
    private lateinit var serverField: TextInputEditText
    private lateinit var forceServer: MaterialSwitch

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
    }

    private fun ready() {
        engine.language = Locale.SIMPLIFIED_CHINESE
        runOnUiThread { updateEngineStatus() }
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
        root.addView(voiceCard())
        root.addView(playbackCard())
        root.addView(serverCard())
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
                text = "These apply to the test in Diagnostics. Moon Reader's own " +
                    "TTS panel (Volume/Pitch/Speed) overrides them while reading."
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

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val playBtn = MaterialButton(this).apply { text = "Play test excerpt" }
        val stopBtn = MaterialButton(this).apply { text = "Stop" }
        playBtn.setOnClickListener {
            index = 0
            playing = true
            ServerTtsClient.baseUrl = serverField.text?.toString()?.trim()
                ?: Settings.DEFAULT_SERVER_URL
            appendLog("=== Play: rate=${rateSlider.value} pitch=${pitchSlider.value} " +
                "server=${ServerTtsClient.baseUrl}")
            speakNext()
        }
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
        if (index >= sentences.size) {
            appendLog("=== Finished (${sentences.size} sentences)")
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

    private fun splitSentences(raw: String): List<String> {
        val regex = Regex("[^。！？；\\n]+[。！？；]?")
        return regex.findAll(raw).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
    }
}
