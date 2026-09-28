# DSH Novel TTS — Moon Reader Chinese audiobook engine

Self-hosted Chinese TTS for reading novels in Moon Reader on Android.

## Architecture (fully automatic service tiers, no config switches)

```
Moon Reader (or the built-in reader)
   └─ Android TTS engine (com.dsh.noveltts, app/)
        ├─ 0. SQLite sentence cache        (instant replay, no network)
        └─ TtsRouter tiers, in priority order:
             1. DSH TTS server on the Mac (http://100.85.43.11:8321)   ← preferred
                  └─ speaks its own LOCAL Kokoro models (108 Mandarin voices,
                     no internet); Edge TTS only for a voice picked as such
             2. Edge TTS, direct from the phone (wss://speech.platform.bing.com)
             3. Google TTS on the device (com.google.android.tts)      ← last resort
```

Rules (`TtsRouter` — the single place that picks a backend for LIVE playback,
pre-render and the standalone reader alike):

- **3 consecutive server failures → switch to Edge. 5 consecutive Edge failures →
  switch to the Google TTS engine.** A failure counter is reset by any success of
  the current tier.
- A single request never goes silent: it walks down the remaining tiers, so the
  sentence still plays while the current tier's failure counter accumulates.
- **Recovery pings**: while Edge is in use the server is pinged every 10 minutes;
  while Google is in use Edge is pinged every 5 minutes. A successful ping switches
  back up (Google → Edge, then Edge → server), so the chain walks all the way back
  to the preferred server on its own.
- **Manual override**: the app's “语音服务” card and the reader's service line show
  the tier in use and let the user pick one. A manual pick becomes the new tier and
  the same rules apply from there; “lock” freezes the pick (no switching, no pings).
  The choice is persisted across restarts.
- Verified end-to-end on the emulator (AVD `tts_test`): server→Edge at 3 failures,
  Edge→Google at 5, both recovery walks, manual pick + lock, and the reader /
  pre-render paths (`source=server|edge|google|cache`).

## Server (Mac mini, M4)

- Deps: Python 3.12 in a local venv (`.venv-local`). The Python runtime is a copy of
  the uv-managed 3.12 binary stored in `python-runtime/` — kept LOCAL because launchd
  hangs on the uv-python living on the home volume. Do not delete `python-runtime/`.
- Rebuild venv if needed:
  `uv venv --python ./python-runtime/bin/python3.12 .venv-local`
  `uv pip install --python .venv-local/bin/python pip edge-tts kokoro soundfile "misaki[zh]" fastapi uvicorn`
- Manual start: `./tts-server/start_server.sh`  (uvicorn, 0.0.0.0:8321)
- **Auto-start (launchd)**: `com.dsh.noveltts` LaunchDaemon runs the server at
  **system boot, before any login** (`/Library/LaunchDaemons/com.dsh.noveltts.plist`;
  source kept in `tts-server/`, runs as user `satechi`).
  - `RunAtLoad` = starts at boot; `KeepAlive` = auto-restarts on crash.
  - Install / re-install (needs admin): `sudo ./tts-server/install_daemon.sh`
    (also installs the launch wrapper below).
  - Reload after editing: `sudo launchctl bootout system/com.dsh.noveltts; sudo launchctl bootstrap system /Library/LaunchDaemons/com.dsh.noveltts.plist`
  - Sets `HF_HUB_OFFLINE=1` / `TRANSFORMERS_OFFLINE=1` (models are cached; no network at boot).
  - **Launch wrapper** (`/Library/LaunchDaemons/com.dsh.noveltts.wrapper.sh`,
    source `tts-server/launch_wrapper.sh`): the server lives on the external
    volume `/Volumes/Satechi 1`, which may not be mounted yet when launchd fires
    at boot. The wrapper (installed on the boot volume, always present) waits up
    to 10 min for the drive to appear, then exec's the server; on timeout it
    exits and launchd retries. The server therefore comes up automatically once
    the drive is available — no manual reload needed. Wrapper log:
    `/tmp/com.dsh.noveltts.wrapper.log`.
