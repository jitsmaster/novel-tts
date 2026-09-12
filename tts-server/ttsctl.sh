#!/bin/bash
#
# ttsctl.sh - control the DSH Novel TTS server from the terminal.
#
#   ./ttsctl.sh restart              restart the server (default command)
#   ./ttsctl.sh start | stop         bring it up / take it down
#   ./ttsctl.sh status               mode, PID, health, which tier is serving
#   ./ttsctl.sh health               raw /health JSON
#   ./ttsctl.sh voices               voice routing per tier (Edge vs Kokoro)
#   ./ttsctl.sh logs [n]             tail the server log (default 40 lines)
#   ./ttsctl.sh tail                 follow the log until Ctrl-C
#
# Options:
#   --port N       port to manage              (default 8321)
#   --manual       force the plain-process path instead of the LaunchDaemon
#   --dry-run      print what would happen, change nothing
#   --timeout N    seconds to wait for health  (default 90)
#   -h, --help
#
# The server is normally owned by the com.dsh.noveltts LaunchDaemon in
# /Library/LaunchDaemons, and restarting a system daemon needs admin rights -
# this script asks for sudo only in that case. If no daemon plist is installed
# (or --manual is given) it manages a plain background uvicorn process instead,
# which is also how you run a throwaway instance:  ./ttsctl.sh --manual --port 8327 restart
set -uo pipefail

PORT=8321
FORCE_MANUAL=0
DRY_RUN=0
HEALTH_TIMEOUT=90
CMD=""

LABEL="com.dsh.noveltts"
PLIST="/Library/LaunchDaemons/${LABEL}.plist"
DIR="$(cd "$(dirname "$0")" && pwd)"
PYTHON="${DIR}/.venv-local/bin/python"
DAEMON_LOG="/tmp/${LABEL}.out.log"
DAEMON_ERR="/tmp/${LABEL}.err.log"
MANUAL_LOG=""
MANUAL_PIDFILE=""
LOG_LINES=40

c_ok()   { printf '\033[32m%s\033[0m\n' "$*"; }
c_warn() { printf '\033[33m%s\033[0m\n' "$*"; }
c_err()  { printf '\033[31m%s\033[0m\n' "$*" >&2; }
info()   { printf '  %s\n' "$*"; }

usage() { sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

# ---- argument parsing --------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage ;;
        --port)    PORT="${2:-}"; shift 2 ;;
        --timeout) HEALTH_TIMEOUT="${2:-}"; shift 2 ;;
        --manual)  FORCE_MANUAL=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        restart|start|stop|status|health|voices|logs|tail)
                   CMD="$1"; shift ;;
        -*)        c_err "unknown option: $1"; usage ;;
        *)         if [ -z "$CMD" ]; then CMD="$1"; shift; else LOG_LINES="$1"; shift; fi ;;
        # (a bare number after a command = how many log lines)
    esac
done
[ -n "$CMD" ] || CMD="restart"

if [ "$PORT" != "8321" ]; then FORCE_MANUAL=1; fi   # a non-standard port is never the daemon
MANUAL_LOG="/tmp/noveltts-manual-${PORT}.log"
MANUAL_PIDFILE="/tmp/noveltts-manual-${PORT}.pid"
BASE_URL="http://127.0.0.1:${PORT}"

# ---- helpers -----------------------------------------------------------------
daemon_mode() { [ "$FORCE_MANUAL" -eq 0 ] && [ -f "$PLIST" ]; }

pid_on_port() { lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1; }

# Can we actually drive launchd from here? Passwordless sudo, or an
# interactive terminal where the password can be typed.
launchctl_usable() {
    sudo -n true 2>/dev/null && return 0
    [ -t 0 ] && return 0
    return 1
}

sudo_run() {
    if [ "$DRY_RUN" -eq 1 ]; then
        c_warn "  [dry-run] sudo $*"
        return 0
    fi
    if ! sudo -n true 2>/dev/null; then
        c_warn "  (admin rights needed for the LaunchDaemon - you may be asked for your password)"
    fi
    sudo "$@"
}

health_ok() { curl -sS -m 3 -o /dev/null "${BASE_URL}/health" 2>/dev/null; }

