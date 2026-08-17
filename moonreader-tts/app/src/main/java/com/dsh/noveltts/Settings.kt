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

    const val DEFAULT_VOICE = "zh-CN-YunxiNeural"
    const val DEFAULT_SERVER_URL = "http://100.85.43.11:8321"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun voice(context: Context): String =
        prefs(context).getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE

    fun setVoice(context: Context, v: String) =
        prefs(context).edit().putString(KEY_VOICE, v).apply()

    fun rate(context: Context): Float = prefs(context).getFloat(KEY_RATE, 1.0f)

    fun setRate(context: Context, r: Float) =
        prefs(context).edit().putFloat(KEY_RATE, r).apply()

    fun pitch(context: Context): Float = prefs(context).getFloat(KEY_PITCH, 1.0f)

    fun setPitch(context: Context, p: Float) =
        prefs(context).edit().putFloat(KEY_PITCH, p).apply()

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

    fun setServerUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_SERVER_URL, url).apply()

    fun forceServer(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_SERVER, false)

    fun setForceServer(context: Context, f: Boolean) =
        prefs(context).edit().putBoolean(KEY_FORCE_SERVER, f).apply()
}
