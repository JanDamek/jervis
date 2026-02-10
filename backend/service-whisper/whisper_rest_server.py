"""
Whisper REST server for Jervis.

Persistent FastAPI service that exposes the same Whisper transcription capabilities
as the K8s Job variant, but via REST API with SSE streaming for progress updates.

Endpoints:
    POST /transcribe  – Upload audio file + JSON options → SSE stream of progress + result
    GET  /health      – Health check

SSE event types:
    progress  – {"percent": 45.2, "segments_done": 128, "elapsed_seconds": 340}
    result    – Full Whisper result JSON (same as whisper_runner.py output)
    error     – {"error": "description"}

The audio file is uploaded as multipart/form-data, options as a JSON string field.
"""
import asyncio
import json
import os
import shutil
import tempfile
import time
import traceback
import uuid

from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from sse_starlette.sse import EventSourceResponse

# Import the whisper_runner functions directly (same directory)
import whisper_runner


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Pre-load default model on startup for faster first request."""
    print("Whisper REST server starting up...", flush=True)
    yield
    print("Whisper REST server shutting down...", flush=True)


app = FastAPI(
    title="Jervis Whisper REST Service",
    version="2.0.0",
    lifespan=lifespan,
)


@app.get("/health")
async def health():
    """Health check endpoint."""
    return {"status": "ok", "service": "whisper-rest", "timestamp": time.time()}


@app.post("/transcribe")
async def transcribe(
    audio: UploadFile = File(..., description="Audio file to transcribe"),
    options: str = Form(default="{}", description="JSON string with transcription options"),
):
    """
    Transcribe an uploaded audio file using Whisper with SSE streaming.

    Returns a Server-Sent Events stream with three event types:
    - "progress": periodic updates with percent, segments_done, elapsed_seconds
    - "result": final transcription result (same JSON as whisper_runner.py)
    - "error": error details if transcription fails

    The options JSON supports the same parameters as whisper_runner.py:
    - task, model, language, beam_size, vad_filter, word_timestamps,
      initial_prompt, condition_on_previous_text, no_speech_threshold,
      extraction_ranges
    """
    # Parse options early to fail fast on invalid JSON
    try:
        opts = json.loads(options)
    except (json.JSONDecodeError, ValueError) as e:
        raise HTTPException(status_code=400, detail=f"Invalid options JSON: {e}")

    # Save uploaded audio to temp dir (must happen before we enter the generator,
    # because UploadFile is only readable in the request context)
    work_dir = tempfile.mkdtemp(prefix="whisper_rest_")
    audio_ext = Path(audio.filename or "audio.wav").suffix or ".wav"
    audio_path = os.path.join(work_dir, f"input{audio_ext}")
    with open(audio_path, "wb") as f:
        shutil.copyfileobj(audio.file, f)

    file_size = os.path.getsize(audio_path)
    request_id = uuid.uuid4().hex[:8]
    model_name = opts.get("model", "base")
    task_name = opts.get("task", "transcribe")

    print(
        f"[{request_id}] Transcription request: model={model_name}, task={task_name}, "
        f"file={audio.filename} ({file_size} bytes)",
        flush=True,
    )

    async def event_generator():
        try:
            # Set up progress file inside work_dir
            progress_path = os.path.join(work_dir, "progress.json")
            opts["progress_file"] = progress_path

            # Run transcription in a thread (CPU-bound) and poll progress
            loop = asyncio.get_event_loop()
            result_container = {}
            error_container = {}
            done_event = asyncio.Event()

            def run_in_thread():
                try:
                    result_container["result"] = run_whisper(audio_path, opts)
                except Exception as e:
                    error_container["error"] = str(e)
                    traceback.print_exc()
                finally:
                    # Signal that transcription is done
                    loop.call_soon_threadsafe(done_event.set)

            # Start transcription in background thread
            loop.run_in_executor(None, run_in_thread)

            # Poll progress file and stream SSE events
            last_percent = -1.0
            while not done_event.is_set():
                # Wait up to 3 seconds for completion, then send progress
                try:
                    await asyncio.wait_for(done_event.wait(), timeout=3.0)
                except asyncio.TimeoutError:
                    pass

                # Read progress from file
                if os.path.exists(progress_path):
                    try:
                        with open(progress_path) as pf:
                            progress = json.load(pf)
                        percent = progress.get("percent", 0)
                        if percent != last_percent:
                            last_percent = percent
                            yield {
                                "event": "progress",
                                "data": json.dumps({
                                    "percent": progress.get("percent", 0),
                                    "segments_done": progress.get("segments_done", 0),
                                    "elapsed_seconds": progress.get("elapsed_seconds", 0),
                                }),
                            }
                    except (json.JSONDecodeError, OSError):
                        pass  # File being written, skip this cycle

            # Transcription done — send result or error
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
                    # Send final 100% progress
                    yield {
                        "event": "progress",
                        "data": json.dumps({
                            "percent": 100.0,
                            "segments_done": len(result.get("segments", [])),
                            "elapsed_seconds": 0,
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
            # Clean up temp files
            shutil.rmtree(work_dir, ignore_errors=True)

    return EventSourceResponse(event_generator())


def run_whisper(audio_path: str, opts: dict) -> dict:
    """
    Run Whisper transcription in-process (blocking, CPU-bound).
    Reuses whisper_runner.py logic but captures output as dict instead of stdout.
    """
    from faster_whisper import WhisperModel

    task = opts.get("task", "transcribe")
    model_name = opts.get("model", "base")
    language = opts.get("language")
    beam_size = opts.get("beam_size", 5)
    vad_filter = opts.get("vad_filter", True)
    word_timestamps = opts.get("word_timestamps", False)
    initial_prompt = opts.get("initial_prompt")
    condition_on_previous_text = opts.get("condition_on_previous_text", True)
    no_speech_threshold = opts.get("no_speech_threshold", 0.6)
    progress_file = opts.get("progress_file")
    extraction_ranges = opts.get("extraction_ranges")

    # Handle extraction_ranges mode
    range_mapping = None
    cleanup_dir = None
    transcribe_path = audio_path

    if extraction_ranges:
        print(
            f"Extraction mode: {len(extraction_ranges)} ranges from {audio_path}",
            flush=True,
        )
        cleanup_dir = tempfile.mkdtemp(prefix="whisper_extract_")
        try:
            transcribe_path, range_mapping = whisper_runner.extract_ranges(
                audio_path, extraction_ranges, cleanup_dir,
            )
        except Exception as e:
            if cleanup_dir:
                shutil.rmtree(cleanup_dir, ignore_errors=True)
            return {
                "text": "",
                "segments": [],
                "error": f"ffmpeg extraction failed: {str(e)[:500]}",
            }

    print(f"Loading model: {model_name} (device=cpu)", flush=True)
    model = WhisperModel(model_name, device="cpu")
    print(f"Model loaded, starting transcription: task={task}, lang={language or 'auto'}", flush=True)

    # Build transcribe kwargs
    transcribe_kwargs = {
        "task": task,
        "beam_size": beam_size,
        "vad_filter": vad_filter,
        "word_timestamps": word_timestamps,
        "condition_on_previous_text": condition_on_previous_text,
        "no_speech_threshold": no_speech_threshold,
        "log_progress": True,
    }
    if language:
        transcribe_kwargs["language"] = language
    if initial_prompt:
        transcribe_kwargs["initial_prompt"] = initial_prompt

    segments_iter, info = model.transcribe(transcribe_path, **transcribe_kwargs)

    total_duration = info.duration if info.duration and info.duration > 0 else None
    print(
        f"Audio duration: {total_duration:.1f}s, detected language: {info.language} "
        f"(prob={info.language_probability:.2f})",
        flush=True,
    )

    out_segments = []
    all_text = []
    start_time = time.time()
    last_progress_time = 0

    for seg in segments_iter:
        out_segments.append({
            "start": float(seg.start),
            "end": float(seg.end),
            "text": seg.text,
        })
        all_text.append(seg.text)

        now = time.time()
        if progress_file and total_duration and (now - last_progress_time) >= 3:
            percent = min(99.9, (seg.end / total_duration) * 100)
            elapsed = now - start_time
            whisper_runner.write_progress(progress_file, percent, len(out_segments), elapsed)
            last_progress_time = now

    elapsed = time.time() - start_time
    if progress_file:
        whisper_runner.write_progress(progress_file, 100.0, len(out_segments), elapsed)

    print(
        f"Transcription complete: {len(out_segments)} segments, "
        f"{len(''.join(all_text))} chars, {elapsed:.1f}s",
        flush=True,
    )

    # Map segments back to original ranges if extraction mode
    if range_mapping is not None:
        mapped_segments = whisper_runner.map_segments_to_ranges(out_segments, range_mapping)
        text_by_segment = {}
        for ms in mapped_segments:
            si = ms["segment_index"]
            text_by_segment.setdefault(si, []).append(ms["text"])

        result = {
            "text": "".join(all_text),
            "segments": mapped_segments,
            "range_mapping": range_mapping,
            "text_by_segment": {str(k): " ".join(v).strip() for k, v in text_by_segment.items()},
            "language": info.language,
            "language_probability": round(info.language_probability, 3),
            "duration": info.duration,
        }
    else:
        result = {
            "text": "".join(all_text),
            "segments": out_segments,
            "language": info.language,
            "language_probability": round(info.language_probability, 3),
            "duration": info.duration,
        }

    # Cleanup extraction temp files
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
