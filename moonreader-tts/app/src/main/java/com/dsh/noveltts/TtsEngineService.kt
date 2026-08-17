package com.dsh.noveltts

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import android.view.KeyEvent
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android TTS engine that Moon Reader selects (modern String-based API).
 *
 * Synthesis cascade per sentence:
 *   1. SQLite sentence cache (instant replay, no network)
 *   2. Edge TTS direct from the phone (free, best quality)
 *   3. DSH server on the Mac (which itself cascades Edge -> Kokoro local)
 * The fallback is fully automatic: if Edge is throttled or broken, the
 * sentence still plays via the local model without any user action.
 *
 * Media integration ("like music"):
 *   - A MediaSession makes earphone/headset buttons (play/pause/stop/next)
 *     control the TTS while it is the active media session.
 *   - Audio focus: starting TTS pauses other media (music gets focus loss);
 *     when other media takes focus, this TTS pauses automatically. No overlap.
 */
class TtsEngineService : TextToSpeechService() {

    companion object {
        private const val TAG = "NovelTtsEngine"
        private const val DEFAULT_VOICE = "zh-CN-YunxiNeural"
        private const val SAMPLE_RATE = 24000
        private const val CHUNK = 16 * 1024
        private const val IDLE_TIMEOUT_MS = 3000L

        /** Test hook: skip Edge entirely and go straight to the server tier. */
        @Volatile
        var forceServerOnly: Boolean = false

        /** The device's native output sample rate (TTS framework plays at this). */
        @Volatile
        var nativeOutputRate: Int = 48000

        val VOICES = listOf(
            Voice(
                "zh-CN-YunxiNeural", Locale.SIMPLIFIED_CHINESE,
                Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet()
            ),
            Voice(
                "zh-CN-YunjianNeural", Locale.SIMPLIFIED_CHINESE,
                Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet()
            ),
            Voice(
                "zh-CN-XiaobeiNeural", Locale.SIMPLIFIED_CHINESE,
                Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet()
            ),
        )

        /** Set by the service on create; used by MediaButtonReceiver to dispatch keys. */
        @Volatile
        private var instance: TtsEngineService? = null

        fun handleMediaKey(keyCode: Int) {
            instance?.onMediaKey(keyCode)
        }
    }

    private lateinit var cache: SentenceCache
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val pausedByUser = AtomicBoolean(false)
    private val pausedByFocus = AtomicBoolean(false)
    private val skipCurrent = AtomicBoolean(false)

