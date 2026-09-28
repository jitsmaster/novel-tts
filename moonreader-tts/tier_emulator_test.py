#!/usr/bin/env python3
#!/usr/bin/env python3
"""Emulator verification of the novel-tts TTS service-tier chain (TtsRouter).

Run on the Mac with the AVD `tts_test` booted and the debug APK installed:
    adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
    python3 tier_emulator_test.py

Simulates backend outages with the emulator's own firewall:
  * `iptables -I OUTPUT -p tcp --dport 443 -j REJECT` (needs `adb root`) =
    Edge TTS down, because wss://speech.platform.bing.com is port 443.
  * a server URL on a dead port (http://10.0.2.2:9999) = server tier down.
NOTE: every `-I` inserts a NEW rule; unblocking must delete each one, or the
tier silently stays down (the script re-lists and deletes them by number).

Drives the INSTALLED app over adb and asserts on logcat + uiautomator dumps.
Every check prints PASS/FAIL; the full report is also written to
/tmp/tier_test_results.txt.

Run:  python3 /tmp/tier_emulator_test.py
"""
import html, re, subprocess, sys, time

ADB = ["adb", "-s", "emulator-5554"]
APP = "com.dsh.noveltts"
MAIN = APP + "/.MainActivity"
READER = APP + "/.ReaderActivity"
GOOD = "http://10.0.2.2:8321"     # the Mac's TTS server, seen from the emulator
DEAD = "http://10.0.2.2:9999"     # nothing listening -> server tier fails fast
PHONE_DEFAULT = "http://100.85.43.11:8321"

results = []


def sh(args, timeout=240):
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout)


def adb(*a, **kw):
    return sh(ADB + list(a), **kw)


def shell(c, timeout=240):
    return sh(ADB + ["shell", c], timeout=timeout)


def logs(*tags):
    args = []
    for t in tags:
        args += ["-s", t]
    return adb("logcat", "-d", *args).stdout


def clear_log():
    adb("logcat", "-c")


def start(component, *extras):
    return shell("am start -n " + component + (" " + " ".join(extras) if extras else ""))


def stop():
    shell("am force-stop " + APP)


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS  " if ok else "FAIL  ") + name + (("  :: " + str(detail)) if detail else ""),
          flush=True)


def _block():
    shell("iptables -I OUTPUT 1 -p tcp --dport 443 -j REJECT")


def _unblock():
    for _ in range(8):
        out = shell("iptables -L OUTPUT -n --line-numbers").stdout
        num = None
        for line in out.splitlines():
            if "dpt:443" in line and "REJECT" in line:
                num = line.split()[0]
                break
        if num is None:
            return
        shell("iptables -D OUTPUT " + num)


def hook(server=None, tier=None, pin=None, probe=None, clear=False):
    ex = []
    if server:
        ex += ["--es", "server", server]
    if tier:
        ex += ["--es", "tier", tier]
    if pin is not None:
        ex += ["--ez", "pin", "true" if pin else "false"]
    if probe:
        ex += ["--ei", "probeSeconds", str(probe)]
    if clear:
        ex += ["-a", APP + ".CLEAR_CACHE"]
    return ex


def config_only(**kw):
    """Apply tier/URL settings, then kill the process (they persist)."""
    stop()
    time.sleep(1)
    start(MAIN, *hook(**kw))
    time.sleep(4)
    stop()
    time.sleep(1)


def play(limit, wait, tag_filter=("NovelTtsEngine", "NovelTtsRouter")):
    """Autoplay N sample sentences through the TTS engine, return the log tail."""
    clear_log()
    start(MAIN, "--ez", "autoplay", "true", "--ei", "limit", str(limit))
    time.sleep(wait)
    return logs(*tag_filter)


def status_dump():
    clear_log()
    start(MAIN, "-a", APP + ".STATUS")
    time.sleep(3)
    return logs("MainActivity")


