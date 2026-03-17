"""
Jervis TTS Service — XTTS v2 (Coqui) multilingual voice cloning TTS.

Replaces Piper TTS with much higher quality neural TTS.
Supports Czech + English (and 15 other languages) from a single cloned voice.
Requires GPU (CUDA) for acceptable latency.

API:
  POST /tts         {"text": "...", "speed": 1.0}  → audio/wav (batch)
  POST /tts/stream  {"text": "...", "speed": 1.0}  → SSE with base64 WAV chunks (~0.5s first chunk)
"""
import asyncio
import base64
import io
import json
import os
import queue
import re
import threading
import time
import wave
from contextlib import asynccontextmanager
from pathlib import Path

import numpy as np
import torch
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel

# ── Configuration ────────────────────────────────────────────────────────
TTS_PORT = int(os.getenv("TTS_PORT", "8787"))
TTS_WORKERS = int(os.getenv("TTS_WORKERS", "1"))
TTS_DATA_DIR = Path(os.getenv("TTS_DATA_DIR", "/opt/jervis/data/tts"))
TTS_SPEAKER_WAV = os.getenv("TTS_SPEAKER_WAV", "")  # path to reference voice WAV
TTS_LANGUAGE = os.getenv("TTS_LANGUAGE", "cs")  # default language
TTS_MAX_TEXT_LENGTH = int(os.getenv("TTS_MAX_TEXT_LENGTH", "10000"))
TTS_DEVICE = os.getenv("TTS_DEVICE", "cuda")  # cuda or cpu

# XTTS v2 model name (from coqui-tts)
XTTS_MODEL = "tts_models/multilingual/multi-dataset/xtts_v2"

# Global references
_tts = None
_lock = asyncio.Lock()
_speaker_wav = None
_gpt_cond_latent = None
_speaker_embedding = None


def _find_speaker_wav() -> str:
    """Find speaker reference WAV file."""
    # Explicit env var
    if TTS_SPEAKER_WAV and Path(TTS_SPEAKER_WAV).exists():
        return TTS_SPEAKER_WAV

    # Look in data dir
    speaker_dir = TTS_DATA_DIR / "speakers"
    speaker_dir.mkdir(parents=True, exist_ok=True)

    # Try common names
    for name in ["speaker.wav", "default.wav", "reference.wav", "voice.wav"]:
        path = speaker_dir / name
        if path.exists():
            return str(path)

    # Any WAV file in speakers dir
    wavs = list(speaker_dir.glob("*.wav"))
    if wavs:
        return str(wavs[0])

    return ""


def _load_tts():
    """Lazy-load XTTS v2 model and speaker embeddings."""
    global _tts, _speaker_wav, _gpt_cond_latent, _speaker_embedding
    if _tts is not None:
        return _tts

    from TTS.api import TTS as CoquiTTS

    print(f"[TTS] Loading XTTS v2 model on {TTS_DEVICE}...")
    start = time.monotonic()

    _tts = CoquiTTS(XTTS_MODEL).to(TTS_DEVICE)

    elapsed = time.monotonic() - start
    print(f"[TTS] XTTS v2 loaded in {elapsed:.1f}s on {TTS_DEVICE}")

    # Pre-compute speaker conditioning for faster inference
    _speaker_wav = _find_speaker_wav()
    if _speaker_wav:
        print(f"[TTS] Using speaker reference: {_speaker_wav}")
        _precompute_speaker_embedding()
    else:
        print("[TTS] WARNING: No speaker reference WAV found!")
        print(f"[TTS] Place a WAV file in {TTS_DATA_DIR}/speakers/speaker.wav")
        print("[TTS] Using default XTTS voice (no cloning)")

    return _tts


def _precompute_speaker_embedding():
    """Pre-compute speaker conditioning latents for faster synthesis."""
    global _gpt_cond_latent, _speaker_embedding
    if not _speaker_wav or not _tts:
        return

    print(f"[TTS] Computing speaker embedding from {_speaker_wav}...")
    start = time.monotonic()

    # Access the underlying synthesizer for conditioning
    model = _tts.synthesizer.tts_model
    _gpt_cond_latent, _speaker_embedding = model.get_conditioning_latents(
        audio_path=[_speaker_wav]
    )

    elapsed = time.monotonic() - start
    print(f"[TTS] Speaker embedding computed in {elapsed:.1f}s")


