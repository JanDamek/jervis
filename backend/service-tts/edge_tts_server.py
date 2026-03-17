"""
Jervis TTS Service — Edge TTS (Microsoft Neural voices).

High-quality Czech + English TTS with near-instant response (<500ms).
Uses Microsoft Edge's free neural TTS endpoint (no API key needed).
Voice: cs-CZ-AntoninNeural (Czech male, deep, natural).

Returns WAV audio (converted from MP3 via ffmpeg) for JVM AudioPlayer compatibility.

API is backward-compatible with previous TTS servers:
  POST /tts  {"text": "...", "speed": 1.5}  → audio/wav
"""
import asyncio
import io
import os
import re
import subprocess
import tempfile
import time
from contextlib import asynccontextmanager

import edge_tts
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel

# ── Configuration ────────────────────────────────────────────────────────
TTS_PORT = int(os.getenv("TTS_PORT", "8787"))
TTS_WORKERS = int(os.getenv("TTS_WORKERS", "1"))
TTS_VOICE = os.getenv("TTS_VOICE", "cs-CZ-AntoninNeural")  # Czech male
TTS_VOICE_EN = os.getenv("TTS_VOICE_EN", "en-GB-RyanNeural")  # English male (deep)
TTS_MAX_TEXT_LENGTH = int(os.getenv("TTS_MAX_TEXT_LENGTH", "10000"))
TTS_DEFAULT_RATE = os.getenv("TTS_DEFAULT_RATE", "+50%")  # speaking rate boost


def _detect_language(text: str) -> str:
    """Simple language detection: if mostly ASCII + English words → English."""
    ascii_chars = sum(1 for c in text if ord(c) < 128)
    ratio = ascii_chars / max(len(text), 1)
    if ratio > 0.90 and re.search(
        r'\b(the|is|are|was|were|have|has|this|that|with|for|and|but|not|you|your|can|will|would)\b',
        text.lower()
    ):
        return "en"
    return "cs"


def _speed_to_rate(speed: float) -> str:
    """Convert numeric speed multiplier to edge-tts rate string."""
    if speed <= 0.5:
        return "-50%"
    elif speed <= 0.8:
        return "-20%"
    elif speed <= 1.0:
        return TTS_DEFAULT_RATE
    elif speed <= 1.3:
        return "+50%"
    elif speed <= 1.5:
        return "+70%"
    elif speed <= 2.0:
        return "+90%"
    else:
        return "+100%"


def _mp3_to_wav(mp3_data: bytes) -> bytes:
    """Convert MP3 bytes to WAV bytes using ffmpeg."""
    result = subprocess.run(
        ["ffmpeg", "-i", "pipe:0", "-f", "wav", "-acodec", "pcm_s16le",
         "-ar", "24000", "-ac", "1", "pipe:1"],
        input=mp3_data,
        capture_output=True,
        timeout=10,
    )
    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg failed: {result.stderr.decode()[:200]}")
    return result.stdout


async def _synthesize_text(text: str, speed: float = 1.0, language: str = "") -> bytes:
    """Synthesize text to WAV bytes using edge-tts + ffmpeg."""
    if not language:
        language = _detect_language(text)

    voice = TTS_VOICE if language == "cs" else TTS_VOICE_EN
    rate = _speed_to_rate(speed)

    communicate = edge_tts.Communicate(text, voice, rate=rate)

    # Collect MP3 chunks
    mp3_data = b""
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            mp3_data += chunk["data"]

    if not mp3_data:
        raise RuntimeError("No audio data received from edge-tts")

    # Convert MP3 → WAV in a thread to not block the event loop
    loop = asyncio.get_running_loop()
    wav_data = await loop.run_in_executor(None, _mp3_to_wav, mp3_data)
    return wav_data


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup/shutdown lifecycle."""
    print(f"[TTS] Starting Edge TTS service on port {TTS_PORT}")
    print(f"[TTS] Voice CS: {TTS_VOICE}, EN: {TTS_VOICE_EN}, rate: {TTS_DEFAULT_RATE}")

    # Verify ffmpeg is available
    try:
        subprocess.run(["ffmpeg", "-version"], capture_output=True, timeout=5)
        print("[TTS] ffmpeg available")
    except FileNotFoundError:
        print("[TTS] WARNING: ffmpeg not found! Install with: apt install ffmpeg")

    # Warm up with a short synthesis
    try:
        await _synthesize_text("Test.", 1.0, "cs")
        print("[TTS] Warmup complete, ready")
    except Exception as e:
        print(f"[TTS] Warmup failed: {e}")
    yield
    print("[TTS] Shutting down")


app = FastAPI(title="Jervis TTS Service (Edge TTS)", lifespan=lifespan)


class TtsRequest(BaseModel):
    text: str
    voice: str | None = None
    speed: float = 1.0
    language: str = ""


class TtsHealthResponse(BaseModel):
    status: str = "ok"
    model: str = "edge-tts"
    model_loaded: bool = True
    voice_cs: str = TTS_VOICE
    voice_en: str = TTS_VOICE_EN


@app.get("/health")
async def health() -> TtsHealthResponse:
    return TtsHealthResponse()


@app.post("/tts")
async def synthesize(request: TtsRequest) -> Response:
    """Synthesize speech from text, return WAV audio."""
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Empty text")

    if len(request.text) > TTS_MAX_TEXT_LENGTH:
        raise HTTPException(
            status_code=400,
            detail=f"Text too long: {len(request.text)} chars (max {TTS_MAX_TEXT_LENGTH})",
        )

    start = time.monotonic()

    wav_data = await _synthesize_text(request.text, request.speed, request.language)

    elapsed = time.monotonic() - start
    lang = request.language or _detect_language(request.text)
    print(f"[TTS] Synthesized {len(request.text)} chars → {len(wav_data)} bytes in {elapsed:.2f}s (lang={lang})")

    return Response(
        content=wav_data,
        media_type="audio/wav",
        headers={
            "X-TTS-Duration-Ms": str(int(elapsed * 1000)),
            "X-TTS-Text-Length": str(len(request.text)),
        },
    )


if __name__ == "__main__":
    uvicorn.run("edge_tts_server:app", host="0.0.0.0", port=TTS_PORT, workers=TTS_WORKERS)