def dump_ui(path=None):
    shell("uiautomator dump /sdcard/ui_t.xml >/dev/null")
    # uiautomator escapes non-ASCII as &#NNNNN; text=...; unescape so plain
    # substrings can be matched.
    return html.unescape(shell("cat /sdcard/ui_t.xml").stdout)


def scroll_to_voice_card():
    shell("input swipe 540 1800 540 500")
    time.sleep(2)


def radio_checked(xml, label):
    """True when a RadioButton whose text contains [label] is checked."""
    for node in re.findall(r"<node[^>]*>", xml):
        if label in node and 'checked="true"' in node:
            return True
    return False


def count(hay, needle):
    return hay.count(needle)


print("=== novel-tts TTS tier chain :: emulator verification ===", flush=True)
adb("root")
time.sleep(3)
adb("wait-for-device")
_unblock()   # clean any leftover rule from an earlier run
print("device ready; edge firewall clear", flush=True)

# ---------------------------------------------------------------- T1: server first
config_only(server=GOOD, tier="server", pin=False, clear=True)
log = play(3, 30)
check("T1 server-first: engine fetched from the server",
      count(log, "source=server") >= 3 and count(log, "source=edge") == 0
      and count(log, "source=google") == 0,
      "%d source=server, %d edge, %d google" % (count(log, "source=server"),
                                                count(log, "source=edge"),
                                                count(log, "source=google")))

# ------------------------------------------------- T2: 3 server failures -> Edge
config_only(server=DEAD, tier="server", pin=False, clear=True)
log = play(7, 60)
switched = "TTS 服务器 连续失败 3 次 → 切换到 Edge 在线语音" in log
check("T2a server down: 3 consecutive failures switch to Edge", switched)
check("T2b audio kept playing while the server failed (Edge carried it)",
      count(log, "source=edge") >= 4,
      "%d source=edge" % count(log, "source=edge"))
after = log.split("切换到 Edge 在线语音")[-1] if switched else log
check("T2c after the switch the dead server is not retried per sentence",
      "tier server failed" not in after,
      "%d further server attempts" % count(after, "tier server failed"))

# ------------------------------------- T3: server + Edge down -> Google (5 fails)
_block()
config_only(server=DEAD, tier="server", pin=False, clear=True)
log = play(12, 110)
check("T3a 5 consecutive Edge failures switch to Google TTS",
      "Edge 在线语音 连续失败 5 次 → 切换到 Google TTS（兜底）" in log)
check("T3b Google TTS rendered audio (last resort, offline engine)",
      count(log, "source=google") >= 1, "%d source=google" % count(log, "source=google"))
check("T3c probe target moved to Edge (5-minute recovery ping armed)",
      "probing edge every" in log)

# ------------------------------- T4: recovery walk Google -> Edge -> server
# 443 is still blocked at the start: the Edge ping must fail, then succeed.
stop()
time.sleep(1)
start(MAIN, *hook(server=GOOD, tier="google", pin=False, probe="10"))
time.sleep(25)
before = logs("NovelTtsRouter")
check("T4a while Google is in use, Edge is pinged and a dead Edge stays down",
      "probing edge every" in before and "probe edge -> false" in before,
      "%d pings" % count(before, "probe edge ->"))
_unblock()
time.sleep(30)
after = logs("NovelTtsRouter")
went_edge = "probe edge -> true" in after
check("T4b Edge comes back -> automatic switch to Edge", went_edge)
check("T4c Edge then pings the server and switches back to it",
      "probe server -> true" in after)
st = status_dump()
check("T4d final state after the recovery walk is the server tier",
      "当前服务：🖥 TTS 服务器" in st and "（已锁定）" not in st,
      [l.split("TIER| ")[-1] for l in st.splitlines() if "TIER| 当前服务" in l][:1])

# ------------------------------------------------- T5: built-in reader path
config_only(server=GOOD, tier="server", pin=False, clear=True)
clear_log()
start(READER, "--ez", "sample", "true", "--ez", "autoplay", "true", "--ei", "limit", "2")
time.sleep(45)
rlog = logs("NovelTtsAudio", "NovelTtsRouter")
check("T5 reader (AudioBookService) fetches through the tier router",
      count(rlog, "[fetch] source=server") >= 1,
      "%d source=server" % count(rlog, "[fetch] source=server"))
