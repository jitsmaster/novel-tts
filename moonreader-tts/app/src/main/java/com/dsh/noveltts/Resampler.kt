package com.dsh.noveltts

/**
 * Linear-interpolation resampler. Used to convert decoded PCM (24 kHz from
 * Edge/Kokoro) to the device's native output sample rate, because the TTS
 * framework plays the track at the device rate; feeding mismatched-rate PCM
 * causes 2x-speed, high-pitched audio (the "squirrel" sound).
 */
object Resampler {

    /**
     * Resample interleaved 16-bit PCM.
     * @param pcm input samples (interleaved if [channels] > 1)
     * @param fromRate input sample rate in Hz
     * @param toRate output sample rate in Hz
     * @param channels channel count (interleaved)
     */
    fun resample(pcm: ShortArray, fromRate: Int, toRate: Int, channels: Int): ShortArray {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate || pcm.isEmpty()) return pcm
        val frameCount = pcm.size / channels
        val outFrames = (frameCount.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outFrames * channels)
        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (f in 0 until outFrames) {
            val srcPos = f * ratio
            val i0 = srcPos.toInt().coerceIn(0, frameCount - 1)
            val i1 = (i0 + 1).coerceAtMost(frameCount - 1)
            val frac = (srcPos - i0).toFloat()
            for (c in 0 until channels) {
                val a = pcm[i0 * channels + c]
                val b = pcm[i1 * channels + c]
                out[f * channels + c] = (a + (b - a) * frac).toInt().toShort()
            }
        }
        return out
    }
}
