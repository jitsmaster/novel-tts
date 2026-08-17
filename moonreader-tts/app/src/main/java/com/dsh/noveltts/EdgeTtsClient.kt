package com.dsh.noveltts

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Faithful Kotlin port of the edge-tts protocol (validated against the live
 * service): Sec-MS-GEC token via exact integer (Long) math, WSS connect with
 * the Edge headers, speech.config + ssml frames, binary audio frame parsing
 * ([2-byte header length][headers][\r\n\r\n][mp3]).
 */
object EdgeTtsClient {

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WSS_BASE =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val SEC_MS_GEC_VERSION = "1-143.0.3650.75"
    private const val WIN_EPOCH = 11644473600L
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    /** Exact integer math equivalent of DRM.generate_sec_ms_gec(). */
    fun generateSecMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000L + WIN_EPOCH
        ticks -= ticks % 300L
        ticks *= 10_000_000L
        val input = "$ticks$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02X".format(b))
        return sb.toString()
    }

    private fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun dateToString(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    /** Remove control characters the service rejects (edge-tts remove_incompatible_characters). */
    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val code = ch.code
            if ((code in 0..8) || (code in 11..12) || (code in 14..31)) {
                sb.append(' ')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Escape XML special characters. */
    private fun xmlEscape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun mkssml(text: String, voice: String, ratePct: String, pitchHz: String): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='$pitchHz' rate='$ratePct' volume='+0%'>" +
            xmlEscape(cleanText(text)) +
            "</prosody></voice></speak>"

    /**
     * Synthesize [text] to MP3 bytes using the given Edge [voice].
     *
     * @param ratePct e.g. "+0%" (Edge rate parameter syntax)
     * @param pitchHz e.g. "+0Hz" (Edge pitch parameter syntax)
     * @param timeoutMs overall timeout for the whole synthesis
     * @throws Exception on any failure (network, timeout, no audio)
     */
    @Throws(Exception::class)
    fun synthesize(
        text: String,
        voice: String,
        ratePct: String,
        pitchHz: String,
        timeoutMs: Long = 15000L
    ): ByteArray {
        val url = "$WSS_BASE?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&ConnectionId=${connectId()}" +
            "&Sec-MS-GEC=${generateSecMsGec()}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Cookie", "muid=${connectId().uppercase()};")
            .build()

        val mp3 = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val config = "X-Timestamp:${dateToString()}\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                    "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                    "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                webSocket.send(config)

                val ssmlFrame = "X-RequestId:${connectId()}\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${dateToString()}Z\r\n" +
                    "Path:ssml\r\n\r\n" +
                    mkssml(text, voice, ratePct, pitchHz)
                webSocket.send(ssmlFrame)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Text frames carry Path:turn.end when the utterance is done.
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, "done")
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLength = ((data[0].toInt() and 0xFF) shl 8) or
                    (data[1].toInt() and 0xFF)
                if (headerLength < 0 || headerLength > data.size) return
                // Edge-tts parses data[:header_length] (which includes the 2 length bytes
                // glued to the first header line) and treats payload as data[header_length+2:].
                val headerText = String(data, 0, headerLength, Charsets.US_ASCII)
                if (headerText.contains("Path:audio")) {
                    val payloadStart = headerLength + 2
                    if (payloadStart < data.size) {
                        mp3.write(data, payloadStart, data.size - payloadStart)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                error.set(t)
                latch.countDown()
            }
        }

        val ws = client.newWebSocket(request, listener)
        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) {
            ws.cancel()
            throw RuntimeException("Edge TTS timed out after ${timeoutMs}ms")
        }
        error.get()?.let { throw it }
        val result = mp3.toByteArray()
        if (result.size < 100) {
            throw RuntimeException("Edge TTS returned no audio")
        }
        return result
    }
}
