"""
Jervis TTS Service — Text-to-Speech using Piper TTS.
Male, deep voice (JARVIS/Iron Man style).

Supports piper-tts 1.2.x (old API) and 1.4.x (new SynthesisConfig API).
"""
import asyncio
import io
import os
import struct
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
TTS_MODEL = os.getenv("TTS_MODEL", "cs_CZ-jirka-medium")  # Czech male voice (primary)
TTS_DATA_DIR = Path(os.getenv("TTS_DATA_DIR", "/opt/jervis/data/tts"))
TTS_SPEAKER_ID = int(os.getenv("TTS_SPEAKER_ID", "0"))
TTS_SAMPLE_RATE = 22050
TTS_MAX_TEXT_LENGTH = int(os.getenv("TTS_MAX_TEXT_LENGTH", "5000"))

# Global Piper voice reference
_voice = None
_lock = asyncio.Lock()
_piper_new_api = False  # True for piper-tts >= 1.4 (SynthesisConfig + AudioChunk)


def _load_voice():
    """Lazy-load Piper voice model."""
    global _voice, _piper_new_api
    if _voice is not None:
        return _voice

    import piper

    # Detect API version
    _piper_new_api = hasattr(piper, 'SynthesisConfig')
    print(f"[TTS] Piper API: {'new (1.4+)' if _piper_new_api else 'legacy (1.2)'}")

    TTS_DATA_DIR.mkdir(parents=True, exist_ok=True)

    model_dir = TTS_DATA_DIR / "models"
    model_dir.mkdir(parents=True, exist_ok=True)

    model_path = model_dir / f"{TTS_MODEL}.onnx"
    config_path = model_dir / f"{TTS_MODEL}.onnx.json"

    if not model_path.exists():
        # Try legacy download API (piper-tts 1.2.x)
        try:
            from piper.download import ensure_voice_exists, get_voices
            print(f"[TTS] Downloading model {TTS_MODEL}...")
            voices_info = get_voices(model_dir, update_voices=True)
            ensure_voice_exists(TTS_MODEL, [str(model_dir)], model_dir, voices_info)
        except ImportError:
            raise RuntimeError(
                f"TTS model not found: {model_path}. "
                f"Download manually from https://huggingface.co/rhasspy/piper-voices"
            )

    if not model_path.exists():
        raise RuntimeError(f"TTS model not found after download: {model_path}")

    print(f"[TTS] Loading voice from {model_path}")
    _voice = piper.PiperVoice.load(str(model_path), config_path=str(config_path))
    print(f"[TTS] Voice loaded: {TTS_MODEL}, sample_rate={_voice.config.sample_rate}")
    return _voice


def _synthesize_text(voice, text: str, speed: float = 1.0) -> bytes:
    """Synthesize text to WAV bytes. Works with both old and new piper API."""
    import piper

    if _piper_new_api:
        # piper-tts >= 1.4: synthesize() returns Iterable[AudioChunk]
        syn_config = piper.SynthesisConfig(
            length_scale=1.0 / max(0.1, speed),
            speaker_id=TTS_SPEAKER_ID if getattr(voice.config, 'num_speakers', 1) > 1 else None,
        )
        chunks = voice.synthesize(text, syn_config=syn_config)
        # Collect all audio samples
        all_samples = b""
        sample_rate = voice.config.sample_rate
        for chunk in chunks:
            all_samples += chunk.audio_int16_bytes
        # Wrap in WAV
        audio_buffer = io.BytesIO()
        with wave.open(audio_buffer, "wb") as wav:
            wav.setframerate(sample_rate)
            wav.setsampwidth(2)  # 16-bit
            wav.setnchannels(1)  # mono
            wav.writeframes(all_samples)
        return audio_buffer.getvalue()
    else:
        # piper-tts 1.2.x: synthesize() writes to wave file directly
        audio_buffer = io.BytesIO()
        with wave.open(audio_buffer, "wb") as wav:
            wav.setframerate(voice.config.sample_rate)
            wav.setsampwidth(2)
            wav.setnchannels(1)
            synth_kwargs = dict(length_scale=1.0 / max(0.1, speed))
            if hasattr(voice.config, 'num_speakers') and voice.config.num_speakers > 1:
                synth_kwargs['speaker_id'] = TTS_SPEAKER_ID
            voice.synthesize(text, wav, **synth_kwargs)
        return audio_buffer.getvalue()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup/shutdown lifecycle."""
    print(f"[TTS] Starting TTS service — FastAPI :{TTS_PORT} + gRPC :5501")
    # Start the pod-to-pod gRPC server alongside FastAPI.
    from app.grpc_server import start_grpc_server

    grpc_port = int(os.getenv("TTS_GRPC_PORT", "5501"))
    grpc_server = await start_grpc_server(port=grpc_port)
    app.state.grpc_server = grpc_server
    try:
        yield
    finally:
        await grpc_server.stop(grace=5.0)
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


# /tts + /tts/stream migrated to gRPC (TtsService.{Speak,SpeakStream} on :5501).


if __name__ == "__main__":
    uvicorn.run("tts_server:app", host="0.0.0.0", port=TTS_PORT, workers=TTS_WORKERS)
