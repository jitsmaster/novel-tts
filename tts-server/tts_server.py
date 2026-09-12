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
from concurrent.futures import ThreadPoolExecutor

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

# ---- Timeouts / worker pools -------------------------------------------------
# edge-tts can hang on a half-open websocket, and asyncio.wait_for() CANNOT
# cancel a thread. So Edge calls run on their own bounded pool and the timeout
# lives INSIDE the worker (see edge_synthesize), where the thread can actually
# unwind and free its slot. The old code ran Edge on the default executor,
# where ~min(32, cpu+4) stuck threads would consume every worker and stall the
# Kokoro fallback too - the server then looks "stuck" while /health still
# answers instantly.
EDGE_TIMEOUT = 8.0            # real cap inside the worker thread
EDGE_HANDLER_TIMEOUT = 10.0   # request-level cap (worker unwinds a bit sooner)
KOKORO_TIMEOUT = 30.0
EDGE_WORKERS = 4
KOKORO_WORKERS = 4

_edge_pool = ThreadPoolExecutor(max_workers=EDGE_WORKERS, thread_name_prefix="edge-tts")
_kokoro_pool = ThreadPoolExecutor(max_workers=KOKORO_WORKERS, thread_name_prefix="kokoro")

# Cheap counters so "is the server serving, and from which tier?" is answerable
# from /health instead of guesswork.
_stats = {"cache_hit": 0, "edge_ok": 0, "edge_fail": 0, "kokoro_ok": 0, "kokoro_fail": 0}


def _bump(key: str) -> None:
    try:
        _stats[key] += 1
    except KeyError:
        _stats[key] = 1

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

# Kokoro-82M ships only THREE zh speakers in the locally cached snapshot:
#   zf_xiaobei, zm_yunjian, zm_yunxi
# Asking it for any other voice makes it try to download that speaker from the
# HuggingFace Hub, which raises under HF_HUB_OFFLINE=1 and surfaced as a bare
# 500 "both backends failed" (three of the six names above died this way).
# Edge honours all six, so the map keeps them - only the local fallback needs
# a stand-in.
KOKORO_VOICES = {"zf_xiaobei", "zm_yunjian", "zm_yunxi"}

# Same-gender stand-in for requested voices the local model has no speaker for.
KOKORO_SUBSTITUTE = {
    "zf_xiaoxiao": "zf_xiaobei",
    "zf_xiaoyi": "zf_xiaobei",
    "zm_yunyang": "zm_yunjian",
}


def resolve_kokoro_voice(edge_voice: str) -> tuple:
    """Map an Edge voice name to a Kokoro voice that actually exists locally.

    Returns (kokoro_voice, substituted_from): substituted_from is the voice that
    was asked for when a stand-in had to be used, else None."""
    wanted = VOICE_MAP.get(edge_voice, VOICE_MAP[DEFAULT_VOICE])
    if wanted in KOKORO_VOICES:
        return wanted, None
    return KOKORO_SUBSTITUTE.get(wanted, VOICE_MAP[DEFAULT_VOICE]), wanted

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

        async def _consume():
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    buf.write(chunk["data"])

        # Bounded INSIDE the worker thread: cancelling the consumer makes the
        # websocket's `async with` unwind, so the thread exits and releases its
        # pool slot. A wait_for() at the call site could never do that.
        await asyncio.wait_for(_consume(), timeout=EDGE_TIMEOUT)

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
        "timeouts": {
            "edge": EDGE_TIMEOUT,
            "edge_handler": EDGE_HANDLER_TIMEOUT,
            "kokoro": KOKORO_TIMEOUT,
        },
        "workers": {"edge": EDGE_WORKERS, "kokoro": KOKORO_WORKERS},
        "stats": dict(_stats),
    }


@app.get("/voices")
async def voices():
    """Voice routing per tier: Edge honours every name in edge_to_kokoro; the
    Kokoro fallback only has kokoro_available, and substitutes for the rest."""
    return {
        "edge_to_kokoro": VOICE_MAP,
        "kokoro_available": sorted(KOKORO_VOICES),
        "kokoro_substitutes": KOKORO_SUBSTITUTE,
        "default": DEFAULT_VOICE,
    }


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
        _bump("cache_hit")
        return Response(content=cached, media_type=media)

    loop = asyncio.get_running_loop()

    # 2) Edge TTS, on its own bounded pool (see the pools section at the top).
    try:
        data = await asyncio.wait_for(
            loop.run_in_executor(_edge_pool, edge_synthesize, text, voice, rate, pitch),
            timeout=EDGE_HANDLER_TIMEOUT,
        )
        _cache_put(key, "mp3", data)
        _bump("edge_ok")
        return Response(content=data, media_type="audio/mpeg")
    except Exception as e:  # throttled / timeout / broken -> local fallback
        # Never silent: an invisible fallback made a merely degraded Edge
        # indistinguishable from a broken server.
        _bump("edge_fail")
        print(f"[tts] edge failed ({type(e).__name__}: {e}); falling back to kokoro", flush=True)

    # 3) Kokoro local fallback
    kokoro_voice, substituted = resolve_kokoro_voice(voice)
    if substituted:
        print(
            f"[tts] no local Kokoro speaker for '{substituted}'; using '{kokoro_voice}'",
            flush=True,
        )
    try:
        data = await asyncio.wait_for(
            loop.run_in_executor(
                _kokoro_pool, kokoro_synthesize, text, kokoro_voice, _edge_rate_to_pct(rate)
            ),
            timeout=KOKORO_TIMEOUT,
        )
        _cache_put(key, "wav", data)
        _bump("kokoro_ok")
        return Response(content=data, media_type="audio/wav")
    except Exception as e:
        _bump("kokoro_fail")
        print(f"[tts] kokoro failed ({type(e).__name__}: {e})", flush=True)
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