# wait_health [old_pid] - when old_pid is given, a still-answering old process
# does NOT count as a successful restart (it raced us before it exited).
wait_health() {
    local old_pid="${1:-}" now
    local waited=0 step=2
    while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
        if health_ok; then
            now="$(pid_on_port)"
            if [ -z "$old_pid" ] || [ "$now" != "$old_pid" ]; then
                c_ok "server is up after ${waited}s (pid ${now})"
                return 0
            fi
        fi
        sleep "$step"; waited=$((waited + step))
    done
    c_err "server did NOT answer ${BASE_URL}/health within ${HEALTH_TIMEOUT}s"
    info "last log lines:"
    tail -n 15 "$(log_path)" 2>/dev/null | sed 's/^/    /'
    return 1
}

log_path() { if daemon_mode; then echo "$DAEMON_LOG"; else echo "$MANUAL_LOG"; fi; }

tailscale_ip() {
    local ts="/Applications/Tailscale.app/Contents/MacOS/Tailscale"
    [ -x "$ts" ] && "$ts" ip -4 2>/dev/null | head -1
}

# ---- commands ----------------------------------------------------------------
cmd_start() {
    if daemon_mode; then
        if ! launchctl_usable; then
            if [ -n "$(pid_on_port)" ]; then
                c_ok "already running (pid $(pid_on_port)) - nothing to start"
                return 0
            fi
            c_err "loading the LaunchDaemon needs admin rights and sudo is unavailable here"
            info "run it yourself:  sudo launchctl bootstrap system ${PLIST}"
            return 1
        fi
        # kickstart works on an already-loaded job; bootstrap loads it if it isn't.
        if ! sudo_run launchctl kickstart -k "system/${LABEL}" 2>/dev/null; then
            sudo_run launchctl bootstrap system "$PLIST"
        fi
    else
        if [ -n "$(pid_on_port)" ]; then
            c_warn "already listening on :${PORT} (pid $(pid_on_port)) - use restart"
            return 0
        fi
        if [ ! -x "$PYTHON" ]; then
            c_err "python not found: $PYTHON"
            info "is the external volume mounted? (${DIR} lives on it)"
            return 1
        fi
        if [ "$DRY_RUN" -eq 1 ]; then
            c_warn "  [dry-run] (cd \"$DIR\" && nohup \"$PYTHON\" -m uvicorn tts_server:app --host 0.0.0.0 --port $PORT >> \"$MANUAL_LOG\" 2>&1 &)"
            return 0
        fi
        ( cd "$DIR" && nohup "$PYTHON" -m uvicorn tts_server:app --host 0.0.0.0 --port "$PORT" \
            >> "$MANUAL_LOG" 2>&1 & echo $! > "$MANUAL_PIDFILE" )
        info "started manual server on :${PORT} (log $MANUAL_LOG)"
    fi
    wait_health
}

cmd_stop() {
    if daemon_mode; then
        # Unloading stops it for good; KeepAlive would otherwise restart it.
        if ! launchctl_usable; then
            c_err "stopping the LaunchDaemon (not just killing it) needs admin rights"
            info "run it yourself:  sudo launchctl bootout system/${LABEL}"
            return 1
        fi
        sudo_run launchctl bootout "system/${LABEL}"
        c_ok "daemon unloaded (start/restart will load it again)"
    else
        local pid; pid="$(pid_on_port)"
        [ -z "$pid" ] && pid="$(cat "$MANUAL_PIDFILE" 2>/dev/null)"
        if [ -z "$pid" ]; then
            c_warn "nothing listening on :${PORT}"
            return 0
        fi
        if [ "$DRY_RUN" -eq 1 ]; then
            c_warn "  [dry-run] kill $pid (TERM, then KILL after 15s)"
            return 0
        fi
        info "stopping pid $pid ..."
        kill "$pid" 2>/dev/null
        local waited=0
        while [ "$waited" -lt 15 ] && kill -0 "$pid" 2>/dev/null; do sleep 1; waited=$((waited + 1)); done
        if kill -0 "$pid" 2>/dev/null; then
            c_warn "  still alive after 15s - sending SIGKILL"
            kill -9 "$pid" 2>/dev/null
        fi
        rm -f "$MANUAL_PIDFILE"
        c_ok "stopped"
    fi
}

