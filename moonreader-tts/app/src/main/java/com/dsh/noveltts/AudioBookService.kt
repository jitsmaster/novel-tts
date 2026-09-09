package com.dsh.noveltts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Direct audio-book player — no Android TTS framework, no Moon Reader.
 *
 * The reader activity feeds it one chapter at a time (list of text blocks).
 * A look-ahead producer thread fetches + decodes the NEXT block while the
 * current one plays, so playback is gap-free (that is the (text,audio) queue:
 * the next block is always ready before the current one ends). The service
 * owns an AudioTrack, audio focus and a MediaSession, so:
 *   - Play / pause at ANY time, mid-block included (UI or earphone button)
 *   - ±10 s rewind/forward (UI buttons AND earphone rewind/forward/prev/next)
 *   - keeps playing with the screen off (foreground service)
 */
class AudioBookService : Service() {

    companion object {
        private const val TAG = "NovelTtsAudio"
        private const val CHANNEL = "novel_tts_audio"
        private const val NOTIF_ID = 7
        private const val SAMPLE_RATE = 24000

        // intents
        const val ACTION_START = "com.dsh.noveltts.audio.START"
        const val ACTION_PLAY = "com.dsh.noveltts.audio.PLAY"
        const val ACTION_PAUSE = "com.dsh.noveltts.audio.PAUSE"
        const val ACTION_PLAY_PAUSE = "com.dsh.noveltts.audio.PLAY_PAUSE"
        const val ACTION_STOP = "com.dsh.noveltts.audio.STOP"
        const val ACTION_SEEK = "com.dsh.noveltts.audio.SEEK"      // extra "deltaMs"
        const val ACTION_NEXT_BLOCK = "com.dsh.noveltts.audio.NEXT_BLOCK"
        const val ACTION_PREV_BLOCK = "com.dsh.noveltts.audio.PREV_BLOCK"
        const val ACTION_SPEED = "com.dsh.noveltts.audio.SPEED"   // re-read Settings.rate
        const val EXTRA_BOOK = "book"
        const val EXTRA_BLOCKS = "blocks"
        const val EXTRA_FROM = "fromBlock"
        const val EXTRA_DELTA = "deltaMs"

        // state broadcasts to the activity
        const val ACTION_STATE = "com.dsh.noveltts.audio.STATE"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_BLOCK = "block"
        const val EXTRA_POS = "posMs"
        const val EXTRA_DUR = "durMs"
        const val EXTRA_REASON = "reason"   // block-start | done | pause | play | seek | stopped | finished

        /** Routed by MediaButtonReceiver to whichever player is active. */
        @Volatile
        private var instance: AudioBookService? = null

        @JvmStatic
        fun instanceHandle(): AudioBookService? = instance

        fun handleKey(keyCode: Int) {
            instance?.onMediaKey(keyCode)
        }

        fun start(context: Context, book: String, blocks: List<String>, fromBlock: Int) {
            val i = Intent(context, AudioBookService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_BOOK, book)
                .putStringArrayListExtra(EXTRA_BLOCKS, ArrayList(blocks))
                .putExtra(EXTRA_FROM, fromBlock)
            context.startForegroundService(i)
        }

        fun control(context: Context, action: String, deltaMs: Int = 0) {
            val i = Intent(context, AudioBookService::class.java).setAction(action)
            if (action == ACTION_SEEK) i.putExtra(EXTRA_DELTA, deltaMs)
            context.startService(i)
        }
    }

    // ---- state ------------------------------------------------------------

    private val blocks = ArrayList<String>()
    private var bookName = ""
    private var fromBlock = 0

    private val playing = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val gen = AtomicLong(0)          // bumped on stop/start
    private var blockIndex = 0

    // pcm of the CURRENT block (kept for seek) + decoded length
    @Volatile private var curPcm: ByteArray? = null
    @Volatile private var curSampleRate = SAMPLE_RATE
    private val decodedDursMs = HashMap<Int, Long>()   // exact durations once decoded
    private val CHARS_PER_SEC = 4.6

    private var track: AudioTrack? = null
    private var foregroundActive = false
    private var posInBlockMs = 0L            // playback position inside current block
    // seek handoff: written by seekToMs/skipTo (main), consumed by the next
    // playback thread at its first block (NOT via the shared posInBlockMs,
    // which the dying thread can still overwrite for ~one chunk)
    @Volatile private var pendingSeekBlock = -1
    @Volatile private var pendingSeekMs = 0L
    private var chapterPosMs = 0L            // position within the loaded chapter

