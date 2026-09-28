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

# ---- Voice catalogue ---------------------------------------------------------
#
# WHICH ENGINE SPEAKS FOLLOWS THE VOICE NAME, so the LOCAL model is the default
# and Edge is only ever used when an Edge voice is actually asked for:
#
#   zm_yunxi / zf_xiaobei / zf_001 / zm_010 …   local Kokoro   (no network)
#   zh-CN-YunxiNeural / …                       Microsoft Edge (online)
#   engine=kokoro|edge|auto                     per-request override
#
# Two things used to make the audio a mystery, both gone:
#   * a missing local speaker was silently replaced by a same-gender one
#     (asking for Xiaoxiao spoke Xiaobei), and
#   * a Kokoro voice name was unknown, so it fell through to the DEFAULT
#     speaker - every new voice name spoke Yunxi.
# Every Chinese speaker of both Kokoro repos is now downloaded, and an unknown
# name is reported instead of being substituted.

# Edge (online) voices and the local speaker each one mirrors.
EDGE_TO_KOKORO = {
    "zh-CN-YunxiNeural": "zm_yunxi",      # young lively male
    "zh-CN-YunjianNeural": "zm_yunjian",  # deep documentary male
    "zh-CN-YunxiaNeural": "zm_yunxia",    # boyish male
    "zh-CN-YunyangNeural": "zm_yunyang",  # news-anchor male
    "zh-CN-XiaobeiNeural": "zf_xiaobei",  # mature female
    "zh-CN-XiaoxiaoNeural": "zf_xiaoxiao",# warm female
    "zh-CN-XiaoyiNeural": "zf_xiaoyi",    # lively female
    "zh-CN-XiaoniNeural": "zf_xiaoni",    # girlie female
}
KOKORO_TO_EDGE = {v: k for k, v in EDGE_TO_KOKORO.items()}

# Kokoro-82M: 8 Mandarin speakers (worth preferring - best trained).
KOKORO_V1 = [
    "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi",
    "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
]
# Kokoro-82M-v1.1-zh: the 100 Mandarin speakers added on top of that model.
KOKORO_V11 = [
        "zf_001", "zf_002", "zf_003", "zf_004", "zf_005", "zf_006",
        "zf_007", "zf_008", "zf_017", "zf_018", "zf_019", "zf_021",
        "zf_022", "zf_023", "zf_024", "zf_026", "zf_027", "zf_028",
        "zf_032", "zf_036", "zf_038", "zf_039", "zf_040", "zf_042",
        "zf_043", "zf_044", "zf_046", "zf_047", "zf_048", "zf_049",
        "zf_051", "zf_059", "zf_060", "zf_067", "zf_070", "zf_071",
        "zf_072", "zf_073", "zf_074", "zf_075", "zf_076", "zf_077",
        "zf_078", "zf_079", "zf_083", "zf_084", "zf_085", "zf_086",
        "zf_087", "zf_088", "zf_090", "zf_092", "zf_093", "zf_094",
        "zf_099", "zm_009", "zm_010", "zm_011", "zm_012", "zm_013",
        "zm_014", "zm_015", "zm_016", "zm_020", "zm_025", "zm_029",
        "zm_030", "zm_031", "zm_033", "zm_034", "zm_035", "zm_037",
        "zm_041", "zm_045", "zm_050", "zm_052", "zm_053", "zm_054",
        "zm_055", "zm_056", "zm_057", "zm_058", "zm_061", "zm_062",
        "zm_063", "zm_064", "zm_065", "zm_066", "zm_068", "zm_069",
        "zm_080", "zm_081", "zm_082", "zm_089", "zm_091", "zm_095",
        "zm_096", "zm_097", "zm_098", "zm_100",
]
KOKORO_REPO_V1 = "hexgrad/Kokoro-82M"
KOKORO_REPO_V11 = "hexgrad/Kokoro-82M-v1.1-zh"
KOKORO_REPO = {v: KOKORO_REPO_V1 for v in KOKORO_V1}
KOKORO_REPO.update({v: KOKORO_REPO_V11 for v in KOKORO_V11})

