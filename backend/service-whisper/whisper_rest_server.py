"""
Whisper REST server for Jervis.

Persistent FastAPI service that exposes Whisper transcription with optional
GPU acceleration (CUDA) and speaker diarization (pyannote-audio).

GPU VRAM coordination with Ollama on p40-2:
  - Whisper model is NOT pre-loaded. It's loaded on-demand when a transcription arrives.
  - Before loading, Ollama's VL model is unloaded to free VRAM.
  - After transcription + idle timeout (60s), whisper model is auto-unloaded from GPU.
  - Router can call POST /gpu/release to force-unload whisper for VL model loading.

Endpoints:
    POST /transcribe   – Upload audio file + JSON options → SSE stream of progress + result
    GET  /health       – Health check (includes gpu_loaded, active_transcriptions)
    POST /gpu/release  – Unload whisper model from GPU (called by router before VL load)

Environment variables:
    WHISPER_DEVICE         – "cuda" or "cpu" (default: "cpu")
    WHISPER_COMPUTE_TYPE   – CTranslate2 compute type (default: "auto" → int8_float32 on cuda)
    WHISPER_DEFAULT_MODEL  – Model name to use (default: "medium")
    WHISPER_GPU_IDLE_S     – Seconds to keep model loaded after last transcription (default: 60)
    OLLAMA_URL             – Ollama base URL on same GPU (default: "http://localhost:11434")
    OLLAMA_VLM_MODEL       – VLM model name to unload before whisper (default: "qwen3-vl-tool:latest")
    HF_TOKEN               – HuggingFace token for pyannote speaker diarization (optional)
"""
import asyncio
import json
import os
import queue
import shutil
import tempfile
import threading
import time
import traceback
import uuid

from contextlib import asynccontextmanager
from pathlib import Path

import requests as http_requests
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from sse_starlette.sse import EventSourceResponse

# Import the whisper_runner functions directly (same directory)
import whisper_runner

# ---------------------------------------------------------------------------
# Configuration from environment
# ---------------------------------------------------------------------------
DEVICE = os.environ.get("WHISPER_DEVICE", "cpu")
COMPUTE_TYPE = os.environ.get("WHISPER_COMPUTE_TYPE", "auto")
if COMPUTE_TYPE == "auto":
    # int8_float32 works on all GPUs including Pascal (P40) which lacks efficient float16.
    COMPUTE_TYPE = "int8_float32" if DEVICE == "cuda" else "int8"

DEFAULT_MODEL = os.environ.get("WHISPER_DEFAULT_MODEL", "medium")
GPU_IDLE_S = int(os.environ.get("WHISPER_GPU_IDLE_S", "60"))
OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434")
OLLAMA_VLM_MODEL = os.environ.get("OLLAMA_VLM_MODEL", "qwen3-vl-tool:latest")
HF_TOKEN = os.environ.get("HF_TOKEN", "")
ROUTER_URL = os.environ.get("ROUTER_URL", "")

# ---------------------------------------------------------------------------
# Global model cache — lazy loaded, auto-unloaded after idle.
# ---------------------------------------------------------------------------
_model_cache: dict = {}
_model_lock = threading.Lock()
_active_transcriptions = 0
_active_lock = threading.Lock()
_last_transcription_end: float = 0.0  # monotonic time of last transcription completion
_gpu_loaded = False  # True when whisper model is in GPU VRAM

# ---------------------------------------------------------------------------
# Speaker diarization pipeline (pyannote-audio) — loaded lazily with model.
# ---------------------------------------------------------------------------
_diarization_pipeline = None
_diarization_lock = threading.Lock()
_diarization_available = False


def _load_diarization_pipeline():
    """Try to load pyannote speaker diarization pipeline. Requires HF_TOKEN.

    Always runs on CPU — P40 (Pascal, compute capability 6.1) lacks CUDA kernel
    support for pyannote's neural network operations. Whisper uses CTranslate2
    which has its own Pascal-compatible CUDA kernels, but PyTorch (used by pyannote)
    ships without Pascal support in modern wheels.
    """
    global _diarization_pipeline, _diarization_available
    if not HF_TOKEN:
        print("HF_TOKEN not set — speaker diarization disabled", flush=True)
        return
    try:
        from pyannote.audio import Pipeline
        import torch
        print("Loading pyannote speaker diarization pipeline (CPU mode)...", flush=True)
        _diarization_pipeline = Pipeline.from_pretrained(
            "pyannote/speaker-diarization-3.1",
            token=HF_TOKEN,
        )
        # Force CPU — P40 Pascal lacks CUDA kernels for PyTorch ops used by pyannote
        _diarization_pipeline.to(torch.device("cpu"))
        print("Diarization pipeline loaded on CPU (Pascal GPU not supported for pyannote)", flush=True)
        _diarization_available = True
    except Exception as e:
        print(f"Failed to load diarization pipeline: {e}", flush=True)
        print("Speaker diarization will be disabled", flush=True)
        _diarization_available = False


