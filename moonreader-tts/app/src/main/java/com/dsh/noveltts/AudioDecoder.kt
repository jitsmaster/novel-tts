package com.dsh.noveltts

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Decodes mp3/wav bytes to 16-bit little-endian PCM via MediaExtractor +
 * MediaCodec. Uses the MediaCodec OUTPUT format (not the container header)
 * for sample rate / channel count — decoders on some devices upmix mono to
 * stereo or report a different rate, and feeding PCM with a mismatched
 * rate/channel count to the TTS framework causes high-pitched or slowed audio.
 */
object AudioDecoder {

    data class Result(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
    )

    fun decode(bytes: ByteArray): Result {
        val tmp = File.createTempFile("tts_in", ".bin")
        try {
            tmp.writeBytes(bytes)
            return decodeFile(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun decodeFile(file: File): Result {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.trackCount == 0) throw RuntimeException("no tracks in audio")
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw RuntimeException("no mime")
            extractor.selectTrack(0)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            // Authoritative values come from the decoder's OUTPUT format.
            var outSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    if (info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        outBuf.get(chunk)
                        out.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = codec.outputFormat
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        outSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        outChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            return Result(out.toByteArray(), outSampleRate, outChannels)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
}
