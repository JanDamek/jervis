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
    """Try to load pyannote speaker diarization pipeline. Requires HF_TOKEN."""
    global _diarization_pipeline, _diarization_available
    if not HF_TOKEN:
        print("HF_TOKEN not set — speaker diarization disabled", flush=True)
        return
    try:
        from pyannote.audio import Pipeline
        print("Loading pyannote speaker diarization pipeline...", flush=True)
        _diarization_pipeline = Pipeline.from_pretrained(
            "pyannote/speaker-diarization-3.1",
            token=HF_TOKEN,
        )
        if DEVICE == "cuda":
            import torch
            _diarization_pipeline.to(torch.device("cuda"))
            print("Diarization pipeline loaded on CUDA", flush=True)
        else:
            print("Diarization pipeline loaded on CPU", flush=True)
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
    """Release GPU VRAM: unload whisper model and diarization pipeline."""
    global _model_cache, _gpu_loaded, _diarization_pipeline, _diarization_available
    with _model_lock:
        if _model_cache:
            print(f"Unloading whisper model(s) from GPU: {list(_model_cache.keys())}", flush=True)
            _model_cache.clear()
        if _diarization_pipeline is not None:
            print("Unloading diarization pipeline from GPU", flush=True)
            _diarization_pipeline = None
            _diarization_available = False
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


def run_diarization(audio_path: str, request_id: str = "") -> list[dict] | None:
    """Run speaker diarization on audio file. Returns list of speaker turns or None."""
    if not _diarization_available or _diarization_pipeline is None:
        return None
    try:
        print(f"[{request_id}] Running speaker diarization...", flush=True)
        start = time.time()
        diarization = _diarization_pipeline(audio_path)
        turns = []
        for turn, _, speaker in diarization.itertracks(yield_label=True):
            turns.append({
                "start": round(turn.start, 3),
                "end": round(turn.end, 3),
                "speaker": speaker,
            })
        elapsed = time.time() - start
        unique_speakers = len(set(t["speaker"] for t in turns))
        print(
            f"[{request_id}] Diarization complete: {len(turns)} turns, "
            f"{unique_speakers} speakers, {elapsed:.1f}s",
            flush=True,
        )
        return turns
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
    print("Server ready, accepting requests (GPU not loaded yet)", flush=True)
    yield
    idle_task.cancel()
    if _gpu_loaded:
        _release_gpu()
    print("Whisper REST server shut down", flush=True)


app = FastAPI(
    title="Jervis Whisper REST Service",
    version="3.1.0",
    lifespan=lifespan,
)


@app.get("/health")
async def health():
    """Health check endpoint."""
    return {
        "status": "ok",
        "service": "whisper-rest",
        "device": DEVICE,
        "compute_type": COMPUTE_TYPE,
        "gpu_loaded": _gpu_loaded,
        "diarization_enabled": bool(HF_TOKEN),
        "diarization_available": _diarization_available,
        "timestamp": time.time(),
        "active_transcriptions": _active_transcriptions,
        "cached_models": list(_model_cache.keys()),
    }


@app.post("/gpu/release")
async def gpu_release():
    """Release GPU VRAM (called by router before loading VL model on p40-2).

    Returns immediately if no model loaded or transcription in progress.
    If transcription is active, returns 409 Conflict.
    """
    if _active_transcriptions > 0:
        return JSONResponse(
            status_code=409,
            content={"status": "busy", "active_transcriptions": _active_transcriptions},
        )
    if not _gpu_loaded:
        return {"status": "already_released", "gpu_loaded": False}
    _release_gpu()
    return {"status": "released", "gpu_loaded": False}