def _detect_language(text: str) -> str:
    """Simple language detection: if mostly ASCII → English, otherwise Czech."""
    ascii_chars = sum(1 for c in text if ord(c) < 128)
    ratio = ascii_chars / max(len(text), 1)
    # If >90% ASCII and contains common English patterns, assume English
    if ratio > 0.90 and re.search(r'\b(the|is|are|was|were|have|has|this|that|with|for|and|but|not)\b', text.lower()):
        return "en"
    return TTS_LANGUAGE


def _synthesize_text(text: str, speed: float = 1.0, language: str = "") -> bytes:
    """Synthesize text to WAV bytes using XTTS v2."""
    if not language:
        language = _detect_language(text)

    # Strip trailing punctuation — XTTS reads "." as "teška" in Czech
    text = text.rstrip(".!?…,;:\"'""„‟»«")

    model = _tts.synthesizer.tts_model

    if _gpt_cond_latent is not None and _speaker_embedding is not None:
        # Use pre-computed speaker embedding (faster)
        result = model.inference(
            text=text,
            language=language,
            gpt_cond_latent=_gpt_cond_latent,
            speaker_embedding=_speaker_embedding,
            speed=speed,
        )
        wav_array = result["wav"]
    elif _speaker_wav:
        # Compute on-the-fly
        wav_list = _tts.tts(
            text=text,
            speaker_wav=_speaker_wav,
            language=language,
            speed=speed,
        )
        wav_array = np.array(wav_list, dtype=np.float32)
    else:
        # No speaker reference — use default
        wav_list = _tts.tts(
            text=text,
            language=language,
            speed=speed,
        )
        wav_array = np.array(wav_list, dtype=np.float32)

    # Convert float32 [-1, 1] to int16 WAV
    if isinstance(wav_array, torch.Tensor):
        wav_array = wav_array.cpu().numpy()

    wav_int16 = np.clip(wav_array * 32767, -32768, 32767).astype(np.int16)

    # Get sample rate from model config
    sample_rate = _tts.synthesizer.output_sample_rate

    # Wrap in WAV
    audio_buffer = io.BytesIO()
    with wave.open(audio_buffer, "wb") as wav:
        wav.setframerate(sample_rate)
        wav.setsampwidth(2)  # 16-bit
        wav.setnchannels(1)  # mono
        wav.writeframes(wav_int16.tobytes())

    return audio_buffer.getvalue()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup/shutdown lifecycle — pre-load model for instant first request."""
    print(f"[TTS] Starting XTTS v2 service on port {TTS_PORT}")
    # Pre-load model + speaker embedding at startup (not lazy on first request)
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, _load_tts)
    print("[TTS] Model ready, accepting requests")
    yield
    print("[TTS] Shutting down")


app = FastAPI(title="Jervis TTS Service (XTTS v2)", lifespan=lifespan)


class TtsRequest(BaseModel):
    text: str
    voice: str | None = None  # reserved for future multi-voice
    speed: float = 1.0  # speaking rate multiplier
    language: str = ""  # auto-detect if empty


class TtsHealthResponse(BaseModel):
    status: str = "ok"
    model: str = "xtts_v2"
    model_loaded: bool = False
    speaker_loaded: bool = False
    device: str = TTS_DEVICE


@app.get("/health")
async def health() -> TtsHealthResponse:
    return TtsHealthResponse(
        model_loaded=_tts is not None,
        speaker_loaded=_gpt_cond_latent is not None,
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
        await asyncio.get_running_loop().run_in_executor(None, _load_tts)

    wav_data = await asyncio.get_running_loop().run_in_executor(
        None, _synthesize_text, request.text, request.speed, request.language
    )
    elapsed = time.monotonic() - start
    print(f"[TTS] Synthesized {len(request.text)} chars → {len(wav_data)} bytes in {elapsed:.2f}s (lang={_detect_language(request.text)})")

    return Response(
        content=wav_data,
        media_type="audio/wav",
        headers={
            "X-TTS-Duration-Ms": str(int(elapsed * 1000)),
            "X-TTS-Text-Length": str(len(request.text)),
        },
    )


def _wav_chunk_to_bytes(wav_chunk, sample_rate: int) -> bytes:
    """Convert a float32 tensor/numpy chunk to WAV bytes."""
    if isinstance(wav_chunk, torch.Tensor):
        wav_chunk = wav_chunk.cpu().numpy()
    wav_chunk = wav_chunk.squeeze()
    wav_int16 = np.clip(wav_chunk * 32767, -32768, 32767).astype(np.int16)
    audio_buffer = io.BytesIO()
    with wave.open(audio_buffer, "wb") as wf:
        wf.setframerate(sample_rate)
        wf.setsampwidth(2)
        wf.setnchannels(1)
        wf.writeframes(wav_int16.tobytes())
    return audio_buffer.getvalue()


def _stream_sentences(text: str, speed: float, language: str, chunk_queue: queue.Queue):
    """Synthesize text sentence-by-sentence using batch inference, put complete WAVs into queue.

    Uses model.inference() (not inference_stream) for clean, high-quality audio per sentence.
    Each sentence is sent as soon as it's ready — true streaming at sentence level.
    """
    try:
        if not language:
            language = _detect_language(text)

        # Split text into sentences
        sentences = re.split(r'(?<=[.!?])\s+', text.strip())
        sent_idx = 0

        for sentence in sentences:
            sentence = sentence.strip()
            if not sentence:
                continue

            try:
                wav_data = _synthesize_text(sentence, speed, language)
                chunk_queue.put(("chunk", wav_data))
                sent_idx += 1
            except Exception as e:
                print(f"[TTS] sentence synthesis error on '{sentence[:40]}': {e}", flush=True)

        chunk_queue.put(("done", None))
    except Exception as e:
        print(f"[TTS] _stream_sentences error: {e}", flush=True)
        chunk_queue.put(("error", str(e)))


@app.post("/tts/stream")
async def synthesize_stream(request: TtsRequest):
    """Synthesize speech, stream WAV chunks via SSE using inference_stream().

    Each SSE event contains a base64-encoded WAV chunk (~0.5s of audio).
    First chunk arrives in ~0.5s instead of waiting for full sentence.
    """
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Empty text")

    if len(request.text) > TTS_MAX_TEXT_LENGTH:
        raise HTTPException(
            status_code=400,
            detail=f"Text too long: {len(request.text)} chars (max {TTS_MAX_TEXT_LENGTH})",
        )

    async with _lock:
        await asyncio.get_running_loop().run_in_executor(None, _load_tts)

    start = time.monotonic()
    chunk_q: queue.Queue = queue.Queue()

    # Run sentence-by-sentence synthesis in background thread
    thread = threading.Thread(
        target=_stream_sentences,
        args=(request.text, request.speed, request.language, chunk_q),
        daemon=True,
    )
    thread.start()

    async def _sse_stream():
        chunk_idx = 0
        loop = asyncio.get_running_loop()
        first_chunk_time = None

        while True:
            # Non-blocking poll from queue
            try:
                msg_type, data = await loop.run_in_executor(None, chunk_q.get, True, 30.0)
            except Exception:
                yield f"event: error\ndata: {{\"text\":\"Timeout waiting for audio chunk\"}}\n\n"
                break

            if msg_type == "done":
                elapsed = time.monotonic() - start
                lang = request.language or _detect_language(request.text)
                print(f"[TTS] Streamed {chunk_idx} chunks for {len(request.text)} chars in {elapsed:.2f}s "
                      f"(first_chunk={first_chunk_time:.2f}s, lang={lang})", flush=True)
                yield f"event: done\ndata: {{\"chunks\":{chunk_idx}}}\n\n"
                break
            elif msg_type == "error":
                yield f"event: error\ndata: {{\"text\":\"{data}\"}}\n\n"
                break
            elif msg_type == "chunk":
                if first_chunk_time is None:
                    first_chunk_time = time.monotonic() - start
                audio_b64 = base64.b64encode(data).decode("ascii")
                yield f"event: tts_audio\ndata: {{\"data\":\"{audio_b64}\",\"chunk\":{chunk_idx}}}\n\n"
                chunk_idx += 1

    return StreamingResponse(
        _sse_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-store",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )


@app.post("/set_speaker")
async def set_speaker(wav_path: str):
    """Hot-swap speaker reference voice."""
    global _speaker_wav
    if not Path(wav_path).exists():
        raise HTTPException(status_code=404, detail=f"Speaker WAV not found: {wav_path}")

    _speaker_wav = wav_path
    await asyncio.get_running_loop().run_in_executor(None, _precompute_speaker_embedding)
    return {"status": "ok", "speaker": wav_path}


@app.get("/speakers")
async def list_speakers():
    """List available speaker WAV files."""
    speaker_dir = TTS_DATA_DIR / "speakers"
    if not speaker_dir.exists():
        return {"speakers": [], "active": _speaker_wav}

    wavs = [f.name for f in speaker_dir.glob("*.wav")]
    return {"speakers": wavs, "active": Path(_speaker_wav).name if _speaker_wav else None}


if __name__ == "__main__":
    uvicorn.run("xtts_server:app", host="0.0.0.0", port=TTS_PORT, workers=TTS_WORKERS)
