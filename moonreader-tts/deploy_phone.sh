#!/usr/bin/env bash
# Deploy Novel TTS to a connected Android device (phone or emulator) and point
# it at a TTS server, with no UI taps on the device.
#
#   ./deploy_phone.sh [serial] [server-url] [force-server]
#
# Defaults:
#   serial       the single connected device in `adb devices`
#   server-url   http://100.85.43.11:8321   (the Mac mini TTS server)
#   force-server false  (leave the automatic tier chain ON: server first, then
#                        Edge after 3 server failures, then Google TTS after 5
#                        Edge failures, with the failed tier pinged back online)
#
# What it does:
#   1. builds app-debug.apk if it is missing
#   2. installs it
#   3. makes com.dsh.noveltts the system default TTS engine
#   4. writes the server URL (+ the pin-server flag) through the app's adb hooks
#   5. prints the app's stored settings back, so the write is verified
#
# force-server=true LOCKS the server tier (no automatic switching, no recovery
# pings) — debug only; the tier can also be changed any time from the app's
# 语音服务 card or the reader's service line.
#
# Smoke test (renders 3 bundled sentences through the server and greps the
# engine log for source=server):
#
#   ./deploy_phone.sh --smoke
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
apk="$root/app/build/outputs/apk/debug/app-debug.apk"

smoke=false
args=()
for a in "$@"; do
    if [[ "$a" == "--smoke" ]]; then smoke=true; else args+=("$a"); fi
done
serial="${args[0]:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
server="${args[1]:-http://100.85.43.11:8321}"
force="${args[2]:-false}"

if [[ -z "$serial" ]]; then
    echo "error: no device in 'adb devices'. Connect the phone with USB debugging on," >&2
    echo "       or use wireless debugging (adb pair <ip>:<port> <code>)." >&2
    exit 1
fi

if [[ ! -f "$apk" ]]; then
    echo "building $apk ..."
    ( cd "$root" && JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}" \
        ./gradlew :app:assembleDebug --console=plain -q )
fi

echo "device: $serial"
echo "server: $server"
echo "force-server: $force"

adb -s "$serial" install -r "$apk"
adb -s "$serial" shell settings put secure tts_default_synth com.dsh.noveltts
adb -s "$serial" shell am force-stop com.dsh.noveltts
adb -s "$serial" shell am start -n com.dsh.noveltts/.MainActivity \
    --es server "$server" --ez forceServer "$force" >/dev/null
sleep 2
adb -s "$serial" shell am force-stop com.dsh.noveltts

echo "--- stored settings ---"
adb -s "$serial" shell run-as com.dsh.noveltts cat /data/data/com.dsh.noveltts/shared_prefs/novel_tts_prefs.xml

if [[ "$smoke" == true ]]; then
    echo "--- smoke test: 3 sentences through the server ---"
    # clear the on-device sentence cache first, or the sentences come back as
    # source=cache and prove nothing about the server
    adb -s "$serial" shell am start -n com.dsh.noveltts/.MainActivity -a com.dsh.noveltts.CLEAR_CACHE >/dev/null
    sleep 3
    adb -s "$serial" shell am force-stop com.dsh.noveltts
    adb -s "$serial" logcat -c
    adb -s "$serial" shell am start -n com.dsh.noveltts/.MainActivity \
        --ez autoplay true --ei limit 3 >/dev/null
    sleep 30
    # [perf] fetch ... source=server|edge|google shows which tier served each
    # sentence; NovelTtsRouter logs the tier's failure counter, every switch
    # (连续失败 N 次 → 切换 ...) and every recovery ping (probe ...).
    adb -s "$serial" logcat -d -s NovelTtsEngine NovelTtsRouter \
        | grep -E "\[perf\] fetch|tier .* failed|切换|probing|probe " || true
    adb -s "$serial" shell am start -n com.dsh.noveltts/.MainActivity -a com.dsh.noveltts.STATUS >/dev/null
    sleep 3
    echo "--- tier state on the device ---"
    adb -s "$serial" logcat -d -s MainActivity | grep "TIER|" || true
fi
