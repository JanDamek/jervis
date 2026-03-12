"""
Jervis TTS Service — Text-to-Speech using Piper TTS.
Male, deep voice (JARVIS/Iron Man style).
"""
import asyncio
import io
import os
import struct
import tempfile
import time
import wave
from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel

# ── Configuration ────────────────────────────────────────────────────────
TTS_PORT = int(os.getenv("TTS_PORT", "8787"))
TTS_WORKERS = int(os.getenv("TTS_WORKERS", "1"))
TTS_MODEL = os.getenv("TTS_MODEL", "en_US-lessac-medium")  # deep male voice
TTS_DATA_DIR = Path(os.getenv("TTS_DATA_DIR", "/opt/jervis/data/tts"))
TTS_SPEAKER_ID = int(os.getenv("TTS_SPEAKER_ID", "0"))
TTS_SAMPLE_RATE = 22050
TTS_MAX_TEXT_LENGTH = int(os.getenv("TTS_MAX_TEXT_LENGTH", "5000"))

# Global Piper voice reference
_voice = None
_lock = asyncio.Lock()


def _load_voice():
    """Lazy-load Piper voice model."""
    global _voice
    if _voice is not None:
        return _voice

    import piper

    TTS_DATA_DIR.mkdir(parents=True, exist_ok=True)

    # Download model if not present
    model_dir = TTS_DATA_DIR / "models"
    model_dir.mkdir(parents=True, exist_ok=True)

    model_path = model_dir / f"{TTS_MODEL}.onnx"
    config_path = model_dir / f"{TTS_MODEL}.onnx.json"

    if not model_path.exists():
        print(f"[TTS] Downloading model {TTS_MODEL}...")
        from piper.download import ensure_voice_exists, find_voice, get_voices

        data_dirs = [str(model_dir)]
        voices_info = get_voices(model_dir, update_voices=True)
        ensure_voice_exists(TTS_MODEL, data_dirs, model_dir, voices_info)
        model_path, config_path = find_voice(TTS_MODEL, data_dirs)

    print(f"[TTS] Loading voice from {model_path}")
    _voice = piper.PiperVoice.load(str(model_path), config_path=str(config_path))
    print(f"[TTS] Voice loaded: {TTS_MODEL}, sample_rate={_voice.config.sample_rate}")
    return _voice


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup/shutdown lifecycle."""
    print(f"[TTS] Starting TTS service on port {TTS_PORT}")
    yield
    print("[TTS] Shutting down")


app = FastAPI(title="Jervis TTS Service", lifespan=lifespan)


class TtsRequest(BaseModel):
    text: str
    voice: str | None = None  # reserved for future multi-voice
    speed: float = 1.0  # speaking rate multiplier


class TtsHealthResponse(BaseModel):
    status: str = "ok"
    model: str = TTS_MODEL
    model_loaded: bool = False


@app.get("/health")
async def health() -> TtsHealthResponse:
    return TtsHealthResponse(
        model_loaded=_voice is not None,
    )


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

    async with _lock:
        voice = await asyncio.get_event_loop().run_in_executor(None, _load_voice)

    # Synthesize in thread pool (CPU-bound)
    def _synthesize():
        audio_buffer = io.BytesIO()
        with wave.open(audio_buffer, "wb") as wav:
            wav.setframerate(voice.config.sample_rate)
            wav.setsampwidth(2)  # 16-bit
            wav.setnchannels(1)  # mono
            voice.synthesize(
                request.text,
                wav,
                speaker_id=TTS_SPEAKER_ID,
                length_scale=1.0 / max(0.1, request.speed),
            )
        return audio_buffer.getvalue()

    wav_data = await asyncio.get_event_loop().run_in_executor(None, _synthesize)
    elapsed = time.monotonic() - start
    print(f"[TTS] Synthesized {len(request.text)} chars → {len(wav_data)} bytes in {elapsed:.2f}s")

    return Response(
        content=wav_data,
        media_type="audio/wav",
        headers={
            "X-TTS-Duration-Ms": str(int(elapsed * 1000)),
            "X-TTS-Text-Length": str(len(request.text)),
        },
    )


@app.post("/tts/stream")
async def synthesize_stream(request: TtsRequest):
    """Synthesize speech from text, stream WAV audio chunks (for low-latency playback)."""
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Empty text")

    if len(request.text) > TTS_MAX_TEXT_LENGTH:
        raise HTTPException(
            status_code=400,
            detail=f"Text too long: {len(request.text)} chars (max {TTS_MAX_TEXT_LENGTH})",
        )

    async with _lock:
        voice = await asyncio.get_event_loop().run_in_executor(None, _load_voice)

    def _generate_chunks():
        """Generate audio sentence by sentence for low-latency streaming."""
        import re
        sentences = re.split(r'(?<=[.!?])\s+', request.text.strip())

        for sentence in sentences:
            if not sentence.strip():
                continue
            buf = io.BytesIO()
            with wave.open(buf, "wb") as wav:
                wav.setframerate(voice.config.sample_rate)
                wav.setsampwidth(2)
                wav.setnchannels(1)
                voice.synthesize(
                    sentence,
                    wav,
                    speaker_id=TTS_SPEAKER_ID,
                    length_scale=1.0 / max(0.1, request.speed),
                )
            yield buf.getvalue()

    async def _stream():
        loop = asyncio.get_event_loop()
        chunks = await loop.run_in_executor(None, lambda: list(_generate_chunks()))
        for chunk in chunks:
            yield chunk

    return StreamingResponse(
        _stream(),
        media_type="audio/wav",
    )


if __name__ == "__main__":
    uvicorn.run("tts_server:app", host="0.0.0.0", port=TTS_PORT, workers=TTS_WORKERS)
