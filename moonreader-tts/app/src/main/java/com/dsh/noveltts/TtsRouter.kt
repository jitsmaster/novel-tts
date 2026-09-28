package com.dsh.noveltts

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Tiered TTS service router — the single place that decides WHICH backend
 * synthesizes a sentence and WHEN to move to another one.
 *
 * Priority and rules (user-specified):
 *   1. The DSH TTS server on the Mac is the preferred tier.
 *   2. 3 consecutive server failures  -> switch to Edge TTS (phone direct).
 *   3. 5 consecutive Edge failures    -> switch to the Google TTS engine
 *      (the device's own fallback voice).
 *   4. While EDGE is the home tier, ping the server every 10 minutes; when the
 *      ping succeeds, switch back to the server.
 *   5. While GOOGLE is the home tier, ping Edge every 5 minutes; when the ping
 *      succeeds, switch back to Edge — and Edge then starts pinging the server
 *      every 10 minutes, so the chain walks all the way back to the top.
 *
 * A single request never goes silent just because the home tier is down: it
 * walks down the remaining tiers so audio still plays, while the home tier's
 * consecutive-failure counter keeps accumulating (3 / 5 failures before the
 * home tier itself moves).
 *
 * The same rules apply after a MANUAL selection: picking a tier by hand makes
 * it the home tier and arms the counters and probes from there. [select] with
 * pin=true additionally freezes the choice (no automatic switching at all).
 *
 * Observable state for the UI: [snapshot] / [shortLabel], plus a package-scoped
 * broadcast ([ACTION_TIER]) on every change.
 */
object TtsRouter {

    private const val TAG = "NovelTtsRouter"

    /** Broadcast to in-app UI (activities) whenever the service changes. */
    const val ACTION_TIER = "com.dsh.noveltts.TIER"
    const val EXTRA_TIER = "tier"
    const val EXTRA_REASON = "reason"

    /** Consecutive home-tier failures before the home tier steps down. */
    const val SERVER_FAILS_BEFORE_EDGE = 3
    const val EDGE_FAILS_BEFORE_GOOGLE = 5

    /** Recovery ping intervals (rule 4 / rule 5). */
    const val SERVER_PROBE_INTERVAL_MS = 10 * 60_000L   // while on EDGE
    const val EDGE_PROBE_INTERVAL_MS = 5 * 60_000L      // while on GOOGLE

    /** Tier that synthesizes. SERVER is the top priority. */
    enum class Tier(val label: String, val cn: String, val short: String, val icon: String) {
        SERVER("server", "TTS 服务器", "服务器", "🖥"),
        EDGE("edge", "Edge 在线语音", "Edge", "☁"),
        GOOGLE("google", "Google TTS（兜底）", "Google", "🤖");

        companion object {
            fun from(s: String?): Tier? {
                val t = s?.trim() ?: return null
                return Tier.values().firstOrNull { it.name.equals(t, ignoreCase = true) }
            }
        }
    }

    /** Audio came from [tier]. */
    class Fetch(val bytes: ByteArray, val tier: Tier)

    // ---- state --------------------------------------------------------------

    @Volatile
    var home: Tier = Tier.SERVER
        private set

    /** Consecutive failures of the CURRENT home tier. */
    @Volatile
    var failures: Int = 0
        private set

    /** True = user froze the choice: no automatic switching, no probing. */
    @Volatile
    var pinned: Boolean = false
        private set

    /** True = the current tier was picked by hand. */
    @Volatile
    var manual: Boolean = false
        private set

    @Volatile
    var lastReason: String = "启动：TTS 服务器优先"
        private set

    @Volatile
    var lastSwitchAt: Long = System.currentTimeMillis()
        private set

    @Volatile
    var lastProbe: String = "尚未探测"
        private set

    @Volatile
    var nextProbeAt: Long = 0L
        private set

    @Volatile
    private var appContext: Context? = null

    /**
     * Test hook (adb): overrides BOTH ping intervals, in ms. 0 = the real
     * 5-minute (Edge) / 10-minute (server) intervals.
     */
    @Volatile
    var probeIntervalOverrideMs: Long = 0L

    private val sched: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "tts-probe").apply { isDaemon = true }
        }

    @Volatile
    private var probeTask: ScheduledFuture<*>? = null

    /** Binds the router to the app and restores the persisted tier. Idempotent. */
    fun init(context: Context) {
        val c = context.applicationContext
        if (appContext != null) return
        appContext = c
        // The reader (AudioBookService) may be the first component to run in a
        // fresh process; without this it would keep ServerTtsClient's hardcoded
        // default URL instead of the user's setting.
        ServerTtsClient.baseUrl = Settings.serverUrl(c)
        val saved = Tier.from(Settings.tier(c))
        if (saved != null) home = saved
        pinned = Settings.tierPinned(c)
        if (pinned) lastReason = "已锁定：${home.cn}"
        Log.i(TAG, "init: home=${home.label} pinned=$pinned")
        // Scheduled ONCE per process. Doing it on every init() call (i.e. on
        // every fetch) would restart the interval each sentence and the ping
        // would never actually run.
        scheduleProbes()
    }

    // ---- synthesis ----------------------------------------------------------

    /**
     * Synthesizes [text] with the home tier first, then (if it fails) with the
     * tiers below it, so a downed backend never mutes reading. Returns null
     * only when every allowed tier failed.
     *
     * Must be called OFF the main thread (network + engine waits).
     */
    fun fetch(
        context: Context,
        text: String,
        voice: String,
        ratePct: String,
        pitchHz: String,
    ): Fetch? {
        init(context)
        for (tier in chain()) {
            try {
                val bytes = attempt(context, tier, text, voice, ratePct, pitchHz)
                onSuccess(tier)
                return Fetch(bytes, tier)
            } catch (e: Exception) {
                onFailure(tier, e)
            }
        }
        return null
    }

    /** Home tier first, then the tiers below it. A pinned tier is used alone. */
    private fun chain(): List<Tier> = when (home) {
        Tier.SERVER -> if (pinned) listOf(Tier.SERVER)
                       else listOf(Tier.SERVER, Tier.EDGE, Tier.GOOGLE)
        Tier.EDGE -> if (pinned) listOf(Tier.EDGE) else listOf(Tier.EDGE, Tier.GOOGLE)
        // Google is the last resort, but a best-effort Edge retry costs nothing
        // and can recover a sentence before the 5-minute ping would.
        Tier.GOOGLE -> if (pinned) listOf(Tier.GOOGLE) else listOf(Tier.GOOGLE, Tier.EDGE)
    }

    private fun attempt(
        context: Context,
        tier: Tier,
        text: String,
        voice: String,
        ratePct: String,
        pitchHz: String,
    ): ByteArray = when (tier) {
        Tier.SERVER -> ServerTtsClient.synthesize(text, voice, ratePct, pitchHz).first
        Tier.EDGE -> {
            // Edge has no speaker for a LOCAL Kokoro voice, so this tier would
            // fail outright and drop the sentence to Google. Use the Edge voice
            // with the same persona instead - this is the resilience path (the
            // server already failed 3 times), not a switch away from local.
            val edgeVoice = Voices.LOCAL_TO_EDGE[voice]
            if (edgeVoice != null) Log.i(TAG, "Edge stands in for local voice $voice -> $edgeVoice")
            EdgeTtsClient.synthesize(text, edgeVoice ?: voice, ratePct, pitchHz)
        }
        Tier.GOOGLE -> GoogleTtsClient.synthesize(context, text, ratePct, pitchHz)
    }

    @Synchronized
    private fun onSuccess(tier: Tier) {
        if (tier == home) {
            if (failures != 0) {
                failures = 0
                Log.i(TAG, "${home.label} recovered (failure counter reset)")
                notifyUi()
            }
            return
        }
        // A tier ABOVE the home tier just worked (e.g. Edge while Google is
        // home): promote immediately — the recovery ping would only do the
        // same thing later.
        if (tier.ordinal < home.ordinal && !pinned) {
            Log.i(TAG, "${tier.label} works -> promoting home from ${home.label}")
            setHome(tier, "${home.cn} 失败，${tier.cn} 可用 → 切回 ${tier.cn}", manual = false)
        }
    }

    @Synchronized
    private fun onFailure(tier: Tier, e: Exception) {
        Log.w(TAG, "tier ${tier.label} failed: ${e.message}")
        if (pinned || tier != home) return
        val limit = failLimit(home)
        if (limit <= 0) return
        failures++
        val next = lower(home)
        if (next != null && failures >= limit) {
            val reason = "${home.cn} 连续失败 $failures 次 → 切换到 ${next.cn}"
            Log.w(TAG, reason)
            setHome(next, reason, manual = false)
        } else {
            notifyUi()
        }
    }

    private fun failLimit(t: Tier): Int = when (t) {
        Tier.SERVER -> SERVER_FAILS_BEFORE_EDGE
        Tier.EDGE -> EDGE_FAILS_BEFORE_GOOGLE
        Tier.GOOGLE -> 0            // nothing below: Google is the last resort
    }

    private fun lower(t: Tier): Tier? = when (t) {
        Tier.SERVER -> Tier.EDGE
        Tier.EDGE -> Tier.GOOGLE
        Tier.GOOGLE -> null
    }

    // ---- manual selection ---------------------------------------------------

    /**
     * Manual selection (UI). [tier] becomes the home tier with a fresh failure
     * counter; the SAME automatic rules then apply from there (3 / 5 failures
     * step down, the recovery ping steps back up). With [pin] the choice is
     * frozen instead: only that tier is used and no ping runs.
     */
    @Synchronized
    fun select(context: Context, tier: Tier, pin: Boolean = false) {
        init(context)
        pinned = pin
        manual = true
        val why = if (pin) "手动锁定 ${tier.cn}" else "手动选择 ${tier.cn}"
        setHome(tier, why, manual = true)
    }

    /** Releases a pin without changing the tier. */
    @Synchronized
    fun unpin(context: Context) {
        init(context)
        if (!pinned) return
        pinned = false
        lastReason = "解除锁定（恢复自动切换）"
        persist()
        scheduleProbes()
        notifyUi()
    }

    /** True when [tier] can actually synthesize on this device (UI check). */
    fun available(context: Context, tier: Tier): Boolean = try {
        when (tier) {
            Tier.SERVER, Tier.EDGE -> true
            Tier.GOOGLE -> GoogleTtsClient.available(context)
        }
    } catch (e: Exception) {
        false
    }

    @Synchronized
    private fun setHome(tier: Tier, reason: String, manual: Boolean) {
        home = tier
        failures = 0
        this.manual = manual
        lastReason = reason
        lastSwitchAt = System.currentTimeMillis()
        persist()
        scheduleProbes()
        notifyUi()
    }

    private fun persist() {
        val c = appContext ?: return
        Settings.setTier(c, home.name)
        Settings.setTierPinned(c, pinned)
        // Keep the legacy "force server" flag in sync: deploy_phone.sh and the
        // adb hook still write it, and a stale `true` would silently re-pin the
        // server tier on the next launch after the user unlocked it.
        Settings.setForceServer(c, pinned)
    }

    private fun notifyUi() {
        val c = appContext ?: return
        try {
            val i = Intent(ACTION_TIER)
                .setPackage(c.packageName)
                .putExtra(EXTRA_TIER, home.name)
                .putExtra(EXTRA_REASON, lastReason)
            c.sendBroadcast(i)
        } catch (e: Exception) {
            Log.w(TAG, "broadcast failed: ${e.message}")
        }
    }

    // ---- recovery pings -----------------------------------------------------

    /**
     * While EDGE is home, ping the SERVER every 10 minutes.
     * While GOOGLE is home, ping EDGE every 5 minutes.
     */
    @Synchronized
    private fun scheduleProbes() {
        probeTask?.cancel(false)
        probeTask = null
        nextProbeAt = 0L
        if (pinned) return
        val target = probeTarget() ?: return
        val real = if (target == Tier.SERVER) SERVER_PROBE_INTERVAL_MS else EDGE_PROBE_INTERVAL_MS
        val interval = probeIntervalOverrideMs.takeIf { it > 0 } ?: real
        nextProbeAt = System.currentTimeMillis() + interval
        probeTask = sched.scheduleWithFixedDelay(
            { runProbe(target, interval) }, interval, interval, TimeUnit.MILLISECONDS
        )
        Log.i(TAG, "probing ${target.label} every ${interval / 60_000} min")
    }

    /** The tier we are waiting to come back. */
    private fun probeTarget(): Tier? = when (home) {
        Tier.EDGE -> Tier.SERVER
        Tier.GOOGLE -> Tier.EDGE
        Tier.SERVER -> null          // top tier: nothing to wait for
    }

    private fun runProbe(target: Tier, interval: Long) {
        val c = appContext ?: return
        nextProbeAt = System.currentTimeMillis() + interval
        val ok = try {
            probe(c, target)
        } catch (e: Exception) {
            Log.i(TAG, "probe ${target.label} failed: ${e.message}")
            false
        }
        lastProbe = if (ok) "${target.cn} 可用" else "${target.cn} 仍不可用"
        Log.i(TAG, "probe ${target.label} -> $ok (home=${home.label})")
        if (ok) {
            synchronized(this) {
                if (!pinned && probeTarget() == target) {
                    setHome(target, "恢复探测成功：已自动切回 ${target.cn}", manual = false)
                }
            }
        } else {
            notifyUi()      // refresh the "next ping" line in the UI
        }
    }

    /** One ping. Throws on failure. */
    private fun probe(c: Context, target: Tier): Boolean = when (target) {
        Tier.SERVER -> ServerTtsClient.health()
        Tier.EDGE -> {
            // Same mapping as attempt(): a local voice name would make the ping
            // fail and the chain would never walk back up to Edge.
            val v = Voices.LOCAL_TO_EDGE[Settings.voice(c)] ?: Settings.voice(c)
            EdgeTtsClient.synthesize("你好", v, "+0%", "+0Hz", 8000L)
            true
        }
        else -> false
    }

    /**
     * Runs a ping right now (UI button / adb) and applies the same promote
     * rule as the scheduled ping. Returns a human-readable result.
     */
    fun probeNow(context: Context, tier: Tier? = null): String {
        init(context)
        val target = tier ?: probeTarget() ?: Tier.SERVER
        val ok = try {
            probe(context, target)
        } catch (e: Exception) {
            false
        }
        lastProbe = if (ok) "${target.cn} 可用" else "${target.cn} 不可用"
        if (ok && !pinned && target.ordinal < home.ordinal) {
            setHome(target, "手动探测成功：已切回 ${target.cn}", manual = false)
        } else {
            notifyUi()
        }
        return lastProbe
    }

    // ---- UI helpers ---------------------------------------------------------

    /** One-line status for the reader / notifications. */
    fun shortLabel(): String = "${home.icon} ${home.cn}" + if (pinned) "（已锁定）" else ""

    /** Multi-line status for the settings screen. */
    fun snapshot(): String {
        val sb = StringBuilder()
        sb.append("当前服务：").append(home.icon).append(' ').append(home.cn)
        if (manual) sb.append("（手动）")
        if (pinned) sb.append("（已锁定）")
        sb.append('\n')
        val limit = failLimit(home)
        sb.append("连续失败：").append(failures)
        if (limit > 0) sb.append(" / ").append(limit).append(" 次后切换 ")
            .append(lower(home)?.cn ?: "")
        else sb.append("（最后兜底，无更低层级）")
        sb.append('\n')
        when {
            pinned -> sb.append("自动切换：已关闭（锁定中）")
            home == Tier.SERVER -> sb.append("自动切换：服务器优先 · 失败 3 次转 Edge · Edge 失败 5 次转 Google")
            else -> {
                val remain = nextProbeAt - System.currentTimeMillis()
                sb.append("正在探测：")
                sb.append(if (home == Tier.EDGE) "TTS 服务器（每 10 分钟）" else "Edge（每 5 分钟）")
                if (remain > 0) {
                    sb.append(" · 下次约 ")
                    sb.append((remain + 59_999) / 60_000).append(" 分钟后")
                }
            }
        }
        sb.append('\n')
        sb.append("最近：").append(lastReason).append(" · 探测：").append(lastProbe)
        return sb.toString()
    }
}