check("T5b reader UI shows the live service and how to switch",
      "语音服务：" in dump_ui("/sdcard/r.xml"), "")

# ---------------------------------------------------- T6: pre-render path
config_only(server=GOOD, tier="server", pin=False, clear=True)
clear_log()
start(MAIN, "-a", APP + ".PRERENDER", "--ei", "sampleCount", "6")
time.sleep(45)
plog = logs("PreRenderer", "NovelTtsRouter")
check("T6 pre-render renders through the same tier rules",
      "finished done=6" in plog and "failed=0" in plog,
      [l.split("PreRenderer: ")[-1] for l in plog.splitlines() if "finished" in l][:1])
# Regression: the parameters used to be formatted as "${int - 100}%"/"${int -
# 100}Hz", so a NEUTRAL pitch became the bare "0Hz". Edge requires a sign, the
# server's edge-tts rejected it and every such sentence silently landed on the
# Kokoro fallback (audio/wav instead of audio/mpeg).
check("T6b rate/pitch reach Edge with an explicit sign (+0%/+0Hz, not 0%/0Hz)",
      re.search(r"rate=[+-]\d+%", plog) is not None
      and re.search(r"pitch=[+-]\d+Hz", plog) is not None,
      [l.split("PreRenderer: ")[-1] for l in plog.splitlines() if "[pre] start" in l][:1])

# --------------------------------------------- T7: settings UI card + switching
config_only(server=GOOD, tier="server", pin=False)
start(MAIN)
time.sleep(4)
xml = dump_ui("/sdcard/m.xml")
ui_ok = ("当前服务：" in xml and "服务器" in xml and "Edge" in xml and "Google" in xml
         and "锁定当前服务" in xml and "立即探测" in xml)
check("T7 settings card shows the current service + manual tier buttons + lock", ui_ok)
check("T7b card explains the automatic rules",
      "连续失败 3 次转 Edge" in xml and "每 10 分钟探测服务器" in xml)
check("T7c the current tier is visibly selected in the radio group",
      radio_checked(xml, "TTS 服务器"), "checked radio for TTS 服务器")

# ------------------------------- T8: manual pick + lock + persistence + restore
config_only(tier="google", pin=True)
st = status_dump()
check("T8a manual lock sticks across a restart",
      "（已锁定）" in st and "Google" in st, [l for l in st.splitlines() if "TIER| 当前" in l][:1])
check("T8b auto switching is reported as off while locked", "已关闭（锁定中）" in st)

# --------------------------- V: voice selection is actually honored
# Regression: the app saved the voice but the engine synthesized Yunxi anyway
# (the client held the engine's hardcoded default voice).
stop()
time.sleep(1)
clear_log()
start(MAIN, "--es", "voice", "zh-CN-YunjianNeural",
      "--es", "clientVoice", "zh-CN-YunxiNeural",
      "--ez", "forceVoice", "true", "--ez", "autoplay", "true", "--ei", "limit", "1")
time.sleep(22)
vlog = logs("NovelTtsEngine", "MainActivity")
check("V1 the app's voice wins over the client's request (override on by default)",
      "voice=zh-CN-YunjianNeural" in vlog and "voice override:" in vlog,
      [l.split(": ")[-1] for l in vlog.splitlines() if "voice override:" in l][:1])

stop()
time.sleep(1)
clear_log()
start(MAIN, "--ez", "forceVoice", "false", "--es", "clientVoice", "zh-CN-YunxiNeural",
      "--ez", "autoplay", "true", "--ei", "limit", "1")
time.sleep(22)
vlog = logs("NovelTtsEngine")
check("V2 with the override off the client's own voice wins",
      "voice=zh-CN-YunxiNeural" in vlog,
      [l.split("NovelTtsEngine: ")[-1][:60] for l in vlog.splitlines() if "[perf] req" in l][:1])

