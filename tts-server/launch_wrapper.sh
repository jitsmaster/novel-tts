#!/bin/bash
# Novel TTS server launch wrapper (used by the com.dsh.noveltts LaunchDaemon).
#
# Why this exists: the server binary, venv and working directory all live on the
# external volume /Volumes/Satechi 1. At system boot, launchd fires RunAtLoad
# before that volume is necessarily mounted, so the original plist failed to
# spawn (EX_CONFIG) and the server stayed down for hours until something
# re-loaded the job. This wrapper lives on the BOOT volume (always present),
# waits for the external volume to appear, then exec's the real server.
#
# On timeout (volume never appears), it exits non-zero and launchd's KeepAlive
# retries the whole wrapper — so the server comes up as soon as the drive is
# plugged in / mounted, with no manual intervention.
set -u

SERVER_DIR="/Volumes/Satechi 1/Dev/novel-tts/tts-server"
PYTHON="$SERVER_DIR/.venv-local/bin/python"
LOG="/tmp/com.dsh.noveltts.wrapper.log"
# ~10 minutes of waiting before handing back to launchd for a retry.
MAX_WAIT_SECONDS=600
CHECK_INTERVAL=2

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

log "wrapper started (pid $$); waiting for $PYTHON ..."

waited=0
while [ "$waited" -lt "$MAX_WAIT_SECONDS" ]; do
    if [ -x "$PYTHON" ]; then
        log "found $PYTHON after ${waited}s; starting server"
        cd "$SERVER_DIR" || { log "FATAL: cd $SERVER_DIR failed"; exit 1; }
        exec "$PYTHON" -m uvicorn tts_server:app --host 0.0.0.0 --port 8321
    fi
    sleep "$CHECK_INTERVAL"
    waited=$((waited + CHECK_INTERVAL))
done

log "TIMEOUT after ${waited}s: $PYTHON never appeared; exiting for launchd retry"
exit 1