# Local model first: this is what "use the TTS server" means on the phone.
DEFAULT_VOICE = "zm_yunxi"

KOKORO_WAV = "audio/wav"
EDGE_MP3 = "audio/mpeg"

app = FastAPI(title="DSH Novel TTS", version="1.0.0")

_pipelines = {}
import threading
_pipeline_lock = threading.Lock()

# ---- Kokoro helpers ---------------------------------------------------------

def _get_pipeline(repo_id: str):
    """One KPipeline per model repo (v1.0 and v1.1-zh are separate checkpoints)."""
    pipe = _pipelines.get(repo_id)
    if pipe is None:
        with _pipeline_lock:
            pipe = _pipelines.get(repo_id)
            if pipe is None:
                from kokoro import KPipeline
                pipe = KPipeline(lang_code="z", repo_id=repo_id)
                _pipelines[repo_id] = pipe
    return pipe


def kokoro_voice_name(voice: str) -> str:
    """The local speaker for [voice], or "" when this server has no such voice."""
    if voice in KOKORO_REPO:
        return voice
    return EDGE_TO_KOKORO.get(voice, "")


def kokoro_synthesize(text: str, voice: str, rate_pct: int = 0) -> bytes:
    """Synthesize with Kokoro, return WAV bytes (24 kHz mono 16-bit)."""
    speaker = kokoro_voice_name(voice)
    if not speaker:
        raise ValueError(f"no local Kokoro speaker for '{voice}'")
    pipe = _get_pipeline(KOKORO_REPO[speaker])
    # Edge rate "+X%" -> speed multiplier. Edge rate is in percent of nominal
    # speed; positive = faster. Kokoro speed=1.0 is nominal.
    speed = 1.0 + rate_pct / 100.0
    speed = max(0.5, min(2.0, speed))
    chunks = []
    for result in pipe(text, voice=speaker, speed=speed):
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

def _signed(value: str, unit: str) -> str:
    """Edge wants an explicit sign AND unit: "0Hz" -> "+0Hz", "-15%" -> "-15%".

    A bare value without the sign (or the unit) would make edge-tts reject the
    request, which silently degraded every such sentence to the Kokoro fallback.
    """
    v = (value or "").strip()
    if v and v[0] not in "+-":
        v = "+" + v
    if not v.endswith(unit):
        v = (v or "+0") + unit
    return v


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
        "kokoro": "available",
        "kokoro_models_loaded": sorted(_pipelines),
        "kokoro_voices": len(KOKORO_V1) + len(KOKORO_V11),
        "edge": "available",
        "default_voice": DEFAULT_VOICE,
        "default_engine": "kokoro",
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
    """The full voice catalogue: local Kokoro speakers first (the default
    engine), Edge voices second (only used when one of these names is asked
    for, or when engine=edge)."""
    return {
        "default": DEFAULT_VOICE,
        "default_engine": "kokoro",
        "kokoro": {
            "repo_v1": KOKORO_REPO_V1,
            "repo_v11_zh": KOKORO_REPO_V11,
            "voices_v1": list(KOKORO_V1),
            "voices_v11_zh": list(KOKORO_V11),
            "count": len(KOKORO_V1) + len(KOKORO_V11),
        },
        "edge": {
            "voices": sorted(EDGE_TO_KOKORO),
            "mirrors": EDGE_TO_KOKORO,
        },
    }


