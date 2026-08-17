#!/usr/bin/env python3
"""
DSH Novel TTS server for the M4 Mac mini.

Cascade per request (fully automatic, no config switches):
  1. Serve from disk cache if present (sentence-hash keyed).
  2. Try Edge TTS (free, best quality). On ANY failure (403/429/timeout/
     empty audio) fall through.
  3. Fall back to local Kokoro-82M (zh voices mirror the Edge names), so
     throttling never breaks reading.

Endpoints:
  GET /tts?text=...&voice=...&rate=...&pitch=...  -> audio/mpeg (Edge) or audio/wav (Kokoro)
  GET /health                                    -> status + model info
  GET /voices                                    -> supported voice mapping

Run: uvicorn tts_server:app --host 0.0.0.0 --port 8321
"""
from __future__ import annotations

import asyncio
import hashlib
import io
import os
import time

# Models are fully cached on disk; never phone home to HuggingFace (this also
# avoids launchd sandbox network stalls at startup).
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
from typing import Optional

import edge_tts
import soundfile as sf
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import Response

CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cache")
os.makedirs(CACHE_DIR, exist_ok=True)

SAMPLE_RATE = 24000

# Edge voice -> Kokoro voice mapping (names mirror each other).
VOICE_MAP = {
    "zh-CN-YunxiNeural": "zm_yunxi",     # young lively male
    "zh-CN-YunjianNeural": "zm_yunjian", # deep documentary male
    "zh-CN-XiaobeiNeural": "zf_xiaobei", # mature female
    "zh-CN-XiaoxiaoNeural": "zf_xiaoxiao",
    "zh-CN-YunyangNeural": "zm_yunyang",
    "zh-CN-XiaoyiNeural": "zf_xiaoyi",
}
DEFAULT_VOICE = "zh-CN-YunxiNeural"

app = FastAPI(title="DSH Novel TTS", version="1.0.0")

_pipeline = None
import threading
_pipeline_lock = threading.Lock()

# ---- Kokoro helpers ---------------------------------------------------------

def _get_pipeline():
    global _pipeline
    if _pipeline is None:
        with _pipeline_lock:
            if _pipeline is None:
                from kokoro import KPipeline
                _pipeline = KPipeline(lang_code="z")
    return _pipeline


def kokoro_synthesize(text: str, voice: str, rate_pct: int = 0) -> bytes:
    """Synthesize with Kokoro, return WAV bytes (24 kHz mono 16-bit)."""
    pipe = _get_pipeline()
    # Edge rate "+X%" -> speed multiplier. Edge rate is in percent of nominal
    # speed; positive = faster. Kokoro speed=1.0 is nominal.
    speed = 1.0 + rate_pct / 100.0
    speed = max(0.5, min(2.0, speed))
    chunks = []
    for result in pipe(text, voice=voice, speed=speed):
        chunks.append(result.audio)
    if not chunks:
        raise RuntimeError("Kokoro produced no audio")
    import torch
    audio = torch.cat(chunks).cpu().numpy()
    buf = io.BytesIO()
    sf.write(buf, audio, SAMPLE_RATE, format="WAV", subtype="PCM_16")
    return buf.getvalue()


# ---- Edge helpers -----------------------------------------------------------

def _edge_rate_to_pct(rate: str) -> int:
    try:
        return int(rate.replace("%", ""))
    except ValueError:
        return 0


def edge_synthesize(text: str, voice: str, rate: str, pitch: str) -> bytes:
    """Edge TTS via edge-tts, returns MP3 bytes. Raises on any failure."""
    rate_pct = _edge_rate_to_pct(rate)
    if rate_pct > 0:
        rate = f"+{rate_pct}%"
    elif rate_pct < 0:
        rate = f"{rate_pct}%"
    else:
        rate = "+0%"
    # Normalize pitch (already like "+0Hz")
    if not pitch.endswith("Hz"):
        pitch = "+0Hz"
    buf = io.BytesIO()

    async def _run():
        communicate = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch, volume="+0%")
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                buf.write(chunk["data"])

    asyncio.run(_run())
    data = buf.getvalue()
    if len(data) < 100:
        raise RuntimeError("Edge returned no audio")
    return data


# ---- Cache ------------------------------------------------------------------

def _cache_key(text: str, voice: str, rate: str, pitch: str) -> str:
    h = hashlib.sha256(f"{voice}|{rate}|{pitch}|{text}".encode("utf-8")).hexdigest()
    return h


def _cache_path(key: str, ext: str) -> str:
    return os.path.join(CACHE_DIR, f"{key}.{ext}")


def _cache_get(key: str):
    for ext, media in (("mp3", "audio/mpeg"), ("wav", "audio/wav")):
        p = _cache_path(key, ext)
        if os.path.exists(p):
            with open(p, "rb") as f:
                return f.read(), media
    return None, None


def _cache_put(key: str, ext: str, data: bytes) -> None:
    with open(_cache_path(key, ext), "wb") as f:
        f.write(data)


# ---- Endpoints --------------------------------------------------------------

@app.get("/health")
async def health():
    return {
        "status": "ok",
        "kokoro": _pipeline is not None,
        "edge": "available",
        "voices": VOICE_MAP,
        "sample_rate": SAMPLE_RATE,
        "cache_dir": CACHE_DIR,
    }


@app.get("/voices")
async def voices():
    return VOICE_MAP


@app.get("/tts")
async def tts(
    text: str = Query(..., description="Text to synthesize (one sentence)"),
    voice: str = Query(DEFAULT_VOICE),
    rate: str = Query("+0%"),
    pitch: str = Query("+0Hz"),
):
    if not text.strip():
        raise HTTPException(400, "empty text")
    if voice not in VOICE_MAP:
        # Allow any zh-CN Edge voice even if not pre-mapped; Kokoro fallback
        # then uses the closest mapped voice or default.
        pass

    key = _cache_key(text, voice, rate, pitch)

    # 1) Cache
    cached, media = _cache_get(key)
    if cached:
        return Response(content=cached, media_type=media)

    # 2) Edge TTS (with a timeout guard)
    try:
        data = await asyncio.wait_for(
            asyncio.to_thread(edge_synthesize, text, voice, rate, pitch),
            timeout=20,
        )
        _cache_put(key, "mp3", data)
        return Response(content=data, media_type="audio/mpeg")
    except Exception as e:  # throttled / timeout / broken -> local fallback
        pass

    # 3) Kokoro local fallback
    kokoro_voice = VOICE_MAP.get(voice, VOICE_MAP[DEFAULT_VOICE])
    try:
        data = await asyncio.wait_for(
            asyncio.to_thread(kokoro_synthesize, text, kokoro_voice, _edge_rate_to_pct(rate)),
            timeout=30,
        )
        _cache_put(key, "wav", data)
        return Response(content=data, media_type="audio/wav")
    except Exception as e:
        raise HTTPException(500, f"both backends failed: {e}")


# Pre-warm Kokoro in the background so uvicorn binds immediately and the first
# real request isn't slow. Non-blocking: if the sandbox/network stalls the model
# load, the API is still up and Kokoro becomes ready whenever it finishes.
@app.on_event("startup")
async def startup():
    import asyncio as _asyncio
    import threading as _threading

    def _warm():
        try:
            _get_pipeline()
            print("[startup] Kokoro pipeline loaded", flush=True)
        except Exception as e:
            print(f"[startup] Kokoro warmup failed (will retry lazily): {e}", flush=True)

    _threading.Thread(target=_warm, daemon=True).start()
