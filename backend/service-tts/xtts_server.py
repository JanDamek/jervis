"""
Jervis TTS Service — XTTS v2 (Coqui) multilingual voice cloning TTS.

Replaces Piper TTS with much higher quality neural TTS.
Supports Czech + English (and 15 other languages) from a single cloned voice.
Requires GPU (CUDA) for acceptable latency.

API:
  POST /tts         {"text": "...", "speed": 1.0}  → audio/wav (batch)
  POST /tts/stream  {"text": "...", "speed": 1.0}  → SSE with raw PCM int16 chunks (true streaming via inference_stream)
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


XTTS_CHAR_LIMIT = 170  # XTTS v2 limit is 186 for Czech, keep margin


def _split_long_text(text: str, max_len: int = XTTS_CHAR_LIMIT) -> list[str]:
    """Split text into chunks that fit within XTTS character limit.

    First splits on sentence boundaries (.!?), then on clause boundaries (,;:—–)
    if sentences are still too long, finally hard-splits on word boundaries.
    """
    # Step 1: Split into sentences
    raw_sentences = re.split(r'(?<=[.!?])\s+', text.strip())
    chunks = []

    for sentence in raw_sentences:
        sentence = sentence.strip()
        if not sentence:
            continue

        if len(sentence) <= max_len:
            chunks.append(sentence)
            continue

        # Step 2: Split long sentence on clause boundaries
        clauses = re.split(r'(?<=[,;:—–])\s+', sentence)
        current = ""
        for clause in clauses:
            if current and len(current) + 1 + len(clause) > max_len:
                chunks.append(current.strip())
                current = clause
            elif not current:
                current = clause
            else:
                current += " " + clause

        if current.strip():
            # Step 3: If still too long, hard-split on word boundaries
            if len(current) > max_len:
                words = current.split()
                buf = ""
                for word in words:
                    if buf and len(buf) + 1 + len(word) > max_len:
                        chunks.append(buf.strip())
                        buf = word
                    elif not buf:
                        buf = word
                    else:
                        buf += " " + word
                if buf.strip():
                    chunks.append(buf.strip())
            else:
                chunks.append(current.strip())

    return chunks if chunks else [text]


def _synthesize_chunk(text: str, speed: float, language: str) -> bytes:
    """Synthesize a single text chunk (must be within XTTS char limit) to WAV bytes."""
    # Strip trailing punctuation — XTTS reads "." as "teška" in Czech
    text = text.rstrip(".!?…,;:\"'""„‟»«")
    if not text:
        return b""

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


def _synthesize_text(text: str, speed: float = 1.0, language: str = "") -> bytes:
    """Synthesize text to WAV bytes, auto-splitting if exceeds XTTS char limit."""
    if not language:
        language = _detect_language(text)

    # Short text — single chunk
    if len(text) <= XTTS_CHAR_LIMIT:
        return _synthesize_chunk(text, speed, language)

    # Long text — split and concatenate raw PCM, wrap in single WAV
    chunks = _split_long_text(text)
    all_pcm = []
    sample_rate = _tts.synthesizer.output_sample_rate

    for chunk in chunks:
        chunk = chunk.strip()
        if not chunk:
            continue
        wav_bytes = _synthesize_chunk(chunk, speed, language)
        if not wav_bytes:
            continue
        # Extract raw PCM from WAV bytes
        with wave.open(io.BytesIO(wav_bytes), "rb") as wf:
            sample_rate = wf.getframerate()
            all_pcm.append(wf.readframes(wf.getnframes()))

    if not all_pcm:
        return b""

    # Combine all PCM and wrap in single WAV
    combined_pcm = b"".join(all_pcm)
    audio_buffer = io.BytesIO()
    with wave.open(audio_buffer, "wb") as wav:
        wav.setframerate(sample_rate)
        wav.setsampwidth(2)
        wav.setnchannels(1)
        wav.writeframes(combined_pcm)

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


def _stream_inference(text: str, speed: float, language: str, chunk_queue: queue.Queue):
    """True streaming TTS using inference_stream() — yields PCM chunks as they're generated.

    Uses XTTS inference_stream() which internally handles crossfading between chunks.
    Each chunk is raw int16 PCM bytes (no WAV header) for gapless playback via SourceDataLine.
    Text is split into sentences first (XTTS char limit), then each sentence is streamed.
    """
    try:
        if not language:
            language = _detect_language(text)

        model = _tts.synthesizer.tts_model
        sample_rate = _tts.synthesizer.output_sample_rate

        # Send sample rate as first event so client can open audio line
        chunk_queue.put(("header", {"sample_rate": sample_rate}))

        # Split text into sentence-level chunks (respecting XTTS char limit)
        text_chunks = _split_long_text(text)
        chunk_idx = 0

        for text_chunk in text_chunks:
            text_chunk = text_chunk.strip().rstrip(".!?…,;:\"'""„‟»«")
            if not text_chunk:
                continue

            try:
                if _gpt_cond_latent is not None and _speaker_embedding is not None:
                    # Use inference_stream with pre-computed speaker embedding
                    stream_gen = model.inference_stream(
                        text=text_chunk,
                        language=language,
                        gpt_cond_latent=_gpt_cond_latent,
                        speaker_embedding=_speaker_embedding,
                        speed=speed,
                        stream_chunk_size=30,  # tokens per chunk — larger = better quality, ~2s first chunk on P40
                    )

                    for audio_chunk in stream_gen:
                        if isinstance(audio_chunk, torch.Tensor):
                            audio_chunk = audio_chunk.cpu().numpy()
                        audio_chunk = audio_chunk.squeeze()
                        pcm_int16 = np.clip(audio_chunk * 32767, -32768, 32767).astype(np.int16)
                        chunk_queue.put(("pcm", pcm_int16.tobytes()))
                        chunk_idx += 1
                else:
                    # No speaker embedding — fall back to batch synthesis
                    wav_data = _synthesize_chunk(text_chunk, speed, language)
                    if wav_data:
                        # Extract raw PCM from WAV
                        with wave.open(io.BytesIO(wav_data), "rb") as wf:
                            chunk_queue.put(("pcm", wf.readframes(wf.getnframes())))
                        chunk_idx += 1

            except Exception as e:
                print(f"[TTS] stream chunk error on '{text_chunk[:40]}': {e}", flush=True)

        chunk_queue.put(("done", chunk_idx))
    except Exception as e:
        print(f"[TTS] _stream_inference error: {e}", flush=True)
        import traceback
        traceback.print_exc()
        chunk_queue.put(("error", str(e)))


@app.post("/tts/stream")
async def synthesize_stream(request: TtsRequest):
    """True streaming TTS via SSE using inference_stream().

    Protocol:
      event: tts_header  data: {"sample_rate":24000}     — open audio line
      event: tts_pcm     data: {"data":"<base64 int16>"}  — raw PCM chunk, write to audio line
      event: done        data: {"chunks":N}               — all chunks sent
      event: error       data: {"text":"..."}             — error
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

    # Run true streaming inference in background thread
    thread = threading.Thread(
        target=_stream_inference,
        args=(request.text, request.speed, request.language, chunk_q),
        daemon=True,
    )
    thread.start()

    async def _sse_stream():
        chunk_idx = 0
        loop = asyncio.get_running_loop()
        first_chunk_time = None

        while True:
            try:
                msg_type, data = await loop.run_in_executor(None, chunk_q.get, True, 60.0)
            except Exception:
                yield f"event: error\ndata: {{\"text\":\"Timeout waiting for audio chunk\"}}\n\n"
                break

            if msg_type == "header":
                yield f"event: tts_header\ndata: {json.dumps(data)}\n\n"

            elif msg_type == "pcm":
                if first_chunk_time is None:
                    first_chunk_time = time.monotonic() - start
                audio_b64 = base64.b64encode(data).decode("ascii")
                yield f"event: tts_pcm\ndata: {{\"data\":\"{audio_b64}\"}}\n\n"
                chunk_idx += 1

            elif msg_type == "done":
                elapsed = time.monotonic() - start
                lang = request.language or _detect_language(request.text)
                fc = f"{first_chunk_time:.2f}" if first_chunk_time else "N/A"
                print(f"[TTS] Streamed {chunk_idx} PCM chunks for {len(request.text)} chars in {elapsed:.2f}s "
                      f"(first_chunk={fc}s, lang={lang})", flush=True)
                yield f"event: done\ndata: {{\"chunks\":{chunk_idx}}}\n\n"
                break

            elif msg_type == "error":
                yield f"event: error\ndata: {{\"text\":\"{data}\"}}\n\n"
                break

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