    private var audioManager: AudioManager? = null
    private var mediaSession: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { goIdle() }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS) hasFocus = false
                pausedByFocus.set(true)
                updateSessionState()
                Log.i(TAG, "audio focus lost -> TTS paused")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                pausedByFocus.set(false)
                updateSessionState()
                Log.i(TAG, "audio focus regained -> TTS resumed")
            }
        }
    }

    private val isEffectivelyPaused: Boolean
        get() = pausedByUser.get() || pausedByFocus.get()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TtsEngineService.onCreate")
        instance = this
        cache = SentenceCache(this)
        // Apply persisted settings (server URL, debug force-server flag).
        ServerTtsClient.baseUrl = Settings.serverUrl(this)
        forceServerOnly = Settings.forceServer(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupMediaSession()
        // Query the device's native output sample rate; default to 48 kHz.
        try {
            val prop = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            val r = prop?.toIntOrNull()
            if (r != null && r > 0) nativeOutputRate = r
            Log.i(TAG, "native output sample rate = $nativeOutputRate")
        } catch (e: Exception) {
            Log.w(TAG, "failed to query native rate: ${e.message}")
        }
    }

    override fun onDestroy() {
        instance = null
        goIdle()
        try { mediaSession?.release() } catch (_: Exception) {}
        mediaSession = null
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---- media session + audio focus ---------------------------------------

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "NovelTTS").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumeTts()
                override fun onPause() = pauseTts()
                override fun onStop() = stopAll()
                override fun onSkipToNext() { skipCurrent.set(true) }
            })
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setMediaButtonReceiver(
                android.app.PendingIntent.getBroadcast(
                    this@TtsEngineService, 0,
                    Intent(this@TtsEngineService, MediaButtonReceiver::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            setPlaybackState(buildPlaybackState(PlaybackState.STATE_NONE))
        }
    }

    private fun buildPlaybackState(state: Int): PlaybackState =
        PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT
            )
            .setState(state, 0L, 1f)
            .build()

    private fun updateSessionState() {
        mediaSession?.setPlaybackState(
            buildPlaybackState(
                if (isEffectivelyPaused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING
            )
        )
    }

    private fun ensureAudioFocus() {
        val am = audioManager ?: return
        if (hasFocus) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            hasFocus = am.requestAudioFocus(focusRequest!!) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            hasFocus = am.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        Log.i(TAG, "audio focus requested, granted=$hasFocus")
    }

    private fun releaseAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
                am.abandonAudioFocusRequest(focusRequest!!)
            } else {
                am.abandonAudioFocus(focusListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "abandonAudioFocus failed: ${e.message}")
        }
        hasFocus = false
        Log.i(TAG, "audio focus abandoned")
    }

    /** Called by MediaButtonReceiver when a headset button is pressed. */
    fun onMediaKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> resumeTts()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> pauseTts()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (isEffectivelyPaused) resumeTts() else pauseTts()
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                // Stop = pause-and-hold: keep the current utterance open so Moon
                // Reader does NOT advance. Resuming continues from the same spot.
                Log.i(TAG, "media STOP -> hold (Moon Reader must not advance)")
                pauseTts()
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                skipCurrent.set(true)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                Log.i(TAG, "media PREVIOUS ignored (cannot rewind Moon Reader safely)")
            else -> Log.i(TAG, "unhandled media key $keyCode")
        }
    }

    private fun pauseTts() {
        pausedByUser.set(true)
        updateSessionState()
        Log.i(TAG, "TTS paused (user)")
    }

    private fun resumeTts() {
        pausedByUser.set(false)
        updateSessionState()
        Log.i(TAG, "TTS resumed (user)")
    }

    private fun stopAll() {
        Log.i(TAG, "TTS stop all")
        cancelled.set(true)
        pausedByUser.set(false)
        pausedByFocus.set(false)
        updateSessionState()
        mainHandler.postDelayed(idleRunnable, 200)
    }

    private fun goIdle() {
        Log.i(TAG, "goIdle")
        mediaSession?.isActive = false
        mediaSession?.setPlaybackState(buildPlaybackState(PlaybackState.STATE_NONE))
        releaseAudioFocus()
    }

    // ---- language / voice support (modern String-based API) -----------------

    override fun onIsLanguageAvailable(lang: String, country: String, variant: String): Int =
        if (lang == "zh") TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED

    override fun onGetLanguage(): Array<String> = arrayOf("zh", "CN", "")

    override fun onLoadLanguage(lang: String, country: String, variant: String): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetVoices(): List<Voice> = VOICES

    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String =
        DEFAULT_VOICE

    override fun onIsValidVoiceName(voiceName: String): Int =
        if (VOICES.any { it.name == voiceName }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String): Int = onIsValidVoiceName(voiceName)

    // ---- synthesis ----------------------------------------------------------

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString()
        if (text.isNullOrBlank()) {
            Log.w(TAG, "empty text")
            callback.error()
            return
        }
        val voice = request.voiceName?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE
        // Modern API: speech rate/pitch are ints where 100 = normal.
        val ratePct = "${request.speechRate - 100}%"
        val pitchHz = "${request.pitch - 100}Hz"

        cancelled.set(false)
        skipCurrent.set(false)
        // New reading activity: stay "active" and stop the idle timer.
        mainHandler.removeCallbacks(idleRunnable)
        mediaSession?.isActive = true
        ensureAudioFocus()

        executor.execute {
            try {
                synthesizeAndPlay(text, voice, ratePct, pitchHz, callback)
            } catch (e: Exception) {
                Log.e(TAG, "synthesis failed", e)
                try { callback.error() } catch (_: Exception) {}
            }
        }
    }

    private fun synthesizeAndPlay(
        text: String,
        voice: String,
        ratePct: String,
        pitchHz: String,
        callback: SynthesisCallback
    ) {
        val key = SentenceCache.key(voice, ratePct, pitchHz, text)

        // 1) cache
        var audio: ByteArray? = cache.get(key)
        var source = "cache"
        if (audio == null && !forceServerOnly) {
            // 2) Edge TTS direct
            try {
                audio = EdgeTtsClient.synthesize(text, voice, ratePct, pitchHz)
                source = "edge"
            } catch (e: Exception) {
                Log.w(TAG, "Edge failed (${e.message}); trying server")
            }
        } else if (audio == null) {
            Log.w(TAG, "forceServerOnly=true; skipping Edge")
        }
        if (audio == null) {
            // 3) server fallback (server cascades Edge -> Kokoro locally)
            try {
                val (body, _) = ServerTtsClient.synthesize(text, voice, ratePct, pitchHz)
                audio = body
                source = "server"
            } catch (e2: Exception) {
                Log.e(TAG, "server also failed (${e2.message})")
                try { callback.error() } catch (_: Exception) {}
                return
            }
        }
        if (audio != null) {
            cache.put(key, audio)
        }
        if (cancelled.get()) {
            try { callback.error() } catch (_: Exception) {}
            return
        }

        // decode
        val decoded = AudioDecoder.decode(audio!!)
        Log.i(TAG, "decoded: ${decoded.pcm.size / 2} samples, ${decoded.sampleRate}Hz, ${decoded.channels}ch (source=$source)")
        // Feed the framework at the audio's NATIVE rate (24 kHz) — no manual
        // resampling. Android's audio pipeline does band-limited conversion to
        // the device output rate internally, which is higher quality than a
        // linear-interpolation resample (linear upsampling smears tones).
        // The framework creates a STEREO AudioTrack regardless of what we declare,
        // so we upmix mono -> stereo (duplicate each sample) to match its layout;
        // feeding mono into a stereo track would play 2x fast + high-pitched.
        val shorts = decoded.pcm.toShortArray()
        val stereo = if (decoded.channels >= 2) shorts else upmixToStereo(shorts)
        val pcm = toByteArray(stereo)
        Log.i(TAG, "prepared ${pcm.size / 2} shorts @ ${decoded.sampleRate}Hz stereo (no resample)")
        if (cancelled.get()) {
            try { callback.error() } catch (_: Exception) {}
            return
        }

        try {
            callback.start(
                decoded.sampleRate,
                2,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            Log.i(TAG, "playing [$source] ${pcm.size / 2} samples @ ${decoded.sampleRate}Hz stereo")
            updateSessionState()
            val maxChunk = maxOf(1024, callback.maxBufferSize)
            var off = 0
            while (off < pcm.size && !cancelled.get() && !skipCurrent.get()) {
                // We are actively playing: cancel any pending idle timer so the
                // session stays active and audio focus is held across sentences.
                mainHandler.removeCallbacks(idleRunnable)
                // Hold playback while paused (earphone pause or audio-focus loss).
                while (isEffectivelyPaused && !cancelled.get()) {
                    Thread.sleep(50)
                }
                if (cancelled.get()) break
                val n = minOf(maxChunk, pcm.size - off)
                callback.audioAvailable(pcm, off, n)
                off += n
            }
            if (cancelled.get()) {
                // Framework/Moon Reader initiated stop: its queue is already aborted.
                Log.i(TAG, "utterance ended by cancel")
            } else if (skipCurrent.get()) {
                // Media NEXT: end this utterance so Moon Reader advances one sentence.
                Log.i(TAG, "utterance skipped (media NEXT)")
            }
            // In all non-cancel cases, completing the utterance lets the framework
            // continue with the next queued sentence. Moon Reader pre-queues several
            // sentences, so a pause-hold never loses content: the queue stays parked
            // and resumes exactly where it left off.
            if (!cancelled.get()) {
                try { callback.done() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
            try { callback.error() } catch (_: Exception) {}
        } finally {
            mainHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
        }
    }

    override fun onStop() {
        Log.i(TAG, "onStop: cancelling")
        cancelled.set(true)
        mainHandler.postDelayed(idleRunnable, 200)
    }

    // ---- helpers ------------------------------------------------------------

    /** Little-endian short[] -> byte[] (16-bit PCM). */
    private fun toByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val s = shorts[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /** Duplicate each sample so the stream is stereo-interleaved (L=R). */
    private fun upmixToStereo(mono: ShortArray): ShortArray {
        val out = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            out[i * 2] = mono[i]
            out[i * 2 + 1] = mono[i]
        }
        return out
    }

    private fun ByteArray.toShortArray(): ShortArray {
        val shorts = ShortArray(size / 2)
        for (i in shorts.indices) {
            shorts[i] = ((this[i * 2].toInt() and 0xFF) or (this[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return shorts
    }
}