def _router_notify_gpu():
    """Tell router we want GPU. Blocks until VLM finishes (if running)."""
    if not ROUTER_URL:
        return
    try:
        resp = http_requests.post(
            f"{ROUTER_URL}/router/whisper-notify",
            timeout=3600,
        )
        print(f"Router: GPU notify → {resp.status_code}", flush=True)
    except Exception as e:
        print(f"Router: notify failed ({e}), proceeding anyway", flush=True)


def _router_notify_done():
    """Tell router we're done with GPU."""
    if not ROUTER_URL:
        return
    try:
        http_requests.post(f"{ROUTER_URL}/router/whisper-done", timeout=10)
        print("Router: done notified", flush=True)
    except Exception as e:
        print(f"Router: done notify failed ({e})", flush=True)


def _unload_ollama_vlm():
    """Tell Ollama to unload VLM model to free VRAM for whisper."""
    try:
        resp = http_requests.post(
            f"{OLLAMA_URL}/api/generate",
            json={"model": OLLAMA_VLM_MODEL, "keep_alive": 0},
            timeout=15,
        )
        if resp.status_code == 200:
            print(f"Unloaded Ollama VLM ({OLLAMA_VLM_MODEL}) to free VRAM", flush=True)
        # Ignore errors — VLM might not be loaded
    except Exception:
        pass  # VLM not loaded or Ollama not reachable — fine


def _acquire_gpu():
    """Acquire GPU VRAM: unload Ollama VLM, load whisper model."""
    global _gpu_loaded
    if _gpu_loaded and DEFAULT_MODEL in _model_cache:
        return  # Already loaded
    if DEVICE == "cuda":
        _unload_ollama_vlm()
        time.sleep(1)  # Brief wait for Ollama to free VRAM
    _load_whisper_model()
    _gpu_loaded = True


def _release_gpu():
    """Release GPU VRAM: unload whisper model only.

    Diarization pipeline runs on CPU — no need to unload it.
    """
    global _model_cache, _gpu_loaded
    with _model_lock:
        if _model_cache:
            print(f"Unloading whisper model(s) from GPU: {list(_model_cache.keys())}", flush=True)
            _model_cache.clear()
        # Note: diarization pipeline runs on CPU, keep it loaded
        _gpu_loaded = False
    # Force CUDA memory cleanup
    if DEVICE == "cuda":
        try:
            import torch
            torch.cuda.empty_cache()
            import gc
            gc.collect()
        except Exception:
            pass
    print("GPU VRAM released", flush=True)


def _load_whisper_model():
    """Load whisper model into cache (lazy load)."""
    if DEFAULT_MODEL in _model_cache:
        return
    with _model_lock:
        if DEFAULT_MODEL not in _model_cache:
            from faster_whisper import WhisperModel
            print(
                f"Loading whisper model: {DEFAULT_MODEL} (device={DEVICE}, compute={COMPUTE_TYPE})",
                flush=True,
            )
            _model_cache[DEFAULT_MODEL] = WhisperModel(
                DEFAULT_MODEL, device=DEVICE, compute_type=COMPUTE_TYPE,
            )
            print(f"Whisper model {DEFAULT_MODEL} loaded", flush=True)


def get_model(model_name: str):
    """Get a WhisperModel (must be loaded via _acquire_gpu first)."""
    if model_name in _model_cache:
        return _model_cache[model_name]
    # Fallback: load the specific model if different from default
    with _model_lock:
        if model_name not in _model_cache:
            from faster_whisper import WhisperModel
            print(f"Loading model: {model_name} (device={DEVICE}, compute={COMPUTE_TYPE})", flush=True)
            _model_cache[model_name] = WhisperModel(
                model_name, device=DEVICE, compute_type=COMPUTE_TYPE,
            )
        return _model_cache[model_name]


