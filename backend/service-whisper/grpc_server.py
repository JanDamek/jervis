"""gRPC server for `service-whisper`.

Hosts WhisperService {Transcribe (stream), Health, GpuRelease} on :5501.
Transcribe bridges the existing REST handler's thread + progress queue
pattern into gRPC server streaming with fully-typed events.
"""

from __future__ import annotations

import asyncio
import logging
import os
import queue
import shutil
import tempfile
import traceback
import uuid
import time
from pathlib import Path

import grpc
from grpc_reflection.v1alpha import reflection

from jervis.whisper import transcribe_pb2, transcribe_pb2_grpc
from jervis_contracts.interceptors import ServerContextInterceptor

logger = logging.getLogger("whisper.grpc")


def _options_to_dict(options: transcribe_pb2.TranscribeOptions) -> dict:
    """Convert a typed TranscribeOptions proto into the kwargs dict that
    ``whisper_rest_server.run_whisper`` expects. Scalars with proto3
    "absent means zero" semantics fall back to the runner's defaults."""
    extraction_ranges = [
        {
            "start": float(r.start_sec),
            "end": float(r.end_sec),
            "segment_index": int(r.segment_index),
        }
        for r in options.extraction_ranges
    ] if options.extraction_ranges else None

    return {
        "task": options.task or "transcribe",
        "model": options.model or None,
        "language": options.language or None,
        "beam_size": int(options.beam_size) if options.beam_size > 0 else 5,
        "vad_filter": bool(options.vad_filter),
        "word_timestamps": bool(options.word_timestamps),
        "initial_prompt": options.initial_prompt or None,
        "condition_on_previous_text": bool(options.condition_on_previous_text),
        "no_speech_threshold": float(options.no_speech_threshold) if options.no_speech_threshold > 0 else 0.6,
        "extraction_ranges": extraction_ranges,
        "diarize": bool(options.diarize),
    }


def _segments_to_proto(segments: list[dict]) -> list[transcribe_pb2.TranscribeSegment]:
    out: list[transcribe_pb2.TranscribeSegment] = []
    for i, seg in enumerate(segments or []):
        out.append(transcribe_pb2.TranscribeSegment(
            i=int(seg.get("i", i)),
            start_sec=float(seg.get("start", 0.0)),
            end_sec=float(seg.get("end", 0.0)),
            text=str(seg.get("text", "")),
            speaker=str(seg.get("speaker") or ""),
        ))
    return out


def _result_to_event(result: dict) -> transcribe_pb2.TranscribeEvent:
    """Convert the whisper runner's result dict into a typed ResultEvent."""
    speakers = list(result.get("speakers") or [])

    speaker_embeddings: list[transcribe_pb2.SpeakerEmbedding] = []
    raw_embeddings = result.get("speaker_embeddings") or {}
    for label, values in raw_embeddings.items():
        speaker_embeddings.append(transcribe_pb2.SpeakerEmbedding(
            label=str(label),
            values=[float(v) for v in values],
        ))

    # text_by_segment keys are ints (segment indices) in the typed proto,
    # even though the runner stringifies them for the legacy JSON shape.
    raw_text_by_segment = result.get("text_by_segment") or {}
    text_by_segment: dict[int, str] = {}
    for k, v in raw_text_by_segment.items():
        try:
            text_by_segment[int(k)] = str(v)
        except (TypeError, ValueError):
            continue

    return transcribe_pb2.TranscribeEvent(
        result=transcribe_pb2.ResultEvent(
            text=str(result.get("text", "")),
            language=str(result.get("language") or ""),
            language_probability=float(result.get("language_probability") or 0.0),
            duration=float(result.get("duration") or 0.0),
            segments=_segments_to_proto(result.get("segments") or []),
            speakers=speakers,
            speaker_embeddings=speaker_embeddings,
            text_by_segment=text_by_segment,
        ),
    )


