package com.dsh.noveltts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
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
 *  - Voice card: pick one of the bundled voices (radio group, so the current
 *    choice is unambiguous) + whether the app's pick overrides the reader's.
 *  - Playback card: rate / pitch sliders.
 *  - Service card: which TTS backend is active right now (server / Edge /
 *    Google), why, and buttons to pick one by hand — the automatic rules still
 *    apply from there.
 *  - Server card: the preferred tier URL + a live connectivity test.
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
    private lateinit var voiceGroup: RadioGroup
    private lateinit var forceVoiceSwitch: MaterialSwitch
    private var refreshingVoice = false

    /** Test hook (adb): makes the client request a voice we did not pick. */
    private var clientVoiceOverride: String? = null
    private lateinit var rateSlider: Slider
    private lateinit var pitchSlider: Slider
    private lateinit var serverField: TextInputEditText

    // Service (tier) card state
    private lateinit var serviceNow: TextView
    private lateinit var serviceDetail: TextView
    private lateinit var tierGroup: RadioGroup
    private lateinit var pinSwitch: MaterialSwitch
    private lateinit var probeResult: TextView
    private var refreshingService = false
    private val tierReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runOnUiThread { refreshServiceCard() }
        }
    }

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

        // Deploy hooks (adb), so a freshly installed phone can be pointed at a
        // TTS server and switched to server-only mode without touching its UI:
        //   --es server http://host:8321 --ez forceServer true
        // Written to Settings here, before buildUi() reads them into the
        // widgets. TtsEngineService reads Settings when the service is created,
        // so force-stop the app after setting forceServer.
        intent.getStringExtra("server")?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
            Settings.setServerUrl(this, url)
            ServerTtsClient.baseUrl = url
            android.util.Log.i("MainActivity", "server URL set from intent: $url")
        }
        if (intent.hasExtra("forceServer")) {
            val forced = intent.getBooleanExtra("forceServer", false)
            Settings.setForceServer(this, forced)
            android.util.Log.i("MainActivity", "forceServer set from intent: $forced")
        }

        // Tier state: restore the persisted choice and arm the recovery pings.
        TtsRouter.init(this)
        // Only when no pinned tier was restored (see TtsEngineService).
        if (!TtsRouter.pinned && Settings.forceServer(this)) {
            TtsRouter.select(this, TtsRouter.Tier.SERVER, pin = true)
        }

        // Tier test hooks (adb), mirroring the autoplay/limit hooks below:
        //   --es tier edge --ez pin true   pick a tier by hand (and lock it)
        //   --ei probeSeconds 15           shorten the recovery-ping interval
        //   --ez probeNow true             run one recovery ping right now
        //   -a com.dsh.noveltts.STATUS     log the router state and exit the hook
        if (intent.hasExtra("probeSeconds")) {
            val s = intent.getIntExtra("probeSeconds", 0)
            TtsRouter.probeIntervalOverrideMs = if (s > 0) s * 1000L else 0L
            android.util.Log.i("MainActivity", "probe interval override: ${s}s")
        }
        TtsRouter.Tier.from(intent.getStringExtra("tier"))?.let { t ->
            TtsRouter.select(this, t, pin = intent.getBooleanExtra("pin", false))
            android.util.Log.i("MainActivity", "tier set from intent: ${t.label} pin=${TtsRouter.pinned}")
        }
        // Voice test hooks: set the app's voice, pretend the reading client
        // (Moon Reader) asks for `--es clientVoice <name>`, and/or flip the
        // "app voice wins" switch.
        intent.getStringExtra("voice")?.trim()?.takeIf { it.isNotEmpty() }?.let { v ->
            if (TtsEngineService.VOICES.any { it.name == v }) {
                Settings.setVoice(this, v)
                android.util.Log.i("MainActivity", "voice set from intent: $v")
            }
        }

        // `--es clientVoice <name>`, and/or flip the "app voice wins" switch.
        intent.getStringExtra("clientVoice")?.trim()?.takeIf { it.isNotEmpty() }?.let { v ->
            clientVoiceOverride = v
            android.util.Log.i("MainActivity", "client voice forced to $v (test hook)")
        }
        if (intent.hasExtra("forceVoice")) {
            val f = intent.getBooleanExtra("forceVoice", true)
            Settings.setForceVoice(this, f)
            android.util.Log.i("MainActivity", "forceVoice set from intent: $f")
        }
        if (intent.getBooleanExtra("probeNow", false)) {
            Thread {
                val res = TtsRouter.probeNow(this)
                android.util.Log.i("MainActivity", "probe now -> $res")
            }.start()
        }
        if (intent?.action == "com.dsh.noveltts.STATUS") {
            android.util.Log.i("MainActivity", "=== TIER STATUS ===")
            for (line in TtsRouter.snapshot().split('\n')) {
                android.util.Log.i("MainActivity", "TIER| $line")
            }
        }
        val tierFilter = IntentFilter(TtsRouter.ACTION_TIER)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(tierReceiver, tierFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(tierReceiver, tierFilter)
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

    /**
     * Re-read every persisted choice when this screen comes back to the front.
     *
     * The reader (and Android's own TTS settings) can change the voice or the
     * service while this activity sits in the background; the radio groups used
     * to keep whatever state they were BUILT with, so the screen showed a
     * selection that no longer matched the audio. The automatic tier rules can
     * also switch service on their own while we are away.
     */
    override fun onResume() {
        super.onResume()
        if (!::voiceGroup.isInitialized) return
        selectSavedVoice()                                  // voice radios
        forceVoiceSwitch.isChecked = Settings.forceVoice(this)
        refreshServiceCard()                                // service radios + lock
        applyClientVoice()                                  // in-app client follows too
        appendLog("=== resumed: voice=${Settings.voice(this)} " +
            "tier=${TtsRouter.home.label} pinned=${TtsRouter.pinned}")
    }

    private fun ready() {
        engine.language = Locale.SIMPLIFIED_CHINESE
        runOnUiThread {
            applyClientVoice()
            updateEngineStatus()
            if (autoplay) startPlayback()
        }
    }

    /**
     * Tells the framework client which voice to request. Without this the client
     * keeps the default voice it resolved at startup, so changing the voice in
     * the UI did not change what was spoken (the engine only ever saw Yunxi).
     */
    private fun applyClientVoice() {
        val name = clientVoiceOverride ?: Settings.voice(this)
        val v = TtsEngineService.VOICES.firstOrNull { it.name == name } ?: return
        try {
            val res = engine.setVoice(v)
            android.util.Log.i("MainActivity", "client voice -> ${v.name} (result=$res)")
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "setVoice failed: ${e.message}")
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
        root.addView(serviceCard())
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

    /**
     * Which TTS service is in use right now, why, and manual switches.
     *
     * The automatic chain (TtsRouter) always protects reading: server first,
     * then Edge after 3 consecutive server failures, then the Google TTS engine
     * after 5 consecutive Edge failures — and the failed tier is pinged back
     * online (server every 10 min while Edge runs, Edge every 5 min while
     * Google runs). A manual pick becomes the new home tier and the same rules
     * apply from there; "lock" freezes the pick and stops all probing.
     */
    private fun serviceCard(): MaterialCardView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        serviceNow = TextView(this).apply {
            textSize = 17f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        col.addView(serviceNow)

        serviceDetail = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 6, 0, 12)
        }
        col.addView(serviceDetail)

        tierGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            for (t in TtsRouter.Tier.values()) {
                addView(
                    MaterialRadioButton(this@MainActivity).apply {
                        id = View.generateViewId()
                        text = "${t.icon} ${t.cn}"
                        textSize = 14f
                        tag = t
                        setPadding(0, 10, 0, 10)
                    }
                )
            }
            setOnCheckedChangeListener { group, checkedId ->
                if (refreshingService) return@setOnCheckedChangeListener
                val t = group.findViewById<MaterialRadioButton>(checkedId)?.tag as? TtsRouter.Tier
                    ?: return@setOnCheckedChangeListener
                TtsRouter.select(this@MainActivity, t, pin = pinSwitch.isChecked)
                refreshServiceCard()
            }
        }
        col.addView(tierGroup)

        pinSwitch = MaterialSwitch(this).apply {
            text = "锁定当前服务（关闭自动切换与恢复探测）"
            setOnCheckedChangeListener { _, checked ->
                if (refreshingService) return@setOnCheckedChangeListener
                if (checked) TtsRouter.select(this@MainActivity, TtsRouter.home, pin = true)
                else TtsRouter.unpin(this@MainActivity)
                refreshServiceCard()
            }
        }
        col.addView(pinSwitch)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val probeBtn = MaterialButton(this).apply {
            text = "立即探测"
            textSize = 13f
            isAllCaps = false
        }
        probeBtn.setOnClickListener {
            probeResult.text = "探测中…"
            Thread {
                val r = TtsRouter.probeNow(this@MainActivity)
                runOnUiThread {
                    probeResult.text = r
                    refreshServiceCard()
                }
            }.start()
        }
        probeResult = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 0, 0, 0)
        }
        row.addView(probeBtn)
        row.addView(
            probeResult,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        col.addView(row)

        col.addView(
            TextView(this).apply {
                text = "自动规则：服务器优先 → 连续失败 3 次转 Edge → Edge 连续失败 5 次转 Google。\n" +
                    "恢复探测：在 Edge 时每 10 分钟探测服务器；在 Google 时每 5 分钟探测 Edge，恢复即切回。\n" +
                    "手动选择后同样适用这些规则；锁定时不自动切换。\n" +
                    "兜底引擎：" + GoogleTtsClient.describe(this@MainActivity)
                textSize = 11f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 10, 0, 0)
            }
        )

        refreshServiceCard()
        return card("语音服务（当前 / 手动切换）", col)
    }

    /** Repaints the service card from the live TtsRouter state. */
    private fun refreshServiceCard() {
        refreshingService = true
        try {
            val lines = TtsRouter.snapshot().split("\n")
            serviceNow.text = lines.firstOrNull() ?: ""
            serviceDetail.text = lines.drop(1).joinToString("\n")
            for (i in 0 until tierGroup.childCount) {
                val b = tierGroup.getChildAt(i) as? MaterialRadioButton ?: continue
                val t = b.tag as? TtsRouter.Tier ?: continue
                // A tier with no engine on this device (no Google TTS / Pico)
                // must not look selectable.
                b.isEnabled = TtsRouter.available(this, t)
                if (t == TtsRouter.home) {
                    tierGroup.check(b.id)
                }
            }
            pinSwitch.isChecked = TtsRouter.pinned
        } finally {
            refreshingService = false
        }
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
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // A RadioGroup, not a button toggle group: the current voice must be
        // obvious at a glance and exactly one is always checked.
        voiceGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            for (v in TtsEngineService.VOICES) {
                addView(
                    MaterialRadioButton(this@MainActivity).apply {
                        id = View.generateViewId()
                        text = voiceLabel(v.name)
                        textSize = 14f
                        tag = v.name
                        setPadding(0, 10, 0, 10)
                    }
                )
            }
            setOnCheckedChangeListener { group, checkedId ->
                if (refreshingVoice) return@setOnCheckedChangeListener
                val name = group.findViewById<MaterialRadioButton>(checkedId)?.tag as? String
                    ?: return@setOnCheckedChangeListener
                Settings.setVoice(this@MainActivity, name)
                // Tapping a voice means "I want to HEAR this one". With the
                // override off the engine still followed whatever voice the
                // reading client (Moon Reader) asked for, so the pick looked
                // like it changed nothing at all.
                if (!Settings.forceVoice(this@MainActivity)) {
                    Settings.setForceVoice(this@MainActivity, true)
                    forceVoiceSwitch.isChecked = true
                    appendLog("=== voice override switched ON (a voice was picked)")
                }
                applyClientVoice()
                appendLog("=== voice -> $name")
            }
        }
        col.addView(voiceGroup)

        forceVoiceSwitch = MaterialSwitch(this@MainActivity).apply {
            text = "以此处选择的语音为准（覆盖阅读器内部选择）"
            textSize = 13f
            isChecked = Settings.forceVoice(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setForceVoice(this@MainActivity, checked)
                applyClientVoice()
            }
        }
        col.addView(forceVoiceSwitch)

        col.addView(
            TextView(this).apply {
                text = "语音由朗读引擎按这里的设置合成。关闭上面的开关时，如果阅读器自己指定了" +
                    "另一个语音（同为 Yunxi / Yunjian / Xiaobei），则跟随阅读器。"
                textSize = 11f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 6, 0, 0)
            }
        )

        selectSavedVoice()
        return card("Voice（语音）", col)
    }

    private fun voiceLabel(name: String): String = when (name) {
        "zh-CN-YunxiNeural" -> "Yunxi（年轻男声 · 默认）"
        "zh-CN-YunjianNeural" -> "Yunjian（沉稳男声）"
        "zh-CN-XiaobeiNeural" -> "Xiaobei（成熟女声）"
        else -> name.removePrefix("zh-CN-").removeSuffix("Neural")
    }

    /** Checks the persisted voice; always leaves exactly one radio selected. */
    private fun selectSavedVoice() {
        refreshingVoice = true
        try {
            val saved = Settings.voice(this)
            for (i in 0 until voiceGroup.childCount) {
                val b = voiceGroup.getChildAt(i) as? MaterialRadioButton ?: continue
                if (b.tag == saved) {
                    voiceGroup.check(b.id)
                    return
                }
            }
            (voiceGroup.getChildAt(0) as? MaterialRadioButton)?.let { voiceGroup.check(it.id) }
        } finally {
            refreshingVoice = false
        }
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
            hint = "Server URL (首选服务)"
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
                text = "首选服务。不可达时自动回退到 Edge（3 次失败）→ Google TTS（5 次失败），" +
                    "并在后台探测恢复。默认是你的 Mac mini（Tailscale）。"
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
        // Mirror the TextToSpeech client + TtsEngineService exactly: the client
        // sends round(rate*100), the engine keys with (int - 100). The value
        // must be signed, including the neutral "+0%"/"+0Hz"
        // (see EdgeTtsClient.signedPct).
        val ratePct = EdgeTtsClient.signedPct(Math.round(rateSlider.value * 100f) - 100)
        val pitchHz = EdgeTtsClient.signedHz(Math.round(pitchSlider.value * 100f) - 100)
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

        col.addView(
            TextView(this).apply {
                text = "服务层级（服务器 / Edge / Google）在顶部的“语音服务”卡片中切换。"
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 0, 0, 10)
            }
        )

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
            unregisterReceiver(tierReceiver)
        } catch (e: Exception) {}
        try {
            engine.stop()
            engine.shutdown()
        } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun splitSentences(raw: String): List<String> = TextSegments.bySentence(raw)
}