def run_diarization(
    audio_path: str,
    request_id: str = "",
    progress_queue: queue.Queue | None = None,
    segments_done: int = 0,
) -> dict | None:
    """Run speaker diarization on audio file.

    Returns dict with 'turns' (list of speaker turns) and 'embeddings'
    (dict mapping speaker label → 256-dim float list), or None on failure.
    """
    if not _diarization_available or _diarization_pipeline is None:
        return None
    if not os.path.exists(audio_path):
        print(f"[{request_id}] Diarization aborted: audio file missing at {audio_path}", flush=True)
        return None
    try:
        print(f"[{request_id}] Running speaker diarization...", flush=True)
        start = time.time()
        # Heartbeat thread: pyannote runs synchronously and emits no progress;
        # without periodic SSE events, NAT/ingress can drop the connection
        # during long diarization (5–10 min on 20-min audio).
        stop_heartbeat = threading.Event()
        if progress_queue is not None:
            def _heartbeat():
                while not stop_heartbeat.wait(5.0):
                    elapsed = time.time() - start
                    progress_queue.put({
                        "percent": 99.9,
                        "segments_done": segments_done,
                        "elapsed_seconds": round(elapsed, 1),
                        "last_segment_text": f"diarizing... {elapsed:.0f}s",
                    })
            threading.Thread(target=_heartbeat, daemon=True).start()
        try:
            result = _diarization_pipeline(audio_path)
        finally:
            stop_heartbeat.set()
        # pyannote 4.x returns DiarizeOutput dataclass; 3.x returns Annotation directly
        annotation = getattr(result, "speaker_diarization", result)
        turns = []
        for turn, _, speaker in annotation.itertracks(yield_label=True):
            turns.append({
                "start": round(turn.start, 3),
                "end": round(turn.end, 3),
                "speaker": speaker,
            })
        # Extract speaker embeddings (256-dim float32 per speaker)
        embeddings = {}
        raw_embeddings = getattr(result, "speaker_embeddings", None)
        if raw_embeddings is not None:
            labels = list(annotation.labels())
            for i, label in enumerate(labels):
                if i < len(raw_embeddings):
                    embeddings[label] = raw_embeddings[i].tolist()
        elapsed = time.time() - start
        unique_speakers = len(set(t["speaker"] for t in turns))
        print(
            f"[{request_id}] Diarization complete: {len(turns)} turns, "
            f"{unique_speakers} speakers, {len(embeddings)} embeddings, {elapsed:.1f}s",
            flush=True,
        )
        return {"turns": turns, "embeddings": embeddings}
    except Exception as e:
        print(f"[{request_id}] Diarization failed: {e}", flush=True)
        traceback.print_exc()
        return None


def assign_speakers_to_segments(segments: list[dict], speaker_turns: list[dict]) -> list[dict]:
    """Merge speaker labels into whisper segments based on time overlap."""
    if not speaker_turns:
        return segments

    for seg in segments:
        seg_start = seg["start"]
        seg_end = seg["end"]
        best_speaker = None
        best_overlap = 0.0

        for turn in speaker_turns:
            overlap_start = max(seg_start, turn["start"])
            overlap_end = min(seg_end, turn["end"])
            overlap = max(0.0, overlap_end - overlap_start)
            if overlap > best_overlap:
                best_overlap = overlap
                best_speaker = turn["speaker"]

        if best_speaker:
            seg["speaker"] = best_speaker

    return segments