@app.get("/tts")
async def tts(
    text: str = Query(..., description="Text to synthesize (one sentence)"),
    voice: str = Query(DEFAULT_VOICE, description="local Kokoro speaker (zm_yunxi, zf_001, …) or Edge voice (zh-CN-YunxiNeural)"),
    rate: str = Query("+0%"),
    pitch: str = Query("+0Hz"),
    engine: str = Query("auto", description="auto | kokoro | edge"),
):
    if not text.strip():
        raise HTTPException(400, "empty text")

    # Edge rate/pitch must be SIGNED. A client that sends a bare "0%" / "0Hz"
    # (the Android engine did, until EdgeTtsClient.signedPct) makes edge-tts
    # raise "Invalid pitch '0Hz'" and EVERY such sentence silently landed on the
    # Kokoro fallback. Be tolerant here instead of degrading the tier.
    rate = _signed(rate, "%")
    pitch = _signed(pitch, "Hz")

    speaker = kokoro_voice_name(voice)      # "" = no such local speaker
    edge_voice = voice if voice in EDGE_TO_KOKORO else ""

    mode = (engine or "auto").strip().lower()
    if mode not in ("auto", "kokoro", "edge"):
        raise HTTPException(400, f"unknown engine '{engine}' (auto|kokoro|edge)")
    if mode == "auto":
        # LOCAL FIRST. Edge is used only when an Edge voice name was picked -
        # never as a silent stand-in for a Kokoro voice, which is what made
        # every sentence come from Edge no matter what was selected.
        mode = "edge" if edge_voice else "kokoro"
    if mode == "kokoro" and not speaker:
        raise HTTPException(400, f"no local Kokoro speaker named '{voice}'")
    if mode == "edge" and not edge_voice:
        raise HTTPException(
            400, f"'{voice}' is a local Kokoro voice; pick a zh-CN-* Edge voice or engine=kokoro"
        )

    # The engine is part of the key: the same voice can legitimately be spoken
    # by the local model and by Edge, and those are different audio.
    key = _cache_key(f"{voice}|{mode}", rate, pitch, text)
    cached, media = _cache_get(key)
    if cached:
        _bump("cache_hit")
        return Response(content=cached, media_type=media)

    loop = asyncio.get_running_loop()

    if mode == "kokoro":
        try:
            data = await asyncio.wait_for(
                loop.run_in_executor(
                    _kokoro_pool, kokoro_synthesize, text, voice, _edge_rate_to_pct(rate)
                ),
                timeout=KOKORO_TIMEOUT,
            )
            _cache_put(key, "wav", data)
            _bump("kokoro_ok")
            return Response(content=data, media_type=KOKORO_WAV)
        except Exception as e:
            _bump("kokoro_fail")
            print(f"[tts] kokoro failed for '{voice}' ({type(e).__name__}: {e})", flush=True)
            # Only a mirrored voice has somewhere else to go; anything else is
            # reported instead of silently speaking a different voice.
            if not edge_voice:
                raise HTTPException(500, f"kokoro failed: {e}")
            print(f"[tts] falling back to Edge '{edge_voice}' to keep reading alive",
                  flush=True)
    else:
        # Edge TTS on its own bounded pool (see the pools section at the top).
        try:
            data = await asyncio.wait_for(
                loop.run_in_executor(_edge_pool, edge_synthesize, text, edge_voice, rate, pitch),
                timeout=EDGE_HANDLER_TIMEOUT,
            )
            _cache_put(key, "mp3", data)
            _bump("edge_ok")
            return Response(content=data, media_type=EDGE_MP3)
        except Exception as e:
            _bump("edge_fail")
            print(f"[tts] edge failed ({type(e).__name__}: {e}); falling back to kokoro",
                  flush=True)

    # Last resort: the local model (Edge voice -> its mirrored speaker).
    try:
        data = await asyncio.wait_for(
            loop.run_in_executor(
                _kokoro_pool, kokoro_synthesize, text, edge_voice or voice, _edge_rate_to_pct(rate)
            ),
            timeout=KOKORO_TIMEOUT,
        )
        _cache_put(key, "wav", data)
        _bump("kokoro_ok")
        return Response(content=data, media_type=KOKORO_WAV)
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
        # Warm BOTH checkpoints: v1.0 (the 8 well-trained speakers) is the
        # default target, v1.1-zh serves the 100 extra voices.
        for repo in (KOKORO_REPO_V1, KOKORO_REPO_V11):
            try:
                _get_pipeline(repo)
                print(f"[startup] Kokoro pipeline loaded: {repo}", flush=True)
            except Exception as e:
                print(f"[startup] Kokoro warmup failed for {repo} (retried lazily): {e}",
                      flush=True)

    _threading.Thread(target=_warm, daemon=True).start()
