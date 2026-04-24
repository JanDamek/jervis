"""
Jervis TTS Service — XTTS v2 (Coqui) multilingual voice cloning TTS.

gRPC-only: consumers dial `jervis.tts.TtsService` on :5501. Runs on the VD
GPU VM as a systemd unit (see k8s/deploy_xtts_gpu.sh). No HTTP surface.
Speaker WAVs live under $TTS_DATA_DIR/speakers/ and are picked up at boot
(env override via TTS_SPEAKER_WAV).
"""
import asyncio
import io
import logging
import os
import queue
import re
import signal
import threading
import time
import wave
from pathlib import Path

import numpy as np
import torch

# ── Configuration ────────────────────────────────────────────────────────
TTS_DATA_DIR = Path(os.getenv("TTS_DATA_DIR", "/opt/jervis/data/tts"))
TTS_SPEAKER_WAV = os.getenv("TTS_SPEAKER_WAV", "")  # path to reference voice WAV
TTS_LANGUAGE = os.getenv("TTS_LANGUAGE", "cs")  # default language
TTS_MAX_TEXT_LENGTH = int(os.getenv("TTS_MAX_TEXT_LENGTH", "10000"))
TTS_DEVICE = os.getenv("TTS_DEVICE", "cuda")  # cuda or cpu
TTS_GRPC_PORT = int(os.getenv("TTS_GRPC_PORT", "5501"))

logger = logging.getLogger("tts.xtts")

# XTTS v2 model name (from coqui-tts)
XTTS_MODEL = "tts_models/multilingual/multi-dataset/xtts_v2"

# Global references
_tts = None
_lock = asyncio.Lock()
_speaker_wav = None
_gpt_cond_latent = None
_speaker_embedding = None

# Multi-speaker cache: speaker_name → (gpt_cond_latent, speaker_embedding)
_speaker_cache: dict[str, tuple] = {}


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

    # Cache as "default" speaker
    _speaker_cache["default"] = (_gpt_cond_latent, _speaker_embedding)

    elapsed = time.monotonic() - start
    print(f"[TTS] Speaker embedding computed in {elapsed:.1f}s")

    # Pre-load any saved speaker embeddings from speakers directory
    _load_saved_speakers()


def _load_saved_speakers():
    """Load pre-computed speaker embeddings from .pt files in speakers directory."""
    speaker_dir = TTS_DATA_DIR / "speakers"
    if not speaker_dir.exists():
        return

    for pt_file in speaker_dir.glob("*.pt"):
        speaker_name = pt_file.stem.replace("_embedding", "").replace("speaker_", "")
        if speaker_name in _speaker_cache:
            continue
        try:
            data = torch.load(pt_file, map_location=TTS_DEVICE, weights_only=True)
            if "gpt_cond_latent" in data and "speaker_embedding" in data:
                _speaker_cache[speaker_name] = (data["gpt_cond_latent"], data["speaker_embedding"])
                print(f"[TTS] Loaded speaker '{speaker_name}' from {pt_file.name}")
        except Exception as e:
            print(f"[TTS] Failed to load speaker from {pt_file}: {e}")

    # Also compute embeddings from WAV files for speakers without .pt
    for wav_file in speaker_dir.glob("*.wav"):
        speaker_name = wav_file.stem
        if speaker_name in _speaker_cache or speaker_name in ("speaker", "default", "reference", "voice"):
            continue
        try:
            model = _tts.synthesizer.tts_model
            latent, emb = model.get_conditioning_latents(audio_path=[str(wav_file)])
            _speaker_cache[speaker_name] = (latent, emb)
            print(f"[TTS] Computed speaker '{speaker_name}' from {wav_file.name}")
        except Exception as e:
            print(f"[TTS] Failed to compute speaker from {wav_file}: {e}")


def _get_speaker(speaker_name: str | None) -> tuple:
    """Get speaker conditioning latents by name.

    Returns (gpt_cond_latent, speaker_embedding) or (None, None) for default.
    """
    if not speaker_name or speaker_name == "default":
        return _gpt_cond_latent, _speaker_embedding

    if speaker_name in _speaker_cache:
        return _speaker_cache[speaker_name]

    # Try loading on-demand from speakers directory
    speaker_dir = TTS_DATA_DIR / "speakers"

    # Check for .pt file first
    pt_path = speaker_dir / f"{speaker_name}.pt"
    if pt_path.exists():
        try:
            data = torch.load(pt_path, map_location=TTS_DEVICE, weights_only=True)
            latent, emb = data["gpt_cond_latent"], data["speaker_embedding"]
            _speaker_cache[speaker_name] = (latent, emb)
            return latent, emb
        except Exception as e:
            print(f"[TTS] Failed to load speaker '{speaker_name}': {e}")

    # Check for WAV file
    wav_path = speaker_dir / f"{speaker_name}.wav"
    if wav_path.exists() and _tts:
        try:
            model = _tts.synthesizer.tts_model
            latent, emb = model.get_conditioning_latents(audio_path=[str(wav_path)])
            _speaker_cache[speaker_name] = (latent, emb)
            return latent, emb
        except Exception as e:
            print(f"[TTS] Failed to compute speaker '{speaker_name}': {e}")

    # Fallback to default
    print(f"[TTS] Speaker '{speaker_name}' not found, using default")
    return _gpt_cond_latent, _speaker_embedding


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


