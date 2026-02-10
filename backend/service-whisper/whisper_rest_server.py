"""
Whisper REST server for Jervis.

Persistent FastAPI service that exposes the same Whisper transcription capabilities
as the K8s Job variant, but via REST API. Intended for deployment on a dedicated
machine with sufficient RAM (e.g. 192.168.100.117).

Endpoints:
    POST /transcribe  – Upload audio file + JSON options → transcription result
    GET  /health      – Health check

The audio file is uploaded as multipart/form-data, options as a JSON string field.
Response is the same JSON as whisper_runner.py produces.
"""
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
    version="1.0.0",
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
    Transcribe an uploaded audio file using Whisper.

    The options JSON supports the same parameters as whisper_runner.py:
    - task: "transcribe" | "translate"
    - model: "tiny" | "base" | "small" | "medium" | "large-v3"
    - language: ISO 639-1 code or null for auto-detect
    - beam_size: 1-10
    - vad_filter: boolean
    - word_timestamps: boolean
    - initial_prompt: vocabulary hints string
    - condition_on_previous_text: boolean
    - no_speech_threshold: 0.0-1.0
    - extraction_ranges: [{start, end, segment_index}, ...] for re-transcription
    """
    work_dir = tempfile.mkdtemp(prefix="whisper_rest_")
    try:
        # Parse options
        try:
            opts = json.loads(options)
        except (json.JSONDecodeError, ValueError) as e:
            raise HTTPException(status_code=400, detail=f"Invalid options JSON: {e}")

        # Save uploaded audio to temp file
        audio_ext = Path(audio.filename or "audio.wav").suffix or ".wav"
        audio_path = os.path.join(work_dir, f"input{audio_ext}")
        with open(audio_path, "wb") as f:
            shutil.copyfileobj(audio.file, f)

        file_size = os.path.getsize(audio_path)
        request_id = uuid.uuid4().hex[:8]
        model_name = opts.get("model", "base")
        task = opts.get("task", "transcribe")

        print(
            f"[{request_id}] Transcription request: model={model_name}, task={task}, "
            f"file={audio.filename} ({file_size} bytes), options={opts}",
            flush=True,
        )

        # Set up progress file inside work_dir
        progress_path = os.path.join(work_dir, "progress.json")
        opts["progress_file"] = progress_path

        start_time = time.time()

        # Run transcription using whisper_runner logic directly
        result = run_whisper(audio_path, opts)

        elapsed = time.time() - start_time
        print(
            f"[{request_id}] Transcription complete in {elapsed:.1f}s: "
            f"{len(result.get('segments', []))} segments, "
            f"{len(result.get('text', ''))} chars",
            flush=True,
        )

        return JSONResponse(content=result)

    except HTTPException:
        raise
    except Exception as e:
        traceback.print_exc()
        return JSONResponse(
            status_code=500,
            content={
                "text": "",
                "segments": [],
                "error": f"Whisper REST server error: {str(e)}",
            },
        )
    finally:
        # Clean up temp files
        shutil.rmtree(work_dir, ignore_errors=True)


def run_whisper(audio_path: str, opts: dict) -> dict:
    """
    Run Whisper transcription in-process.
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
        if progress_file and total_duration and (now - last_progress_time) >= 5:
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