# ---------------------------------------------------------------------------
# GPU idle auto-unload background task
# ---------------------------------------------------------------------------
async def _gpu_idle_watchdog():
    """Periodically check if GPU should be released after idle timeout."""
    while True:
        try:
            await asyncio.sleep(15)
            if not _gpu_loaded:
                continue
            if _active_transcriptions > 0:
                continue
            if _last_transcription_end <= 0:
                continue
            idle_s = time.monotonic() - _last_transcription_end
            if idle_s >= GPU_IDLE_S:
                print(f"GPU idle for {idle_s:.0f}s (limit={GPU_IDLE_S}s) — auto-releasing", flush=True)
                _release_gpu()
        except asyncio.CancelledError:
            return
        except Exception as e:
            print(f"GPU idle watchdog error: {e}", flush=True)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Server startup — NO model pre-loading (lazy load on first request)."""
    print(
        f"Whisper REST server starting up (device={DEVICE}, compute={COMPUTE_TYPE}, "
        f"idle_timeout={GPU_IDLE_S}s, ollama={OLLAMA_URL})...",
        flush=True,
    )
    print(
        f"Model={DEFAULT_MODEL} (lazy-load on first transcription), "
        f"diarization={'enabled (HF_TOKEN set)' if HF_TOKEN else 'disabled (no HF_TOKEN)'}",
        flush=True,
    )
    # Start GPU idle watchdog
    idle_task = asyncio.create_task(_gpu_idle_watchdog())

    # Preload model at startup for fast first request (critical for watch voice chat)
    if DEVICE == "cpu":
        print(f"Preloading whisper model {DEFAULT_MODEL} at startup (CPU)...", flush=True)
        await asyncio.get_event_loop().run_in_executor(None, _load_whisper_model)
        print(f"Model {DEFAULT_MODEL} preloaded and ready", flush=True)
    else:
        print("Server ready, accepting requests (GPU model loaded on-demand)", flush=True)

    # Preload diarization pipeline (CPU) so the first diarize=true request
    # doesn't pay the 9s lazy-load cost and so _diarization_available is
    # deterministically True from start (no silent fallback to no-speakers).
    if HF_TOKEN:
        await asyncio.get_event_loop().run_in_executor(None, _load_diarization_pipeline)

    # Start pod-to-pod gRPC surface (:5501). FastAPI /transcribe + /health
    # routes were removed — every consumer dials WhisperService directly.
    from grpc_server import start_grpc_server

    grpc_port = int(os.getenv("WHISPER_GRPC_PORT", "5501"))
    grpc_server = await start_grpc_server(port=grpc_port)
    app.state.grpc_server = grpc_server
    try:
        yield
    finally:
        await grpc_server.stop(grace=5.0)
        idle_task.cancel()
        if _gpu_loaded:
            _release_gpu()
        print("Whisper REST server shut down", flush=True)


app = FastAPI(
    title="Jervis Whisper REST Service",
    version="3.1.0",
    lifespan=lifespan,
)


# /transcribe + /gpu/release migrated to gRPC
# (WhisperService.{Transcribe,GpuRelease} on :5501).
# /health stays for K8s probes; /diagnostic stays for operator debug.


@app.get("/health")
async def health():
    """K8s probe endpoint. Actual traffic runs on gRPC :5501."""
    return {"status": "ok", "service": "whisper"}


@app.get("/diagnostic")
async def diagnostic():
    """Diagnostic endpoint — check why diarization may not work."""
    diag = {
        "hf_token_set": bool(HF_TOKEN),
        "hf_token_prefix": HF_TOKEN[:8] + "..." if HF_TOKEN else "",
        "diarization_available": _diarization_available,
        "diarization_pipeline_loaded": _diarization_pipeline is not None,
    }

    # Check if pyannote is importable
    try:
        import pyannote.audio
        diag["pyannote_installed"] = True
        diag["pyannote_version"] = getattr(pyannote.audio, "__version__", "unknown")
    except ImportError as e:
        diag["pyannote_installed"] = False
        diag["pyannote_import_error"] = str(e)

    # Try loading pipeline if not loaded
    if not _diarization_available and HF_TOKEN:
        diag["load_attempt"] = "testing..."
        try:
            from pyannote.audio import Pipeline
            # Don't actually load (it downloads GB of data), just check prereqs
            diag["pipeline_class_available"] = True
        except ImportError as e:
            diag["pipeline_class_available"] = False
            diag["pipeline_import_error"] = str(e)

    return diag


def run_whisper(audio_path: str, opts: dict, progress_queue: queue.Queue, request_id: str = "") -> dict:
    """Run Whisper transcription (GPU must be acquired before calling this)."""
    task = opts.get("task", "transcribe")
    model_name = opts.get("model", DEFAULT_MODEL)
    language = opts.get("language")
    beam_size = opts.get("beam_size", 5)
    vad_filter = opts.get("vad_filter", True)
    word_timestamps = opts.get("word_timestamps", False)
    initial_prompt = opts.get("initial_prompt")
    condition_on_previous_text = opts.get("condition_on_previous_text", True)
    no_speech_threshold = opts.get("no_speech_threshold", 0.6)
    extraction_ranges = opts.get("extraction_ranges")
    do_diarize = opts.get("diarize", False)

    range_mapping = None
    cleanup_dir = None
    transcribe_path = audio_path

    if extraction_ranges:
        print(f"[{request_id}] Extraction mode: {len(extraction_ranges)} ranges", flush=True)
        cleanup_dir = tempfile.mkdtemp(prefix="whisper_extract_")
        try:
            transcribe_path, range_mapping = whisper_runner.extract_ranges(
                audio_path, extraction_ranges, cleanup_dir,
            )
        except Exception as e:
            if cleanup_dir:
                shutil.rmtree(cleanup_dir, ignore_errors=True)
            return {"text": "", "segments": [], "error": f"ffmpeg extraction failed: {str(e)[:500]}"}

    model = get_model(model_name)
    print(f"[{request_id}] Starting transcription: task={task}, model={model_name}, lang={language or 'auto'}", flush=True)

    transcribe_kwargs = {
        "task": task, "beam_size": beam_size, "vad_filter": vad_filter,
        "word_timestamps": word_timestamps, "condition_on_previous_text": condition_on_previous_text,
        "no_speech_threshold": no_speech_threshold, "log_progress": True,
    }
    if language:
        transcribe_kwargs["language"] = language
    if initial_prompt:
        transcribe_kwargs["initial_prompt"] = initial_prompt

    segments_iter, info = model.transcribe(transcribe_path, **transcribe_kwargs)

    total_duration = info.duration if info.duration and info.duration > 0 else None
    print(f"Audio duration: {total_duration:.1f}s, lang: {info.language} (prob={info.language_probability:.2f})", flush=True)

    out_segments = []
    all_text = []
    start_time = time.time()
    last_progress_time = 0

    for seg in segments_iter:
        out_segments.append({"start": float(seg.start), "end": float(seg.end), "text": seg.text})
        all_text.append(seg.text)

        now = time.time()
        if total_duration and (now - last_progress_time) >= 3:
            percent = min(99.9, (seg.end / total_duration) * 100)
            elapsed = now - start_time
            progress_queue.put({
                "percent": round(percent, 1), "segments_done": len(out_segments),
                "elapsed_seconds": round(elapsed, 1), "last_segment_text": seg.text.strip(),
            })
            print(f"[{request_id}] Progress: {percent:.1f}% ({len(out_segments)} segments)", flush=True)
            last_progress_time = now

    elapsed = time.time() - start_time
    progress_queue.put({"percent": 100.0, "segments_done": len(out_segments), "elapsed_seconds": round(elapsed, 1), "last_segment_text": ""})
    print(f"[{request_id}] Transcription complete: {len(out_segments)} segments, {elapsed:.1f}s", flush=True)

    # Speaker diarization
    speaker_turns = None
    speaker_embeddings = None
    if do_diarize and _diarization_available:
        diarize_result = run_diarization(
            audio_path, request_id, progress_queue, len(out_segments),
        )
        if diarize_result:
            speaker_turns = diarize_result["turns"]
            speaker_embeddings = diarize_result.get("embeddings")
            out_segments = assign_speakers_to_segments(out_segments, speaker_turns)

    # Build result
    if range_mapping is not None:
        mapped_segments = whisper_runner.map_segments_to_ranges(out_segments, range_mapping)
        text_by_segment = {}
        for ms in mapped_segments:
            si = ms["segment_index"]
            text_by_segment.setdefault(si, []).append(ms["text"])
        result = {
            "text": "".join(all_text), "segments": mapped_segments, "range_mapping": range_mapping,
            "text_by_segment": {str(k): " ".join(v).strip() for k, v in text_by_segment.items()},
            "language": info.language, "language_probability": round(info.language_probability, 3),
            "duration": info.duration,
        }
    else:
        result = {
            "text": "".join(all_text), "segments": out_segments,
            "language": info.language, "language_probability": round(info.language_probability, 3),
            "duration": info.duration,
        }

    if speaker_turns is not None:
        result["speakers"] = list(set(t["speaker"] for t in speaker_turns))
        result["speaker_turns"] = speaker_turns
    if speaker_embeddings:
        result["speaker_embeddings"] = speaker_embeddings

    if cleanup_dir:
        shutil.rmtree(cleanup_dir, ignore_errors=True)

    return result


if __name__ == "__main__":
    port = int(os.environ.get("WHISPER_REST_PORT", "8786"))
    host = os.environ.get("WHISPER_REST_HOST", "0.0.0.0")
    workers = int(os.environ.get("WHISPER_REST_WORKERS", "1"))

    print(f"Starting Whisper REST server on {host}:{port} (workers={workers})")
    uvicorn.run(
        "whisper_rest_server:app",
        host=host,
        port=port,
        workers=workers,
        log_level="info",
    )