- Endpoints: `GET /tts?text=…&voice=…&rate=…&pitch=…&engine=auto|kokoro|edge`
  → audio/wav (local Kokoro) or audio/mpeg (Edge); `GET /health`, `GET /voices`
- **Voices — which engine speaks follows the voice NAME:**
  `zm_yunxi`, `zf_xiaobei`, `zf_001`, `zm_010` … are local Kokoro speakers (the
  default), `zh-CN-YunxiNeural` … are Edge voices and are used only when one of
  those is asked for. `engine=` overrides it per request, and an unknown name is
  a 400 instead of being silently replaced by a different voice.
- `rate`/`pitch` follow Edge's syntax and must be **signed** (`+0%`, `-15%`, `+0Hz`).
  edge-tts rejects a bare `0Hz`, and the tier then silently falls back to Kokoro;
  the server normalises a missing sign, and the app writes signed values
  (`EdgeTtsClient.signedPct` / `signedHz`).
- Per-sentence disk cache in `tts-server/cache/`
- **108 Mandarin voices, all local**: 8 from `hexgrad/Kokoro-82M` plus the 100
  speakers of `hexgrad/Kokoro-82M-v1.1-zh`. The daemon runs with
  `HF_HUB_OFFLINE=1`, so a voice pack that is not already cached cannot be
  fetched at runtime (that is why a voice used to speak as a same-gender stand-in,
  and why an unknown name spoke Yunxi). To (re)download them:
  `cd tts-server && .venv-local/bin/python fetch_voices.py` (`--list` reports only).
  The 800 MB of checkpoints stay in the HuggingFace cache; only the ~500 KB voice
  packs belong to this step.
- Kokoro benchmark on this M4 (post-warmup): RTF 0.11–0.13 ≈ 9× realtime; a 5 s
  sentence renders in ~0.6 s. First sentence ~1.7–2.6 s (warmup, pre-warmed at boot).

## Android app

- Kotlin, no AndroidX; OkHttp only. Build: `./gradlew :app:assembleDebug` (needs JDK 17).
- `TtsEngineService` — Android TTS engine (modern String-based API), Moon Reader selects it.
  - **Media integration**: a MediaSession makes earphone buttons (play/pause/stop/next)
    control the TTS "like music"; audio focus pauses the TTS when other media plays and
    pauses music when the TTS starts. Pause/stop HOLDS the current utterance so Moon
    Reader never advances (no missed content on resume).
- `EdgeTtsClient` — faithful Kotlin port of the edge-tts protocol (Sec-MS-GEC Long math,
  WSS + SSML, binary frame parse `[2-byte len][headers][\r\n\r\n][mp3]`), validated live.
- `TtsRouter` — the tier state machine (see Architecture): priority, failure
  thresholds, recovery pings, manual pick / lock, and the persisted choice. Every
  audio path (engine, reader, pre-render) fetches through it, so there is exactly
  one definition of "which service is in use".
- `GoogleTtsClient` — last-resort tier. Renders with the device's own engine
  (`com.google.android.tts`, else the system default when it is not this app, else
  Pico) via `synthesizeToFile`, so the audio enters the SAME decode → cache →
  playback path as the other tiers and keeps pause/stop/audio-focus behaviour.
- **Why there is no in-engine prefetch queue**: Android's TTS framework delivers one
  utterance at a time (`TextToSpeechService.SynthHandler` serializes them on one
  synthesis thread and auto-completes an utterance the moment `onSynthesizeText`
  returns without `done()`), so an engine can never see the NEXT sentence's text
  while the current one plays. Per-sentence generation latency therefore sits
  between sentences — measured ~0.6–0.9 s (server/Kokoro render) on a cold
  sentence, ~0.1 s on a server-cache hit, ~10 ms on a phone-cache hit.
- `PreRenderer` — a "text → audio" queue that runs AHEAD of playback (the only
  layer that can: it owns the source text). Paste a passage (or `adb shell am
  start -n com.dsh.noveltts/.MainActivity -a com.dsh.noveltts.PRERENDER --ei
  sampleCount 120`) and it renders each sentence into the phone sentence cache
  through the same tier rules + keys as live playback, so a later Moon Reader pass
  over the same text is served from cache (measured: 613 ms → 13 ms fetch per
  sentence). Moon Reader compatibility caveat: cache keys are byte-exact
  `voice|rate|pitch|text`, so pre-rendered text must match Moon Reader's
  utterance strings byte-for-byte (same source text incl. leading indents).
  Moon Reader chunks one 。！？-terminated sentence per utterance.
