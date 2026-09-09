package com.dsh.noveltts

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Background "text -> audio" queue: renders text AHEAD of playback into the
 * shared on-device SentenceCache, so that when Moon Reader later asks the
 * engine for the same sentences, playback is served from cache and there is
 * no generation wait between sentences.
 *
 * This is the only place a (text, audio) queue can run ahead: it owns the
 * source text. The TTS engine itself can never prefetch, because the Android
 * framework hands the engine one utterance at a time and only delivers the
 * next after the previous one fully completed (see TtsEngineService docs).
 *
 * Renders with the same cascade as live playback (cache -> Edge -> server)
 * and the same cache keys (voice|ratePct|pitchHz|text), so pre-rendered
 * sentences are byte-identical cache hits. One job at a time; cancel() stops
 * between sentences (already-fetched sentences stay cached).
 */
object PreRenderer {

    private const val TAG = "PreRenderer"

    private val gen = AtomicLong(0)

    @Volatile
    var running = false
        private set

    interface Listener {
        /** Called on the worker thread for every sentence processed. */
        fun onProgress(done: Int, total: Int, cached: Int, fetched: Int, failed: Int, text: String)

        /** Called once when the job finishes or is cancelled. */
        fun onFinished(cancelled: Boolean, done: Int, total: Int, cached: Int, fetched: Int, failed: Int)
    }

    /** Cancels the in-flight job (between sentences). */
    fun cancel() {
        gen.incrementAndGet()
    }

    /**
     * Starts a background render of [rawText] (segmented by [mode], "sentence"
     * or "paragraph") into the sentence cache. Any previous job is cancelled.
     */
    /** Segments [rawText] by [mode] then renders each unit. */
    fun start(
        context: Context,
        rawText: String,
        mode: String,
        voice: String,
        ratePct: String,
        pitchHz: String,
        listener: Listener?
    ) {
        val segments = if (mode == "paragraph") {
            TextSegments.byParagraph(rawText)
        } else {
            TextSegments.bySentence(rawText)
        }
        startUnits(context, segments, voice, ratePct, pitchHz, listener)
    }

    /**
     * Renders an already-segmented list of units. This is the exact-match path:
     * when the caller (e.g. the harness, or eventually a reader) already knows
     * the utterance strings, pass them straight through so cache keys match
     * byte-for-byte.
     */
    fun startUnits(
        context: Context,
        units: List<String>,
        voice: String,
        ratePct: String,
        pitchHz: String,
        listener: Listener?
    ) {
        val myGen = gen.incrementAndGet()
        val segments = units.filter { it.isNotBlank() }
        if (segments.isEmpty()) {
            listener?.onFinished(false, 0, 0, 0, 0, 0)
            return
        }
        running = true
        fun runJob() {
            Log.i(TAG, "[pre] start segments=${segments.size} voice=$voice rate=$ratePct pitch=$pitchHz")
            val cache = SentenceCache(context)
            var cached = 0
            var fetched = 0
            var failed = 0
            var done = 0
            try {
                for (i in segments.indices) {
                    if (myGen != gen.get()) {
                        listener?.onFinished(true, done, segments.size, cached, fetched, failed)
                        Log.i(TAG, "[pre] cancelled at $done/${segments.size}")
                        return
                    }
                    val text = segments[i]
                    val key = SentenceCache.key(voice, ratePct, pitchHz, text)
                    var audio: ByteArray? = cache.get(key)
                    if (audio != null) {
                        cached++
                    } else {
                        // Same cascade as live playback in TtsEngineService.
                        if (!TtsEngineService.forceServerOnly) {
                            try {
                                audio = EdgeTtsClient.synthesize(text, voice, ratePct, pitchHz)
                            } catch (e: Exception) {
                                Log.w(TAG, "edge failed (${e.message}); server fallback")
                            }
                        }
                        if (audio == null) {
                            try {
                                val (body, _) = ServerTtsClient.synthesize(text, voice, ratePct, pitchHz)
                                audio = body
                            } catch (e: Exception) {
                                failed++
                                Log.w(TAG, "render failed for segment $i: ${e.message}")
                            }
                        }
                        if (audio != null) {
                            cache.put(key, audio)
                            fetched++
                        }
                    }
                    done++
                    Log.i(TAG, "[pre] seg $done/${segments.size} cached=$cached fetched=$fetched failed=$failed len=${text.length}")
                    listener?.onProgress(done, segments.size, cached, fetched, failed, text)
                }
                Log.i(TAG, "[pre] finished done=$done cached=$cached fetched=$fetched failed=$failed")
                listener?.onFinished(false, done, segments.size, cached, fetched, failed)
            } finally {
                running = false
            }
        }
        Thread({ runJob() }, "tts-prerender").apply { isDaemon = true }.start()
    }
}
