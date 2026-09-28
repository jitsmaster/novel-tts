package com.dsh.noveltts

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Last-resort tier: the device's OWN TTS engine (Google TTS by default).
 *
 * The engine is asked to render the sentence to a WAV file
 * ([TextToSpeech.synthesizeToFile]) instead of speaking it directly, so the
 * audio travels the SAME path as the other tiers: decode -> cache ->
 * [TtsEngineService] callback / AudioTrack, with the same pause, stop and
 * audio-focus behaviour. Speaking directly would bypass all of that.
 *
 * Engine choice: `com.google.android.tts`, else the device's default TTS
 * engine when that is NOT this app (calling ourselves would recurse), else
 * Pico. The chosen engine is cached and reused (engine startup costs a few
 * hundred ms).
 *
 * MUST be called off the main thread: the TextToSpeech init callback is
 * delivered on the main looper, so blocking the main thread would deadlock.
 */
object GoogleTtsClient {

    private const val TAG = "NovelTtsGoogle"
    const val GOOGLE_ENGINE = "com.google.android.tts"
    private const val PICO_ENGINE = "com.svox.pico"
    private const val INIT_TIMEOUT_MS = 10_000L
    private const val SYNTH_TIMEOUT_MS = 30_000L

    private val engineLock = Any()
    private val synthLock = Any()

    private var engine: TextToSpeech? = null
    private var boundPackage: String? = null

    /** Package name of the fallback engine in use (null = none usable). */
    @Volatile
    var activeEngine: String? = null
        private set

    /** True when some non-recursive fallback engine exists on this device. */
    fun available(context: Context): Boolean = pickEngine(context) != null

    /** Human-readable engine description for the settings UI. */
    fun describe(context: Context): String =
        pickEngine(context) ?: "无（设备上没有可用的回退引擎）"

    /** The engine package we would use, or null. Never returns this app. */
    private fun pickEngine(context: Context): String? {
        val pm = context.packageManager
        if (installed(pm, GOOGLE_ENGINE)) return GOOGLE_ENGINE
        val def = try {
            AndroidSettings.Secure.getString(context.contentResolver, "tts_default_synth")
        } catch (_: Exception) {
            null
        }
        if (def != null && def != context.packageName && installed(pm, def)) return def
        if (installed(pm, PICO_ENGINE)) return PICO_ENGINE
        return null
    }

    private fun installed(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Renders [text] with the fallback engine and returns the raw WAV bytes.
     * Throws with a user-readable message when the engine is missing, has no
     * Chinese voice, or fails to synthesize.
     */
    @Throws(Exception::class)
    fun synthesize(
        context: Context,
        text: String,
        ratePct: String,
        pitchHz: String,
    ): ByteArray {
        if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("Google TTS 兜底不能在主线程调用")
        }
        val c = context.applicationContext
        val pkg = pickEngine(c) ?: throw IllegalStateException("设备上没有可用的回退 TTS 引擎")
        val out = File.createTempFile("gtts_", ".wav", c.cacheDir)
        try {
            // One synthesis at a time: the progress listener is per-function on
            // the engine instance, so parallel utterances would cross wires.
            synchronized(synthLock) {
                val tts = engine(c, pkg)
                val latch = CountDownLatch(1)
                val err = AtomicReference<String?>(null)
                val langRes = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                    throw IllegalStateException("回退引擎不支持中文（$pkg）")
                }
                if (langRes == TextToSpeech.LANG_MISSING_DATA) {
                    Log.w(TAG, "zh-CN voice data missing for $pkg; asking anyway")
                }
                tts.setSpeechRate(asRate(ratePct))
                tts.setPitch(asPitch(pitchHz))
                val id = "gtts-" + System.nanoTime()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = latch.countDown()
                    @Deprecated("Superseded by onError(String, int)")
                    override fun onError(utteranceId: String?) {
                        err.set("回退语音合成失败")
                        latch.countDown()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        err.set("回退语音合成失败 (code $errorCode)")
                        latch.countDown()
                    }
                })
                val res = tts.synthesizeToFile(text, null, out, id)
                if (res != TextToSpeech.SUCCESS) {
                    throw IllegalStateException("回退引擎拒绝合成请求 ($res)")
                }
                if (!latch.await(SYNTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw IllegalStateException("回退语音合成超时")
                }
                err.get()?.let { throw IllegalStateException(it) }
            }
            val bytes = out.readBytes()
            if (bytes.size < 100) throw IllegalStateException("回退引擎返回空音频")
            Log.i(TAG, "google fallback ok: ${bytes.size} bytes via $pkg")
            return bytes
        } finally {
            try { out.delete() } catch (_: Exception) {}
        }
    }

    /** Creates (or reuses) the bound engine instance. */
    private fun engine(context: Context, pkg: String): TextToSpeech {
        synchronized(engineLock) {
            val cur = engine
            if (cur != null && boundPackage == pkg) return cur
            try { cur?.shutdown() } catch (_: Exception) {}
            engine = null
            boundPackage = null
            val latch = CountDownLatch(1)
            val status = AtomicReference(TextToSpeech.ERROR)
            val tts = TextToSpeech(
                context,
                { s ->
                    status.set(s)
                    latch.countDown()
                },
                pkg
            )
            val ok = try {
                latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                false
            }
            if (!ok || status.get() != TextToSpeech.SUCCESS) {
                try { tts.shutdown() } catch (_: Exception) {}
                throw IllegalStateException("回退 TTS 引擎初始化失败（$pkg）")
            }
            engine = tts
            boundPackage = pkg
            activeEngine = pkg
            Log.i(TAG, "bound fallback engine $pkg")
            return tts
        }
    }

    /** Releases the bound engine (not required; the app keeps one instance). */
    fun shutdown() {
        synchronized(engineLock) {
            try { engine?.shutdown() } catch (_: Exception) {}
            engine = null
            boundPackage = null
        }
    }

    /** "+20%" / "-10%" -> TextToSpeech speech-rate multiplier. */
    private fun asRate(pct: String): Float {
        val n = pct.trim().removeSuffix("%").toFloatOrNull() ?: 0f
        return (1f + n / 100f).coerceIn(0.1f, 3f)
    }

    /** "+20Hz" -> pitch multiplier (same convention as the rate, approximate). */
    private fun asPitch(hz: String): Float {
        val n = hz.trim().removeSuffix("Hz").toFloatOrNull() ?: 0f
        return (1f + n / 100f).coerceIn(0.1f, 2f)
    }
}