cmd_restart() {
    if daemon_mode; then
        local pid; pid="$(pid_on_port)"
        if launchctl_usable; then
            info "restarting LaunchDaemon ${LABEL} (launchctl kickstart -k)"
            sudo_run launchctl kickstart -k "system/${LABEL}"
            [ "$DRY_RUN" -eq 1 ] && return 0
        elif [ -n "$pid" ]; then
            # Launched as this user, so a plain SIGTERM is enough: KeepAlive
            # respawns it with whatever is on disk now. No sudo needed.
            info "sudo not available here; SIGTERM to pid ${pid} (launchd KeepAlive respawns it)"
            if [ "$DRY_RUN" -eq 1 ]; then c_warn "  [dry-run] kill ${pid}"; return 0; fi
            kill "$pid" || { c_err "kill ${pid} failed"; return 1; }
            # Let the old process actually exit first, or its still-open socket
            # answers /health and the restart looks done while it is not.
            local gone=0
            while [ "$gone" -lt 30 ] && kill -0 "$pid" 2>/dev/null; do sleep 1; gone=$((gone + 1)); done
            [ "$gone" -ge 30 ] && c_warn "  pid ${pid} still alive after 30s"
        else
            c_warn "nothing listening on :${PORT}; trying to load the job"
            sudo_run launchctl bootstrap system "$PLIST"
            [ "$DRY_RUN" -eq 1 ] && return 0
        fi
        wait_health "$pid"
    else
        info "restarting manual server on :${PORT}"
        cmd_stop
        [ "$DRY_RUN" -eq 1 ] && return 0
        sleep 1
        cmd_start
    fi
}

cmd_health() { curl -sS -m 5 "${BASE_URL}/health" || c_err "no answer from ${BASE_URL}/health"; echo; }

cmd_voices() { curl -sS -m 5 "${BASE_URL}/voices" || c_err "no answer from ${BASE_URL}/voices"; echo; }

cmd_status() {
    echo "DSH Novel TTS server"
    info "mode:      $(daemon_mode && echo "LaunchDaemon (${PLIST})" || echo "manual process")"
    info "dir:       $DIR"
    info "port:      ${PORT}"
    info "url:       $(tailscale_ip | sed "s|\$|:${PORT}|")  (and http://localhost:${PORT})"
    local pid; pid="$(pid_on_port)"
    if [ -z "$pid" ]; then
        c_err "  state:     NOT LISTENING"
        [ -f "$(log_path)" ] && { info "last log lines:"; tail -n 10 "$(log_path)" | sed 's/^/    /'; }
        return 1
    fi
    info "pid:       $pid  ($(ps -o comm= -p "$pid" 2>/dev/null | head -c 60))"
    local h; h="$(curl -sS -m 5 "${BASE_URL}/health" 2>/dev/null)"
    if [ -z "$h" ]; then
        c_err "  health:    NO ANSWER (process alive but not serving?)"
        return 1
    fi
    python3 - "$h" <<'PY'
import json, sys
try:
    d = json.loads(sys.argv[1])
except Exception:
    print("  health:    (unparsable)", sys.argv[1][:120]); raise SystemExit
print(f"  health:    {d.get('status')}   kokoro_loaded={d.get('kokoro')}")
st = d.get("stats") or {}
if st:
    print(f"  served:    cache={st.get('cache_hit',0)}  edge_ok={st.get('edge_ok',0)}  "
          f"edge_fail={st.get('edge_fail',0)}  kokoro_ok={st.get('kokoro_ok',0)}  "
          f"kokoro_fail={st.get('kokoro_fail',0)}")
to = d.get("timeouts") or {}
if to:
    print(f"  timeouts:  edge={to.get('edge')}s handler={to.get('edge_handler')}s kokoro={to.get('kokoro')}s")
PY
    local cache="${DIR}/cache"
    if [ -d "$cache" ]; then
        local n; n="$(ls -1 "$cache" 2>/dev/null | wc -l | tr -d ' ')"
        local sz; sz="$(du -sh "$cache" 2>/dev/null | cut -f1)"
        info "cache:     ${n} files, ${sz}"
    fi
    info "log:       $(log_path)"
}

cmd_logs() { tail -n "${1:-40}" "$(log_path)"; }
cmd_tail() { tail -f "$(log_path)"; }

case "$CMD" in
    start)   cmd_start ;;
    stop)    cmd_stop ;;
    restart) cmd_restart ;;
    status)  cmd_status ;;
    health)  cmd_health ;;
    voices)  cmd_voices ;;
    logs)    cmd_logs "${LOG_LINES:-40}" ;;
    tail)    cmd_tail ;;
    *) c_err "unknown command: $CMD"; usage ;;
esac