def _synthesize_chunk(text: str, speed: float, language: str, speaker: str | None = None) -> bytes:
    """Synthesize a single text chunk (must be within XTTS char limit) to WAV bytes."""
    # Strip trailing punctuation — XTTS reads "." as "teška" in Czech
    text = text.rstrip(".!?…,;:\"'""„‟»«")
    if not text:
        return b""

    model = _tts.synthesizer.tts_model
    spk_latent, spk_embedding = _get_speaker(speaker)

    if spk_latent is not None and spk_embedding is not None:
        # Use pre-computed speaker embedding (faster)
        result = model.inference(
            text=text,
            language=language,
            gpt_cond_latent=spk_latent,
            speaker_embedding=spk_embedding,
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


def _synthesize_text(text: str, speed: float = 1.0, language: str = "", speaker: str | None = None) -> bytes:
    """Synthesize text to WAV bytes, auto-splitting if exceeds XTTS char limit."""
    if not language:
        language = _detect_language(text)

    # Short text — single chunk
    if len(text) <= XTTS_CHAR_LIMIT:
        return _synthesize_chunk(text, speed, language, speaker)

    # Long text — split and concatenate raw PCM, wrap in single WAV
    chunks = _split_long_text(text)
    all_pcm = []
    sample_rate = _tts.synthesizer.output_sample_rate

    for chunk in chunks:
        chunk = chunk.strip()
        if not chunk:
            continue
        wav_bytes = _synthesize_chunk(chunk, speed, language, speaker)
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


def _run_inference_stream_once(model, text_chunk, language, spk_latent, spk_embedding, speed, chunk_queue):
    """One pass of inference_stream with CUDA OOM recovery. Returns the
    number of PCM chunks emitted. Raises on non-OOM failures so the
    caller can log + report to the client."""
    chunks_emitted = 0
    try:
        stream_gen = model.inference_stream(
            text=text_chunk,
            language=language,
            gpt_cond_latent=spk_latent,
            speaker_embedding=spk_embedding,
            speed=speed,
            stream_chunk_size=20,
        )
        for audio_chunk in stream_gen:
            if isinstance(audio_chunk, torch.Tensor):
                audio_chunk = audio_chunk.cpu().numpy()
            audio_chunk = audio_chunk.squeeze()
            pcm_int16 = np.clip(audio_chunk * 32767, -32768, 32767).astype(np.int16)
            chunk_queue.put(("pcm", pcm_int16.tobytes()))
            chunks_emitted += 1
    except torch.cuda.OutOfMemoryError:
        # VRAM pressure — another process grabbed memory since XTTS loaded.
        # Drop our own caches, ask PyTorch to return free blocks to the
        # allocator, and let the caller retry once. XTTS model tensors
        # themselves stay resident (permanent by design).
        print("[TTS] CUDA OOM during inference, clearing cache and retrying", flush=True)
        torch.cuda.empty_cache()
        raise
    return chunks_emitted


def _stream_inference(text: str, speed: float, language: str, chunk_queue: queue.Queue, speaker: str | None = None):
    """True streaming TTS using inference_stream() — yields PCM chunks as they're generated.

    Uses XTTS inference_stream() which internally handles crossfading between chunks.
    Each chunk is raw int16 PCM bytes (no WAV header) for gapless playback via SourceDataLine.
    Text is split into sentences first (XTTS char limit), then each sentence is streamed.
    """
    total_emitted = 0
    chunk_errors: list[str] = []
    try:
        if not language:
            language = _detect_language(text)

        model = _tts.synthesizer.tts_model
        sample_rate = _tts.synthesizer.output_sample_rate

        # Send sample rate as first event so client can open audio line
        chunk_queue.put(("header", {"sample_rate": sample_rate}))

        # Get speaker conditioning
        spk_latent, spk_embedding = _get_speaker(speaker)

        # Split text into sentence-level chunks (respecting XTTS char limit)
        text_chunks = _split_long_text(text)

        for text_chunk in text_chunks:
            text_chunk = text_chunk.strip().rstrip(".!?…,;:\"'""„‟»«")
            if not text_chunk:
                continue

            try:
                if spk_latent is not None and spk_embedding is not None:
                    # Try once; on CUDA OOM recover and retry once more.
                    try:
                        total_emitted += _run_inference_stream_once(
                            model, text_chunk, language, spk_latent, spk_embedding,
                            speed, chunk_queue,
                        )
                    except torch.cuda.OutOfMemoryError:
                        total_emitted += _run_inference_stream_once(
                            model, text_chunk, language, spk_latent, spk_embedding,
                            speed, chunk_queue,
                        )
                else:
                    # No speaker embedding — fall back to batch synthesis
                    wav_data = _synthesize_chunk(text_chunk, speed, language, speaker)
                    if wav_data:
                        with wave.open(io.BytesIO(wav_data), "rb") as wf:
                            chunk_queue.put(("pcm", wf.readframes(wf.getnframes())))
                        total_emitted += 1

            except Exception as e:
                # Per-chunk error — log + keep trying the next sentence.
                err_msg = f"{type(e).__name__}: {e}"
                chunk_errors.append(err_msg)
                print(f"[TTS] stream chunk error on '{text_chunk[:40]}': {err_msg}", flush=True)

        # If we did not emit a single PCM chunk AND we saw errors, the client
        # should surface an error rather than silently playing nothing — this
        # was the HEADER→DONE-without-PCM pathology observed in the desktop log.
        if total_emitted == 0 and chunk_errors:
            chunk_queue.put(("error", f"no audio produced; {len(chunk_errors)} chunk errors: {chunk_errors[0]}"))
        else:
            chunk_queue.put(("done", total_emitted))
    except Exception as e:
        print(f"[TTS] _stream_inference error: {e}", flush=True)
        import traceback
        traceback.print_exc()
        chunk_queue.put(("error", str(e)))


def _stream_inference_from_sentence_queue(
    sentence_queue: queue.Queue,
    speed: float,
    language: str,
    chunk_queue: queue.Queue,
    speaker: str | None = None,
):
    """Streaming TTS worker driven by a sentence queue (new pipeline).

    The producer (the gRPC handler's async normalize loop) pushes
    already-normalized sentences into `sentence_queue`. A `None` on the
    queue is the end-of-stream sentinel. This worker runs in a daemon
    thread because XTTS `inference_stream` is a blocking generator; it
    pulls each sentence, runs inference, and writes raw int16 PCM into
    `chunk_queue` as `("pcm", bytes)`. Terminal state is emitted as
    `("done", N)` or `("error", msg)` once the sentinel arrives.

    Compared to the legacy `_stream_inference` this never holds the full
    text: the normalizer streams sentences from the router LLM, and the
    moment the first sentence lands we start inference — the LLM and the
    GPU overlap their work, and the first PCM chunk arrives roughly one
    sentence of tokens after the user pressed play.
    """
    total_emitted = 0
    chunk_errors: list[str] = []
    worker_start = time.monotonic()
    first_sentence_logged = False
    first_pcm_logged = False
    sentence_index = 0
    try:
        if not language:
            language = TTS_LANGUAGE

        model = _tts.synthesizer.tts_model
        sample_rate = _tts.synthesizer.output_sample_rate
        chunk_queue.put(("header", {"sample_rate": sample_rate}))

        spk_latent, spk_embedding = _get_speaker(speaker)

        # Matches the [CS] / [EN] prefix the normalizer emits so we can
        # switch XTTS to the right language per sentence. Unknown / missing
        # tag falls back to the stream's default language.
        lang_prefix_re = re.compile(r"^\s*\[(CS|EN|cs|en)\]\s*(.*)$", re.DOTALL)

        while True:
            item = sentence_queue.get()
            if item is None:
                break  # producer signalled end-of-stream
            # Keep terminal punctuation (.!?…). XTTS uses it for the
            # falling / rising intonation at end of sentence; without it
            # the voice stops flat mid-thought. Only trim whitespace.
            sentence = item.strip()
            if not sentence:
                continue
            sentence_index += 1
            if not first_sentence_logged:
                print(
                    f"[TTS] WORKER FIRST_SENTENCE t={time.monotonic() - worker_start:.2f}s "
                    f"chars={len(sentence)} text={sentence[:80]!r}",
                    flush=True,
                )
                first_sentence_logged = True
            else:
                print(
                    f"[TTS] WORKER SENTENCE #{sentence_index} "
                    f"t={time.monotonic() - worker_start:.2f}s "
                    f"chars={len(sentence)} text={sentence[:80]!r}",
                    flush=True,
                )
            before_emitted = total_emitted
            inf_start = time.monotonic()

            # Peel off [CS] / [EN] prefix if present. Keep terminal
            # punctuation so XTTS applies proper sentence intonation.
            piece_lang = language
            m = lang_prefix_re.match(sentence)
            if m:
                piece_lang = m.group(1).lower()
                sentence = m.group(2).strip()
                if not sentence:
                    continue

            try:
                # Stream the sentence verbatim. The normalizer prompt tells
                # the LLM to keep lines under XTTS's ~180 char ceiling —
                # splitting here again would hide LLM mis-splits.
                if spk_latent is not None and spk_embedding is not None:
                    try:
                        total_emitted += _run_inference_stream_once(
                            model, sentence, piece_lang, spk_latent, spk_embedding,
                            speed, chunk_queue,
                        )
                    except torch.cuda.OutOfMemoryError:
                        total_emitted += _run_inference_stream_once(
                            model, sentence, piece_lang, spk_latent, spk_embedding,
                            speed, chunk_queue,
                        )
                else:
                    wav_data = _synthesize_chunk(sentence, speed, piece_lang, speaker)
                    if wav_data:
                        with wave.open(io.BytesIO(wav_data), "rb") as wf:
                            chunk_queue.put(("pcm", wf.readframes(wf.getnframes())))
                        total_emitted += 1
                emitted_now = total_emitted - before_emitted
                if emitted_now > 0 and not first_pcm_logged:
                    print(
                        f"[TTS] WORKER FIRST_PCM t={time.monotonic() - worker_start:.2f}s "
                        f"sentence_synth={time.monotonic() - inf_start:.2f}s "
                        f"chunks={emitted_now} lang={piece_lang}",
                        flush=True,
                    )
                    first_pcm_logged = True
                else:
                    print(
                        f"[TTS] WORKER SENTENCE #{sentence_index} DONE "
                        f"synth={time.monotonic() - inf_start:.2f}s "
                        f"chunks={emitted_now} lang={piece_lang}",
                        flush=True,
                    )
            except Exception as e:
                err_msg = f"{type(e).__name__}: {e}"
                chunk_errors.append(err_msg)
                print(f"[TTS] stream chunk error on '{sentence[:40]}': {err_msg}", flush=True)

        if total_emitted == 0 and chunk_errors:
            chunk_queue.put(("error", f"no audio produced; {len(chunk_errors)} chunk errors: {chunk_errors[0]}"))
        else:
            chunk_queue.put(("done", total_emitted))
    except Exception as e:
        print(f"[TTS] _stream_inference_from_sentence_queue error: {e}", flush=True)
        import traceback
        traceback.print_exc()
        chunk_queue.put(("error", str(e)))


def _warmup_inference() -> None:
    """Run one tiny dummy inference to compile CUDA kernels.

    Without this, the first real SpeakStream spends 10-15 s on JIT kernel
    compilation before emitting its first PCM chunk. Here we invoke
    inference_stream once with a throwaway utterance and drain the generator,
    so the first user-facing request gets the fast path.
    """
    if _tts is None or _gpt_cond_latent is None or _speaker_embedding is None:
        return
    try:
        print("[TTS] Warming up CUDA kernels...", flush=True)
        start = time.monotonic()
        model = _tts.synthesizer.tts_model
        stream_gen = model.inference_stream(
            text="ok",
            language=TTS_LANGUAGE,
            gpt_cond_latent=_gpt_cond_latent,
            speaker_embedding=_speaker_embedding,
            speed=1.0,
            stream_chunk_size=20,
        )
        for _ in stream_gen:
            pass  # discard audio — we only care about kernel compilation
        print(f"[TTS] Warmup complete in {time.monotonic() - start:.1f}s", flush=True)
    except Exception as e:
        print(f"[TTS] Warmup failed (non-fatal): {e}", flush=True)


async def _main() -> None:
    """gRPC-only entrypoint. Pre-loads XTTS + speaker embedding, runs a
    throwaway inference to compile CUDA kernels, then spins up the
    TtsService gRPC server and blocks until a termination signal arrives."""
    logger.info("[TTS] Starting XTTS v2 gRPC service on :%d", TTS_GRPC_PORT)
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, _load_tts)
    await loop.run_in_executor(None, _warmup_inference)
    logger.info("[TTS] Model + speaker loaded + warmed up, accepting gRPC requests")

    from app.grpc_server import start_grpc_server

    grpc_server = await start_grpc_server(port=TTS_GRPC_PORT)

    stop_event = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop_event.set)

    try:
        await stop_event.wait()
    finally:
        logger.info("[TTS] Shutting down gRPC server")
        await grpc_server.stop(grace=5.0)


# Entrypoint lives in `app/__main__.py` — running this file directly (or via
# `python -m app.xtts_server`) would create a duplicate module instance
# alongside `app.xtts_server` imported by grpc_server.py, doubling the load
# of every XTTS global. Always start via `python -m app`.
