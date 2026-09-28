package com.dsh.noveltts

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted user settings for the engine. Read/written by MainActivity and
 * read by TtsEngineService so choices (voice, rate, pitch, server URL, and
 * the debug force-server toggle) survive restarts.
 */
object Settings {
    private const val PREFS = "novel_tts_prefs"
    private const val KEY_VOICE = "voice"
    private const val KEY_RATE = "rate"        // float, 1.0 = normal
    private const val KEY_PITCH = "pitch"      // float, 1.0 = normal
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_FORCE_SERVER = "force_server"
    private const val KEY_TIER = "tier"              // TtsRouter.Tier name
    private const val KEY_TIER_PINNED = "tier_pinned"
    private const val KEY_FORCE_VOICE = "force_voice"
    private const val KEY_VOICE_MIGRATED = "voice_migrated_local"

    /** Local Kokoro speaker, not an Edge voice: the server speaks it itself. */
    const val DEFAULT_VOICE = Voices.DEFAULT
    const val DEFAULT_SERVER_URL = "http://100.85.43.11:8321"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun voice(context: Context): String =
        prefs(context).getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE

    fun setVoice(context: Context, v: String) =
        prefs(context).edit().putString(KEY_VOICE, v).apply()

    /**
     * The saved voice used to be one of the Edge names, because they were the
     * only ones the picker offered. An Edge name now means "speak this ONLINE",
     * so a saved one is moved to the local speaker with the same persona - the
     * user picked a VOICE (Yunjian, Xiaobei …), not a backend, and the point of
     * the change is that the server's own model speaks by default.
     *
     * Runs once ([KEY_VOICE_MIGRATED]); an explicit local pick is never touched.
     */
    fun migrateVoiceToLocal(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_VOICE_MIGRATED, false)) return
        val saved = p.getString(KEY_VOICE, null)
        val local = saved?.let { Voices.EDGE_TO_LOCAL[it] }
        p.edit()
            .putBoolean(KEY_VOICE_MIGRATED, true)
            .apply {
                if (local != null) putString(KEY_VOICE, local)
            }
            .apply()
        if (local != null) {
            android.util.Log.i(
                "NovelTtsSettings",
                "voice migrated $saved -> $local (local Kokoro instead of Edge)"
            )
        }
    }

    fun rate(context: Context): Float = prefs(context).getFloat(KEY_RATE, 1.0f)

    fun setRate(context: Context, r: Float) =
        prefs(context).edit().putFloat(KEY_RATE, r).apply()

    fun pitch(context: Context): Float = prefs(context).getFloat(KEY_PITCH, 1.0f)

    fun setPitch(context: Context, p: Float) =
        prefs(context).edit().putFloat(KEY_PITCH, p).apply()

    /**
     * True (default) = the voice picked in the app wins, even when the reading
     * client (Moon Reader) asks the engine for a different one of our voices.
     */
    fun forceVoice(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_VOICE, true)

    fun setForceVoice(context: Context, f: Boolean) =
        prefs(context).edit().putBoolean(KEY_FORCE_VOICE, f).apply()

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

    fun setServerUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_SERVER_URL, url).apply()

    fun forceServer(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_SERVER, false)

    fun setForceServer(context: Context, f: Boolean) =
        prefs(context).edit().putBoolean(KEY_FORCE_SERVER, f).apply()

    /** Last TtsRouter tier name (null = never chosen: server-first default). */
    fun tier(context: Context): String? =
        prefs(context).getString(KEY_TIER, null)

    fun setTier(context: Context, t: String) =
        prefs(context).edit().putString(KEY_TIER, t).apply()

    /** True when the user froze the tier (no automatic switching). */
    fun tierPinned(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TIER_PINNED, false)

    fun setTierPinned(context: Context, p: Boolean) =
        prefs(context).edit().putBoolean(KEY_TIER_PINNED, p).apply()
}