class WhisperServicer(transcribe_pb2_grpc.WhisperServiceServicer):
    """WhisperService implementation.

    Reuses helpers from `whisper_rest_server` (router GPU coord, whisper
    runner, diarization) so the gRPC path has byte-for-byte identical
    behavior to the removed REST handler.
    """

    async def Transcribe(
        self,
        request: transcribe_pb2.TranscribeRequest,
        context: grpc.aio.ServicerContext,
    ):
        import whisper_rest_server as wrs

        if not request.audio and not request.blob_ref:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "audio or blob_ref required")

        opts = _options_to_dict(request.options)
        if not opts["model"]:
            opts["model"] = wrs.DEFAULT_MODEL

        work_dir = tempfile.mkdtemp(prefix="whisper_grpc_")
        audio_ext = Path(request.filename or "audio.wav").suffix or ".wav"
        audio_path = os.path.join(work_dir, f"input{audio_ext}")
        with open(audio_path, "wb") as f:
            f.write(request.audio)

        cleanup_done = __import__("threading").Event()
        request_id = uuid.uuid4().hex[:8]
        model_name = opts.get("model", wrs.DEFAULT_MODEL)
        task_name = opts.get("task", "transcribe")
        do_diarize = opts.get("diarize", False)
        logger.info(
            "[%s] Transcription request (gRPC): model=%s task=%s diarize=%s size=%d",
            request_id, model_name, task_name, do_diarize, len(request.audio),
        )

        with wrs._active_lock:
            wrs._active_transcriptions += 1
        progress_queue: queue.Queue = queue.Queue()

        try:
            loop = asyncio.get_event_loop()
            result_container: dict = {}
            error_container: dict = {}
            done_event = asyncio.Event()

            def run_in_thread():
                try:
                    wrs._router_notify_gpu()
                    wrs._acquire_gpu()
                    if do_diarize and not wrs._diarization_available and wrs.HF_TOKEN:
                        wrs._load_diarization_pipeline()
                    result_container["result"] = wrs.run_whisper(
                        audio_path, opts, progress_queue, request_id,
                    )
                except Exception as e:
                    error_container["error"] = str(e)
                    traceback.print_exc()
                finally:
                    wrs._last_transcription_end = time.monotonic()
                    wrs._router_notify_done()
                    shutil.rmtree(work_dir, ignore_errors=True)
                    cleanup_done.set()
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
                        yield transcribe_pb2.TranscribeEvent(
                            progress=transcribe_pb2.ProgressEvent(
                                percent=float(percent),
                                segments_done=int(latest_progress.get("segments_done", 0)),
                                elapsed_seconds=float(latest_progress.get("elapsed_seconds", 0.0)),
                                last_segment_text=str(latest_progress.get("last_segment_text") or ""),
                            ),
                        )

            while True:
                try:
                    progress_queue.get_nowait()
                except queue.Empty:
                    break

            if "error" in error_container:
                yield transcribe_pb2.TranscribeEvent(
                    error=transcribe_pb2.ErrorEvent(
                        text="",
                        error=f"Whisper gRPC server error: {error_container['error']}",
                    ),
                )
            else:
                yield _result_to_event(result_container.get("result") or {})
        finally:
            with wrs._active_lock:
                wrs._active_transcriptions -= 1

    async def Health(
        self,
        request: transcribe_pb2.HealthRequest,
        context: grpc.aio.ServicerContext,
    ) -> transcribe_pb2.HealthResponse:
        import whisper_rest_server as wrs

        return transcribe_pb2.HealthResponse(
            ok=True,
            status="ok",
            model_loaded=bool(getattr(wrs, "_model", None) is not None),
            detail="",
        )

    async def GpuRelease(
        self,
        request: transcribe_pb2.GpuReleaseRequest,
        context: grpc.aio.ServicerContext,
    ) -> transcribe_pb2.GpuReleaseResponse:
        import whisper_rest_server as wrs

        try:
            wrs._release_gpu()
        except Exception as e:
            logger.warning("GPU_RELEASE_ERROR: %s", e)
            return transcribe_pb2.GpuReleaseResponse(released=False)
        return transcribe_pb2.GpuReleaseResponse(released=True)


async def start_grpc_server(port: int = 5501) -> grpc.aio.Server:
    from jervis_contracts.grpc_options import build_server_options

    # Meeting audio files can be >64 MiB per transcribe.
    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=build_server_options(max_msg_bytes=256 * 1024 * 1024),
    )
    transcribe_pb2_grpc.add_WhisperServiceServicer_to_server(WhisperServicer(), server)

    service_names = (
        transcribe_pb2.DESCRIPTOR.services_by_name["WhisperService"].full_name,
        reflection.SERVICE_NAME,
    )
    reflection.enable_server_reflection(service_names, server)

    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info("gRPC Whisper service listening on :%d", port)
    return server