@app.post("/transcribe")
async def transcribe(
    audio: UploadFile = File(..., description="Audio file to transcribe"),
    options: str = Form(default="{}", description="JSON string with transcription options"),
):
    """
    Transcribe an uploaded audio file using Whisper with SSE streaming.

    GPU VRAM lifecycle:
    1. Unloads Ollama VLM if loaded (free VRAM)
    2. Loads whisper model to GPU
    3. Transcribes
    4. Auto-unloads after idle timeout (GPU_IDLE_S)

    The options JSON supports:
    - task, model, language, beam_size, vad_filter, word_timestamps,
      initial_prompt, condition_on_previous_text, no_speech_threshold,
      extraction_ranges, diarize (bool — enable speaker diarization)
    """
    try:
        opts = json.loads(options)
    except (json.JSONDecodeError, ValueError) as e:
        raise HTTPException(status_code=400, detail=f"Invalid options JSON: {e}")

    work_dir = tempfile.mkdtemp(prefix="whisper_rest_")
    audio_ext = Path(audio.filename or "audio.wav").suffix or ".wav"
    audio_path = os.path.join(work_dir, f"input{audio_ext}")
    with open(audio_path, "wb") as f:
        shutil.copyfileobj(audio.file, f)

    file_size = os.path.getsize(audio_path)
    request_id = uuid.uuid4().hex[:8]
    model_name = opts.get("model", DEFAULT_MODEL)
    task_name = opts.get("task", "transcribe")
    do_diarize = opts.get("diarize", False)

    print(
        f"[{request_id}] Transcription request: model={model_name}, task={task_name}, "
        f"diarize={do_diarize}, file={audio.filename} ({file_size} bytes)",
        flush=True,
    )

    progress_queue = queue.Queue()

    async def event_generator():
        global _active_transcriptions, _last_transcription_end
        with _active_lock:
            _active_transcriptions += 1
        try:
            opts.pop("progress_file", None)

            loop = asyncio.get_event_loop()
            result_container = {}
            error_container = {}
            done_event = asyncio.Event()

            def run_in_thread():
                try:
                    _router_notify_gpu()   # Tell router: I want GPU (blocks until VLM done)
                    _acquire_gpu()         # Unload VLM + load whisper
                    # Load diarization pipeline if needed and not yet loaded
                    if do_diarize and not _diarization_available and HF_TOKEN:
                        _load_diarization_pipeline()
                    result_container["result"] = run_whisper(
                        audio_path, opts, progress_queue, request_id,
                    )
                except Exception as e:
                    error_container["error"] = str(e)
                    traceback.print_exc()
                finally:
                    _last_transcription_end = time.monotonic()
                    _router_notify_done()  # Always tell router we're done
                    loop.call_soon_threadsafe(done_event.set)

            loop.run_in_executor(None, run_in_thread)

            last_percent = -1.0
            while not done_event.is_set():
                try:
                    await asyncio.wait_for(done_event.wait(), timeout=3.0)
                except asyncio.TimeoutError:
                    pass

                latest_progress = None
                while True:
                    try:
                        latest_progress = progress_queue.get_nowait()
                    except queue.Empty:
                        break

                if latest_progress is not None:
                    percent = latest_progress.get("percent", 0)
                    if percent != last_percent:
                        last_percent = percent
                        yield {
                            "event": "progress",
                            "data": json.dumps(latest_progress),
                        }

            while True:
                try:
                    progress_queue.get_nowait()
                except queue.Empty:
                    break

            if "error" in error_container:
                error_msg = error_container["error"]
                print(f"[{request_id}] Transcription failed: {error_msg}", flush=True)
                yield {
                    "event": "error",
                    "data": json.dumps({
                        "text": "",
                        "segments": [],
                        "error": f"Whisper REST server error: {error_msg}",
                    }),
                }
            else:
                result = result_container.get("result", {})
                if result.get("error"):
                    print(f"[{request_id}] Transcription error: {result['error']}", flush=True)
                    yield {
                        "event": "error",
                        "data": json.dumps(result),
                    }
                else:
                    print(
                        f"[{request_id}] Transcription complete: "
                        f"{len(result.get('segments', []))} segments, "
                        f"{len(result.get('text', ''))} chars",
                        flush=True,
                    )
                    yield {
                        "event": "progress",
                        "data": json.dumps({
                            "percent": 100.0,
                            "segments_done": len(result.get("segments", [])),
                            "elapsed_seconds": 0,
                            "last_segment_text": "",
                        }),
                    }
                    yield {
                        "event": "result",
                        "data": json.dumps(result, ensure_ascii=False),
                    }

        except Exception as e:
            traceback.print_exc()
            yield {
                "event": "error",
                "data": json.dumps({
                    "text": "",
                    "segments": [],
                    "error": f"Whisper REST server error: {str(e)}",
                }),
            }
        finally:
            with _active_lock:
                _active_transcriptions -= 1
            shutil.rmtree(work_dir, ignore_errors=True)

    return EventSourceResponse(event_generator(), ping=15)


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
    if do_diarize and _diarization_available:
        speaker_turns = run_diarization(audio_path, request_id)
        if speaker_turns:
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
