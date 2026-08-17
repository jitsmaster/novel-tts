#!/bin/bash
# Start (or restart) the DSH Novel TTS server on the M4 Mac mini.
# Listens on 0.0.0.0:8321 (reachable via Tailscale IP 100.85.43.11:8321).
cd "$(dirname "$0")"
exec .venv-local/bin/python -m uvicorn tts_server:app --host 0.0.0.0 --port 8321
