#!/bin/bash
# Switch the Novel TTS server from a login LaunchAgent to a boot LaunchDaemon.
#
#   sudo ./install_daemon.sh
#
# - Installs /Library/LaunchDaemons/com.dsh.noveltts.plist (runs as 'satechi')
# - RunAtLoad fires at SYSTEM BOOT (before any login); KeepAlive restarts on crash
# - Boots out and removes the old per-user LaunchAgent (would fight over :8321)
# - If the external volume is not mounted yet at boot, launchd retries every
#   few seconds (KeepAlive), so the server comes up once the drive is there.
set -euo pipefail

ORIG_USER="${SUDO_USER:-$(whoami)}"
ORIG_UID="$(id -u "$ORIG_USER")"
ORIG_HOME="$(dscl . -read "/Users/$ORIG_USER" NFSHomeDirectory 2>/dev/null | awk '{print $2}')"
AGENT_PLIST="${ORIG_HOME:-$HOME}/Library/LaunchAgents/com.dsh.noveltts.plist"

# 1) Stop and remove the old per-user LaunchAgent so it can't fight over :8321.
if launchctl print "gui/$ORIG_UID/com.dsh.noveltts" >/dev/null 2>&1; then
  echo "-> booting out old LaunchAgent (gui/$ORIG_UID/com.dsh.noveltts)"
  launchctl bootout "gui/$ORIG_UID/com.dsh.noveltts" || true
fi
if [ -f "$AGENT_PLIST" ]; then
  echo "-> removing $AGENT_PLIST"
  rm -f "$AGENT_PLIST"
fi

# 2) Install the LaunchDaemon.
SRC="$(cd "$(dirname "$0")" && pwd)/com.dsh.noveltts.plist"
DEST="/Library/LaunchDaemons/com.dsh.noveltts.plist"
echo "-> installing $DEST"
cp "$SRC" "$DEST"
chown root:wheel "$DEST"
chmod 644 "$DEST"

# 3) Load it in the system domain (fires now, and at every boot).
launchctl bootout system/com.dsh.noveltts 2>/dev/null || true
launchctl bootstrap system "$DEST"

echo
echo "OK - daemon installed and started."
echo "Check:  launchctl print system/com.dsh.noveltts | head -15"
echo "Health: curl http://100.85.43.11:8321/health"
