package com.dsh.noveltts

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal HTTP client for the DSH TTS server (the local fallback tier).
 * The server itself cascades Edge -> Kokoro, so a single call here is
 * already resilient to Edge throttling.
 */
object ServerTtsClient {

    /** Set by MainActivity (or adb) before use; defaults to the Mac mini's Tailscale IP. */
    @Volatile
    var baseUrl: String = "http://100.85.43.11:8321"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * @return raw audio bytes (mp3 or wav, check the returned media type)
     * @throws IOException on network error or non-2xx
     */
    @Throws(IOException::class)
    fun synthesize(
        text: String,
        voice: String,
        ratePct: String,
        pitchHz: String
    ): Pair<ByteArray, String> {
        val url: HttpUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("tts")
            .addQueryParameter("text", text)
            .addQueryParameter("voice", voice)
            .addQueryParameter("rate", ratePct)
            .addQueryParameter("pitch", pitchHz)
            .build()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("server tts failed: HTTP ${resp.code}")
            }
            val media = resp.header("Content-Type") ?: "audio/mpeg"
            val body = resp.body?.bytes() ?: throw IOException("empty body")
            if (body.size < 100) throw IOException("server returned tiny body")
            return body to media
        }
    }
}