- MainActivity — settings + diagnostics: bundled 三國志演義 excerpt (3088
  sentences), per-sentence latency, rate/pitch sliders, a voice radio group
  (all 108 local Kokoro speakers + the 8 Edge ones; the 100 v1.1-zh speakers sit
  in their own collapsible section, and the voice actually in use is shown above
  the list),
  sentence-cache size/clear, Pre-render card, editable server URL, and the
  **语音服务 card**: the tier in use right now, why it switched, the failure
  counter, the next ping, a service radio group (服务器 / Edge / Google, one
  always checked, the current tier marked), a lock switch and an "立即探测"
  button. Both radio groups re-read the saved choices on resume, so they cannot
  show a stale selection after a change made in the reader. Test hooks via intent extras:
  `--ez autoplay true --ei limit N` (play first N sample sentences),
  `-a com.dsh.noveltts.CLEAR_CACHE`, `-a com.dsh.noveltts.STATUS` (dump the tier
  state to logcat as `TIER|`), `--es tier <server|edge|google> --ez pin <bool>`,
  `--ei probeSeconds N` (shorten the recovery ping — test only), `--ez probeNow true`.
- **Tier regression test**: `python3 moonreader-tts/tier_emulator_test.py` (AVD
  `tts_test` booted, debug APK installed) drives all five rules + both recovery
  walks + the UI over adb and asserts on logcat. Last run: 19/19 checks passed.
- **Library bookshelf**: 📂 in the reader opens your books folder (picked
  once via the system folder picker, defaulting to /sdcard/Books where Moon
  Reader keeps novels) and lists every .txt/.epub as a tappable row — no more
  digging through the system "Recents" sheet, and switching books is one tap.
  Parsed books are cached on disk, so re-opening is near-instant.
- **Standalone audiobook reader (no Moon Reader)** — `ReaderActivity` +
  `AudioBookService`: open any `.txt` novel (system picker), auto chapter
  parsing, reads PARAGRAPH-SIZED blocks through its own AudioTrack player (no
  Android TTS framework), so playback is gapless, pausable at any instant, and
  seekable by previous/next block. Earphone buttons map: play/pause/stop +
  prev/next block (double/triple-click or rewind/fast-forward keys). Foreground
  service keeps reading with the screen off; position is saved per book.
- Perf instrumentation: the engine logs one line per stage per utterance
  (`[perf] req/fetch/decode/play/done` with ms + a punctuation profile), tag
  `NovelTtsEngine` — the raw data behind the numbers above.
- Playback path: decode to 24 kHz mono → upmix to stereo (the framework always
  creates a stereo AudioTrack; feeding mono makes it play 2x fast + high-pitched).
  No manual resampling — Android's pipeline converts 24→48 kHz with high quality.
- Engine registration needs `CATEGORY_DEFAULT` in the TTS service intent-filter
  (getEngines uses MATCH_DEFAULT_ONLY) — the missing piece that silently fell back
  to Google TTS otherwise.
- Android blocks cleartext HTTP by default → `usesCleartextTraffic=true` (server tier is plain HTTP on tailnet).

## Voices

| Edge (primary)          | Kokoro fallback |
|-------------------------|-----------------|
| zh-CN-YunxiNeural (default) | zm_yunxi     |
| zh-CN-YunjianNeural     | zm_yunjian      |
| zh-CN-XiaobeiNeural     | zf_xiaobei      |

## Phone test (S23 Ultra)

1. Connect S23 via USB with USB debugging enabled.
2. `adb install app/build/outputs/apk/debug/app-debug.apk`
3. Settings → Accessibility → Text-to-speech → preferred engine → "Novel TTS Engine".
4. In the test app set Server URL to `http://100.85.43.11:8321` (Tailscale) — or use
   the LAN IP if the phone is on the same network.
5. Moon Reader → open a novel → 朗读 (TTS) → select the engine if prompted.