    // background threads
    private var playThread: Thread? = null

    // media integration
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupMediaSession()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() request must be answered quickly with
        // startForeground() or the system kills us (and throws at the caller).
        // Answer immediately for any action that keeps the player alive.
        if (intent?.action != ACTION_STOP) {
            ensureForeground()
        }
        when (intent?.action) {
            ACTION_START -> {
                bookName = intent.getStringExtra(EXTRA_BOOK) ?: "小说"
                val list = intent.getStringArrayListExtra(EXTRA_BLOCKS) ?: return START_NOT_STICKY
                fromBlock = intent.getIntExtra(EXTRA_FROM, 0)
                loadChapter(bookName, list, fromBlock)
            }
            ACTION_PLAY -> resume()
            ACTION_PAUSE -> pause()
            ACTION_PLAY_PAUSE -> if (playing.get()) pause() else resume()
            ACTION_STOP -> stopPlayback()
            ACTION_SEEK -> seekBy(intent.getIntExtra(EXTRA_DELTA, 0))
            ACTION_NEXT_BLOCK -> skipTo(blockIndex + 1)
            ACTION_PREV_BLOCK -> skipTo(blockIndex - 1)
            ACTION_SPEED -> applySpeedToTrack()
        }
        return START_NOT_STICKY
    }

    @Volatile
    private var currentSpeed = 1.0f

    /** Read the persisted speed (Settings.rate) and apply to the live track. */
    private fun applySpeedToTrack() {
        val s = Settings.rate(applicationContext).coerceIn(0.5f, 2.0f)
        currentSpeed = s
        try {
            val tr = track
            if (tr != null && tr.playState == AudioTrack.PLAYSTATE_PLAYING) {
                tr.setPlaybackParams(
                    android.media.PlaybackParams().setSpeed(s)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "setPlaybackParams failed: ${e.message}")
        }
        Log.i(TAG, "speed set to ${s}x")
    }

    private fun ensureForeground() {
        if (foregroundActive) return
        foregroundActive = true
        startForeground(NOTIF_ID, buildNotification(if (playing.get()) "播放中" else bookName.ifEmpty { "小说" }))
    }

    override fun onDestroy() {
        instance = null
        stopPlayback()
        try { mediaSession?.release() } catch (_: Exception) {}
        mediaSession = null
        super.onDestroy()
    }

    // ---- chapter load -----------------------------------------------------

    private fun loadChapter(name: String, list: List<String>, from: Int) {
        gen.incrementAndGet()
        blocks.clear()
        blocks.addAll(list)
        bookName = name
        decodedDursMs.clear()
        blockIndex = from.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
        chapterPosMs = 0
        posInBlockMs = 0
        playThread?.interrupt()
        playing.set(true)
        pauseRequested.set(false)
        stopRequested.set(false)
        ensureForeground()
        Log.i(TAG, "loadChapter ${blocks.size} blocks from=$from")
        playThread = Thread({ runPlayer() }, "novel-audio-player").apply {
            isDaemon = false
            start()
        }
        broadcastState("start")
    }

    // ---- player loop ------------------------------------------------------

    private fun runPlayer() {
        val myGen = gen.get()
        try {
            var firstBlock = true
            while (blockIndex < blocks.size && gen.get() == myGen && !stopRequested.get()) {
                if (pauseRequested.get()) {
                    pauseAudioTrack()
                    while (pauseRequested.get() && !stopRequested.get() && gen.get() == myGen) {
                        Thread.sleep(50)
                    }
                    if (stopRequested.get() || gen.get() != myGen) break
                    mainHandler.post { updateNotification("播放中") }
                    broadcastState("play")
                }
                if (stopRequested.get() || gen.get() != myGen) break

                // Resolve where this block starts from: a pending seek target,
                // the saved mid-block offset (resume), or the block start.
                val startMs = when {
                    pendingSeekBlock >= 0 -> {
                        val m = pendingSeekMs
                        pendingSeekBlock = -1
                        pendingSeekMs = 0
                        m
                    }
                    firstBlock -> posInBlockMs
                    else -> 0L
                }
                firstBlock = false

                // fetch + decode current block (blocking; cancel-checked)
                val text = blocks[blockIndex]
                val pcm = obtainPcm(text, myGen)
                if (pcm == null || gen.get() != myGen || stopRequested.get()) break
                curPcm = pcm
                val bytesPerSec = curSampleRate * 2 * 2
                val blockDurMs = (pcm.size.toLong() * 1000) / bytesPerSec
                decodedDursMs[blockIndex] = blockDurMs
                mainHandler.post { updateNotification("第 ${blockIndex + 1}/${blocks.size} 块") }
                broadcastState("block-start")

                // decode is fast (in-memory): start pre-fetching the next block
                val nextIdx = blockIndex + 1
                if (nextIdx < blocks.size) {
                    val myGen2 = myGen
                    Thread({
                        if (gen.get() == myGen2 && !stopRequested.get()) {
                            try { obtainPcm(blocks[nextIdx], myGen2) } catch (_: Exception) {}
                        }
                    }, "novel-prefetch").apply { isDaemon = true }.start()
                }

                val played = playPcm(pcm, myGen, startMs)
                if (played < 0) break                      // stopped/paused-ended
                blockIndex++
                chapterPosMs += blockDurMs
                posInBlockMs = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "player error", e)
        } finally {
            if (gen.get() == myGen) {
                // We are still the current session: normal end (chapter
                // finished or stop). Tear down state + foreground.
                if (!stopRequested.get() && blockIndex >= blocks.size) {
                    mainHandler.post { updateNotification("播放完毕") }
                    broadcastState("finished")
                }
                releaseTrack()
                stopForegroundCompat()
                playing.set(false)
                broadcastState("stopped")
            } else {
                // Superseded by a newer session (seek/restart). Never touch
                // shared state, the foreground flag or the new session's
                // AudioTrack — the newer thread owns them now.
            }
        }
    }

    /** Write pcm to an AudioTrack (blocking = natural pacing). Returns -1 if aborted. */
    private fun playPcm(pcm: ByteArray, myGen: Long, startMs: Long): Int {
        val startOffset = ((startMs * curSampleRate * 2 * 2) / 1000L).toInt().coerceIn(0, pcm.size)
        val stereo = if (startOffset == 0) pcm else pcm.copyOfRange(startOffset, pcm.size)
        val writtenTotal = IntArray(1)
        ensureAudioFocus()
        val tr = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(curSampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(64 * 1024, AudioTrack.getMinBufferSize(
                curSampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 4))
            .build()
        track = tr
        try {
            tr.play()
            applySpeedToTrack()
            var off = 0
            while (off < stereo.size && gen.get() == myGen && !stopRequested.get()) {
                while (pauseRequested.get() && !stopRequested.get() && gen.get() == myGen) {
                    if (tr.playState == AudioTrack.PLAYSTATE_PLAYING) tr.pause()
                    Thread.sleep(50)
                }
                if (stopRequested.get() || gen.get() != myGen) break
                if (tr.playState == AudioTrack.PLAYSTATE_PAUSED) tr.play()
                val n = minOf(64 * 1024, stereo.size - off)
                val written = tr.write(stereo, off, n)
                if (written <= 0) break
                off += written
                writtenTotal[0] = off
                posInBlockMs = ((startOffset + off).toLong() * 1000) / (curSampleRate * 2 * 2)
                if (off % (curSampleRate * 2 * 2 * 2) < n) broadcastState("pos")   // ~1s cadence
            }
            if (tr.playState == AudioTrack.PLAYSTATE_PLAYING) {
                // let the tail drain before the next block starts
                tr.stop()
            }
            return if (gen.get() != myGen || stopRequested.get()) -1 else 0
        } catch (e: Exception) {
            Log.e(TAG, "track write failed", e)
            return -1
        } finally {
            try { tr.release() } catch (_: Exception) {}
            if (track === tr) track = null
        }
    }

    private fun pauseAudioTrack() {
        try { track?.pause() } catch (_: Exception) {}
        mainHandler.post { updateNotification("已暂停") }
        broadcastState("pause")
    }

    private fun obtainPcm(text: String, myGen: Long): ByteArray? {
        val key = SentenceCache.key(
            Settings.voice(this).takeIf { it.isNotBlank() } ?: "zh-CN-YunxiNeural",
            "+0%", "+0Hz", text
        )
        var audio = cache().get(key)
        if (audio == null && !TtsEngineService.forceServerOnly) {
            try {
                audio = EdgeTtsClient.synthesize(text, "zh-CN-YunxiNeural", "+0%", "+0Hz")
            } catch (_: Exception) {}
        }
        if (audio == null) {
            try {
                val (body, _) = ServerTtsClient.synthesize(text, "zh-CN-YunxiNeural", "+0%", "+0Hz")
                audio = body
            } catch (e: Exception) {
                Log.e(TAG, "fetch failed: ${e.message}")
                Thread.sleep(800)
                if (gen.get() != myGen) return null
                return obtainPcm(text, myGen)
            }
        }
        cache().put(key, audio)
        val decoded = AudioDecoder.decode(audio)
        curSampleRate = decoded.sampleRate
        val shorts = pcmToShorts(decoded.pcm)
        val stereo = if (decoded.channels >= 2) shorts else upmix(shorts)
        return toBytes(stereo)
    }

    private var _cache: SentenceCache? = null
    private fun cache(): SentenceCache {
        if (_cache == null) _cache = SentenceCache(applicationContext)
        return _cache!!
    }

    /** Little-endian 16-bit PCM bytes -> ShortArray (same as engine). */
    private fun pcmToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            shorts[i] = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return shorts
    }

    private fun upmix(mono: ShortArray): ShortArray {
        val out = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            out[i * 2] = mono[i]; out[i * 2 + 1] = mono[i]
        }
        return out
    }

    private fun toBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val s = shorts[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    // ---- controls ---------------------------------------------------------

    private fun resume() {
        if (blocks.isEmpty()) return
        if (playing.get() && !pauseRequested.get()) return
        if (blockIndex >= blocks.size) return
        if (!playing.get()) {
            // restarted after stop/finish: replay from current position
            ensureForeground()
            playing.set(true)
            stopRequested.set(false)
            gen.incrementAndGet()
            playThread = Thread({ runPlayer() }, "novel-audio-player").apply { start() }
            return
        }
        pauseRequested.set(false)
        broadcastState("play")
    }

    private fun pause() {
        if (!playing.get()) return
        Log.i(TAG, "pause() pos=${currentChapterPosMs()}")
        pauseRequested.set(true)
        broadcastState("pause")
    }

    private fun stopPlayback() {
        Log.i(TAG, "stopPlayback()")
        stopRequested.set(true)
        gen.incrementAndGet()
        playing.set(false)
        pauseRequested.set(false)
        playThread?.interrupt()
        releaseTrack()
        releaseAudioFocus()
        stopForegroundCompat()
        broadcastState("stopped")
    }

    private fun seekBy(deltaMs: Int) {
        if (blocks.isEmpty()) return
        val total = chapterTotalMs()
        val cur = currentChapterPosMs()
        val target = (cur + deltaMs).coerceIn(0L, total)
        Log.i(TAG, "seekBy delta=$deltaMs cur=$cur target=$target total=$total block=$blockIndex posInBlock=$posInBlockMs playing=${playing.get()} paused=${pauseRequested.get()}")
        seekToMs(target)
    }

    private fun seekToMs(targetMs: Long) {
        // resolve target to (blockIndex, offsetMs) from exact/estimated durations
        var acc = 0L
        var idx = 0
        while (idx < blocks.size) {
            val d = blockDurMs(idx)
            if (acc + d > targetMs) break
            acc += d
            idx++
        }
        if (idx >= blocks.size) idx = blocks.size - 1
        blockIndex = idx
        val inBlock = (targetMs - acc).coerceAtLeast(0)
        posInBlockMs = inBlock
        chapterPosMs = acc
        pendingSeekBlock = idx
        pendingSeekMs = inBlock
        Log.i(TAG, "seekToMs target=$targetMs -> block=$idx inBlock=$inBlock (dur=${blockDurMs(idx)})")
        // restart playback from the new position
        val wasPlaying = playing.get() && !pauseRequested.get()
        stopRequested.set(true)
        gen.incrementAndGet()
        playThread?.interrupt()
        releaseTrack()
        playing.set(true)
        stopRequested.set(false)
        pauseRequested.set(false)
        ensureForeground()
        playThread = Thread({ runPlayer() }, "novel-audio-player").apply { start() }
        if (!wasPlaying) pauseRequested.set(true)
        broadcastState("seek")
    }

    private fun skipTo(idx: Int) {
        if (blocks.isEmpty()) return
        val target = idx.coerceIn(0, blocks.size - 1)
        // jump to the START of that block
        var acc = 0L
        for (i in 0 until target) acc += blockDurMs(i)
        blockIndex = target
        chapterPosMs = acc
        posInBlockMs = 0
        pendingSeekBlock = target
        pendingSeekMs = 0
        val wasPlaying = playing.get() && !pauseRequested.get()
        stopRequested.set(true)
        gen.incrementAndGet()
        playThread?.interrupt()
        releaseTrack()
        if (wasPlaying) {
            playing.set(true); stopRequested.set(false); pauseRequested.set(false)
            ensureForeground()
            playThread = Thread({ runPlayer() }, "novel-audio-player").apply { start() }
        } else {
            playing.set(false)
            stopRequested.set(false)
        }
        broadcastState("seek")
    }

    private fun blockDurMs(idx: Int): Long {
        decodedDursMs[idx]?.let { return it }
        val chars = blocks.getOrNull(idx)?.length ?: 0
        return (chars * 1000.0 / CHARS_PER_SEC).toLong()
    }

    private fun chapterTotalMs(): Long {
        var acc = 0L
        for (i in blocks.indices) acc += blockDurMs(i)
        return acc
    }

    private fun currentChapterPosMs(): Long {
        if (playing.get() && !pauseRequested.get()) {
            return chapterPosMs + posInBlockMs
        }
        return chapterPosMs + posInBlockMs
    }

    // ---- media session / focus / headset ---------------------------------

    private fun onMediaKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> resume()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (playing.get() && !pauseRequested.get()) pause() else resume()
            KeyEvent.KEYCODE_MEDIA_STOP -> stopPlayback()
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(10_000)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-10_000)
            else -> {}
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "NovelTtsAudio").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() = stopPlayback()
                override fun onSkipToNext() = seekBy(10_000)
                override fun onSkipToPrevious() = seekBy(-10_000)
                override fun onRewind() = seekBy(-10_000)
                override fun onFastForward() = seekBy(10_000)
            })
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setMediaButtonReceiver(
                PendingIntent.getBroadcast(
                    this@AudioBookService, 1,
                    Intent(this@AudioBookService, MediaButtonReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            setPlaybackState(buildPlaybackState(PlaybackState.STATE_NONE))
            isActive = true
        }
    }

    private fun buildPlaybackState(state: Int): PlaybackState =
        PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD
            )
            .setState(state, currentChapterPosMs(), 1f)
            .build()

    private fun updateMediaState() {
        val state = when {
            stopRequested.get() || !playing.get() -> PlaybackState.STATE_NONE
            pauseRequested.get() -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        try {
            mediaSession?.setPlaybackState(buildPlaybackState(state))
        } catch (_: Exception) {}
    }

    private fun ensureAudioFocus() {
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .build()
                am.requestAudioFocus(focusRequest!!)
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (_: Exception) {}
    }

    private fun releaseAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) am.abandonAudioFocusRequest(focusRequest!!)
            else am.abandonAudioFocus(null)
        } catch (_: Exception) {}
    }

    private fun releaseTrack() {
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) {}
        track = null
    }

    // ---- notification -----------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val c = NotificationChannel(CHANNEL, "小说朗读", NotificationManager.IMPORTANCE_LOW)
            c.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(c)
        }
    }

    private fun buildNotification(line: String): Notification {
        val pi = PendingIntent.getService(
            this, 2,
            Intent(this, AudioBookService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 3,
            Intent(this, AudioBookService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else Notification.Builder(this)
        return builder
            .setContentTitle(bookName)
            .setContentText(line)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "播放/暂停", pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
            .build()
    }

    private fun updateNotification(line: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(line))
        } catch (_: Exception) {}
    }

    private fun stopForegroundCompat() {
        foregroundActive = false
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
    }

    // ---- activity communication -------------------------------------------

    private fun broadcastState(reason: String) {
        updateMediaState()
        try {
            sendBroadcast(
                Intent(ACTION_STATE)
                    .setPackage(packageName)
                    .putExtra(EXTRA_PLAYING, playing.get() && !pauseRequested.get())
                    .putExtra(EXTRA_BLOCK, blockIndex)
                    .putExtra(EXTRA_POS, currentChapterPosMs())
                    .putExtra(EXTRA_DUR, chapterTotalMs())
                    .putExtra(EXTRA_REASON, reason)
            )
        } catch (_: Exception) {}
    }
}