stop()
time.sleep(1)
start(MAIN, "--es", "voice", "zh-CN-XiaobeiNeural", "--ez", "forceVoice", "true")
time.sleep(4)
xml = dump_ui()
sel = radio_checked(xml, "Xiaobei（成熟女声）")
if not sel:                                    # below the fold: scroll it in
    scroll_to_voice_card()
    xml = dump_ui()
    sel = radio_checked(xml, "Xiaobei（成熟女声）")
check("V3 the voice radio group shows the saved voice as selected", sel,
      "Xiaobei radio checked" if sel else "no checked Xiaobei radio in the dump")

# ------------------------------- V4/V5: the ENGINE follows the voice NAME
# The server speaks its own Kokoro model by default; Edge is used only when an
# Edge voice (zh-CN-*Neural) is picked on purpose. Regression: every voice used
# to come from Edge because the app only knew Edge names.
stop()
time.sleep(1)
clear_log()
start(MAIN, "--es", "voice", "zf_xiaobei", "--ez", "forceVoice", "true",
      "--ez", "autoplay", "true", "--ei", "limit", "1")
time.sleep(22)
vlog = logs("NovelTtsEngine", "NovelTtsRouter")
check("V4 a local Kokoro voice is requested as such (server speaks it itself)",
      "voice=zf_xiaobei" in vlog,
      [l.split("NovelTtsEngine: ")[-1][:58] for l in vlog.splitlines() if "[perf] req" in l][:1])
check("V4b the local voice is served without touching a phone-side tier",
      "source=server" in vlog and "source=edge" not in vlog and "source=google" not in vlog,
      [l.split("NovelTtsEngine: ")[-1][:58] for l in vlog.splitlines() if "[perf] fetch" in l][:1])

stop()
time.sleep(1)
start(MAIN, "--es", "voice", "zf_xiaobei", "--ez", "forceVoice", "true")
time.sleep(4)
xml = dump_ui()
sel = radio_checked(xml, "Xiaobei 女声·成熟 · 本地")
if not sel:
    scroll_to_voice_card()
    xml = dump_ui()
    sel = radio_checked(xml, "Xiaobei 女声·成熟 · 本地")
check("V5 the LOCAL Xiaobei radio is checked (not the Edge one)", sel,
      "local Xiaobei radio checked" if sel else "no checked local Xiaobei radio")
check("V5b the picker offers the full catalogue (Kokoro v1.0 / v1.1-zh / Edge)",
      "本地 Kokoro v1.0（8）" in xml and "本地 Kokoro v1.1-zh（100）" in xml
      and "Edge 在线（8）" in xml,
      "sections found" if "本地 Kokoro v1.1-zh（100）" in xml else "sections missing")

# cleanup / restore the app to its normal state on this emulator
config_only(server=PHONE_DEFAULT, tier="server", pin=False)
shell("am start -n %s --es voice zm_yunxi --ez forceVoice true" % MAIN)
time.sleep(3)
stop()
_unblock()
st = status_dump()
check("T9 restored: server-first, unpinned, no firewall rules left",
      "当前服务：🖥 TTS 服务器" in st and "（已锁定）" not in st
      and "dpt:443" not in shell("iptables -L OUTPUT -n").stdout)

passed = sum(1 for _, ok, _ in results if ok)
total = len(results)
lines = ["%s %s%s" % ("PASS" if ok else "FAIL", name, ("  :: " + str(d)) if d else "")
         for name, ok, d in results]
report = "\n".join(lines) + "\n\n%d/%d checks passed\n" % (passed, total)
open("/tmp/tier_test_results.txt", "w").write(report)
print("\n=== SUMMARY: %d/%d checks passed ===" % (passed, total), flush=True)
for l in lines:
    if l.startswith("FAIL"):
        print(l, flush=True)
print("report: /tmp/tier_test_results.txt", flush=True)
