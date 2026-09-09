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
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import android.view.KeyEvent
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private val cancelled = AtomicBoolean(false)
    /** Bumped on every stop; in-flight/queued utterances with an older value abort. */
    private val generation = AtomicLong(0)
    private val pausedByUser = AtomicBoolean(false)
    private val pausedByFocus = AtomicBoolean(false)
    private val skipCurrent = AtomicBoolean(false)
    /**
     * True from the moment an utterance is handed to us until it is done —
     * including its fetch/decode phase, so the multi-second silent gap between
     * sentences still counts as an active stream (the OS then routes a
     * play/pause press as PAUSE, which parks the next sentence instead of being
     * dropped as a no-op "play").
     */
    private val utteranceActive = AtomicBoolean(false)
    /**
     * True while a reading stream is parked in this process (an utterance is
     * current or queued behind a pause-hold). A pause-hold is only resumable
     * while engaged; a fresh utterance after the engine went idle is a new
     * reading session and clears stale pause state.
     */
    private val engaged = AtomicBoolean(false)

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
        super.onDestroy()
    }

    // ---- media session + audio focus ---------------------------------------

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "NovelTTS").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumeTts()
                override fun onPause() = pauseTts()
                // Transport/media "stop" (lock screen, Assistant, some headsets)
                // behaves like the receiver's KEYCODE_MEDIA_STOP: pause-and-hold,
                // so Moon Reader never advances and resume keeps the same spot.
                // Only the framework onStop() (client engine.stop()) ends reading.
                override fun onStop() = pauseTts()
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

    /**
     * Report ONLY the truth to the system: PLAYING while PCM is actually being
     * fed, PAUSED while a stream is held, NONE otherwise. Never report PLAYING
     * while silent — the OS decides what a play/pause button press means from
     * this state, so a fake "PLAYING" makes a would-be resume press arrive as
     * a pause (the "need several presses to start" / "sometimes stops instead"
     * feel).
     */
    private fun updateSessionState() {
        val paused = isEffectivelyPaused
        val state = when {
            utteranceActive.get() && !paused -> PlaybackState.STATE_PLAYING
            paused && (utteranceActive.get() || engaged.get()) -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }
        mediaSession?.setPlaybackState(buildPlaybackState(state))
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
        Log.i(TAG, "TTS paused (user) engaged=${engaged.get()} active=${utteranceActive.get()}")
    }

    private fun resumeTts() {
        pausedByUser.set(false)
        // A resume must also lift a focus-induced pause; otherwise the stream
        // stays frozen behind whichever app grabbed audio focus and play presses
        // do nothing until the system happens to hand focus back.
        if (pausedByFocus.get()) {
            ensureAudioFocus()
            if (hasFocus) pausedByFocus.set(false)
            else Log.i(TAG, "resume deferred: audio focus still held elsewhere")
        }
        updateSessionState()
        Log.i(TAG, "TTS resumed (user) engaged=${engaged.get()} active=${utteranceActive.get()}")
    }

    private fun goIdle() {
        Log.i(TAG, "goIdle")
        engaged.set(false)
        utteranceActive.set(false)
        pausedByUser.set(false)
        pausedByFocus.set(false)
        mediaSession?.isActive = false
        updateSessionState()
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

        // Only a FRESH stream (the engine was idle) means the user started
        // reading and wants audio now: clear any stale pause-hold. A
        // continuation utterance of the same stream (the next queued sentence)
        // must NOT clear the pause — clearing it there is what made a pause
        // taken at a sentence boundary "not stick". Capture the current
        // generation so a stop that fired earlier also kills this task.
        val fresh = engaged.compareAndSet(false, true)
        if (fresh) {
            pausedByUser.set(false)
            pausedByFocus.set(false)
            Log.i(TAG, "fresh stream started (cleared stale pause)")
        }
        cancelled.set(false)
        skipCurrent.set(false)
        val gen = generation.get()
        val tReq = SystemClock.elapsedRealtime()
        val enders = text.count { it == '。' || it == '！' || it == '？' || it == '；' }
        val commas = text.count { it == '，' || it == '、' }
        Log.i(TAG, "[perf] req gen=$gen len=${text.length} voice=$voice " +
            "rate=${request.speechRate} pitch=${request.pitch} enders=$enders commas=$commas " +
            "end=${text.lastOrNull()} first=${text.take(14).replace("\n", " ")}")
        // New reading activity: stay "active" and stop the idle timer. The
        // utterance counts as active from here until it is done (fetch, decode,
        // paced playback and any pause-hold), so the reported playback state
        // and the OS button routing stay truthful for the whole sentence.
        mainHandler.removeCallbacks(idleRunnable)
        mediaSession?.isActive = true
        utteranceActive.set(true)
        ensureAudioFocus()

        // IMPORTANT: the framework requires this method to BLOCK until the
        // utterance is fully synthesized and played (or aborted). See
        // TextToSpeechService.playImpl(): immediately after onSynthesizeText
        // returns it auto-completes the item if start() was called but done()
        // was not — that would end the item mid-playback and make stop() unable
        // to interrupt it (the "won't stop" bug). The framework serializes
        // utterances on its own single synthesis thread, so no executor here.
        try {
            synthesizeAndPlay(text, voice, ratePct, pitchHz, callback, gen)
        } catch (e: Exception) {
            Log.e(TAG, "synthesis failed", e)
            try { callback.error() } catch (_: Exception) {}
        } finally {
            utteranceActive.set(false)
            updateSessionState()
            Log.i(TAG, "[perf] done gen=$gen totalMs=${SystemClock.elapsedRealtime() - tReq}")
        }
    }

    /**
     * Returns true if a stop happened since [gen] was captured. The caller must
     * then return WITHOUT calling anything on [callback]: the framework's stop
     * path already notified the client, and error() here would make clients
     * like Moon Reader retry the aborted sentence (reading continues).
     */
    private fun abortIfStale(gen: Long, callback: SynthesisCallback): Boolean {
        if (gen == generation.get()) return false
        Log.i(TAG, "utterance stale (stop since gen $gen); aborting silently")
        return true
    }

    private fun synthesizeAndPlay(
        text: String,
        voice: String,
        ratePct: String,
        pitchHz: String,
        callback: SynthesisCallback,
        gen: Long
    ) {
        val key = SentenceCache.key(voice, ratePct, pitchHz, text)

        // If a stop fired between onSynthesizeText and this point (or while a
        // previous utterance was still synthesizing), abort before any work.
        if (abortIfStale(gen, callback)) return

        val tFetch = SystemClock.elapsedRealtime()
        // 1) cache
        var audio: ByteArray? = cache.get(key)
        var source = "cache"
        if (abortIfStale(gen, callback)) return
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
            if (abortIfStale(gen, callback)) return
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
        if (audio != null) {
            Log.i(TAG, "[perf] fetch gen=$gen source=$source ms=${SystemClock.elapsedRealtime() - tFetch} bytes=${audio.size}")
        }
        if (gen != generation.get() || cancelled.get()) {
            // Stop arrived while fetching: return silently. The framework's stop
            // path already notified the client; error() would make clients like
            // Moon Reader retry the aborted sentence.
            Log.i(TAG, "aborted before playback (stop during fetch)")
            return
        }

        // decode
        val tDecode = SystemClock.elapsedRealtime()
        val decoded = AudioDecoder.decode(audio!!)
        Log.i(TAG, "[perf] decode gen=$gen ms=${SystemClock.elapsedRealtime() - tDecode}")
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
        if (gen != generation.get() || cancelled.get()) {
            Log.i(TAG, "aborted before playback (stop during decode)")
            return
        }

        try {
            // A pause may have arrived while this sentence was being
            // fetched/decoded (there is a multi-second silent gap between
            // sentences): park BEFORE creating the audio track so nothing leaks
            // and resume starts cleanly at the sentence beginning.
            while (isEffectivelyPaused && gen == generation.get() && !cancelled.get() && !skipCurrent.get()) {
                Thread.sleep(50)
            }
            if (gen != generation.get() || cancelled.get()) {
                Log.i(TAG, "aborted before playback (stop during fetch)")
                return
            }
            callback.start(
                decoded.sampleRate,
                2,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            updateSessionState()
            Log.i(TAG, "playing [$source] ${pcm.size / 2} samples @ ${decoded.sampleRate}Hz stereo")
            val tPlay = SystemClock.elapsedRealtime()
            val maxChunk = maxOf(1024, callback.maxBufferSize)
            // Pace delivery to real time (stereo 16-bit: sampleRate * 2ch * 2B).
            // Without pacing the whole utterance is handed to the framework in a
            // few ms and marked done immediately, so a stop can never interrupt
            // it: the framework has no "current" item to abort, onStop is never
            // delivered, and the buffered audio keeps playing — the "won't stop"
            // bug. Keeping the item current for its full duration lets stop()
            // abort it and makes pause-hold/skip work per sentence.
            val bytesPerMs = decoded.sampleRate * 2 * 2 / 1000.0
            var off = 0
            while (off < pcm.size && gen == generation.get() && !cancelled.get() && !skipCurrent.get()) {
                // We are actively playing: cancel any pending idle timer so the
                // session stays active and audio focus is held across sentences.
                mainHandler.removeCallbacks(idleRunnable)
                // Hold playback while paused (earphone pause or audio-focus loss);
                // a stop (generation bump) breaks the hold immediately.
                while (isEffectivelyPaused && gen == generation.get() && !cancelled.get() && !skipCurrent.get()) {
                    Thread.sleep(50)
                }
                if (gen != generation.get() || cancelled.get()) break
                if (skipCurrent.get()) break
                val n = minOf(maxChunk, pcm.size - off)
                val chunkMs = (n / bytesPerMs).toLong()
                val t0 = System.nanoTime()
                callback.audioAvailable(pcm, off, n)
                off += n
                // Sleep the rest of the chunk's real-time duration, in cancelable
                // steps so a stop/next/pause still lands within ~50ms. Abort the
                // sleep early when a pause arrives so the boundary hold below can
                // park the utterance before it completes — otherwise a pause taken
                // in the last chunk of a sentence is silently dropped.
                var remaining = chunkMs - (System.nanoTime() - t0) / 1_000_000
                while (remaining > 0 && gen == generation.get() && !cancelled.get() && !skipCurrent.get() && !isEffectivelyPaused) {
                    val step = minOf(50L, remaining)
                    Thread.sleep(step)
                    remaining -= step
                }
            }
            val audioMs = pcm.size.toDouble() / 4 / decoded.sampleRate * 1000
            Log.i(
                TAG,
                "[perf] play gen=$gen ms=${SystemClock.elapsedRealtime() - tPlay} audioMs=${audioMs.toInt()}"
            )
            // End-of-sentence boundary hold: the whole sentence has been fed but
            // a pause arrived during the final chunk. Park here (before done())
            // so the next queued sentence does not auto-start. Without this, a
            // pause landing in the last ~200 ms of a sentence is lost and the
            // reader just keeps talking.
            if (off >= pcm.size && gen == generation.get() && !cancelled.get() && !skipCurrent.get()) {
                while (isEffectivelyPaused && gen == generation.get() && !cancelled.get() && !skipCurrent.get()) {
                    Thread.sleep(50)
                }
            }
            if (gen != generation.get() || cancelled.get()) {
                // Stop: the framework's stop path (synthesisCallback.stop() +
                // onStop to the client) already halted the audio track, so just
                // return silently. Do NOT call error(): clients like Moon Reader
                // retry errored sentences, which would make reading continue
                // after a stop. The framework's auto-done() then delivers
                // onDone, which the client's stop-state guard ignores.
                Log.i(TAG, "utterance ended by cancel (silent)")
            } else if (skipCurrent.get()) {
                // Media NEXT: end this utterance so Moon Reader advances one sentence.
                Log.i(TAG, "utterance skipped (media NEXT)")
                try { callback.done() } catch (_: Exception) {}
            } else {
                // Completed normally: let the framework continue with the next queued
                // sentence. Moon Reader pre-queues several sentences, so a pause-hold
                // never loses content: the queue stays parked and resumes exactly
                // where it left off.
                try { callback.done() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
            try { callback.error() } catch (_: Exception) {}
        } finally {
            updateSessionState()
            mainHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
        }
    }

    override fun onStop() {
        Log.i(TAG, "onStop: cancelling")
        // Invalidate every in-flight AND already-queued utterance so nothing
        // keeps fetching/playing after the user stopped, and clear any
        // pause-hold so the next start is not blocked.
        generation.incrementAndGet()
        cancelled.set(true)
        pausedByUser.set(false)
        pausedByFocus.set(false)
        engaged.set(false)
        utteranceActive.set(false)
        updateSessionState()
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
