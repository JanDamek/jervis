"""gRPC server for `service-whisper`.

Hosts WhisperService {Transcribe (stream), Health, GpuRelease} on :5501.
Transcribe bridges the existing REST handler's thread + progress queue
pattern into gRPC server streaming.
"""

from __future__ import annotations

import asyncio
import json
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

        try:
            opts = json.loads(request.options_json) if request.options_json else {}
        except Exception as e:
            yield transcribe_pb2.TranscribeEvent(
                event="error",
                data_json=json.dumps({"text": "", "segments": [], "error": f"Invalid options JSON: {e}"}),
            )
            return

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
            opts.pop("progress_file", None)

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
                            event="progress",
                            data_json=json.dumps(latest_progress, ensure_ascii=False),
                        )

            while True:
                try:
                    progress_queue.get_nowait()
                except queue.Empty:
                    break

            if "error" in error_container:
                yield transcribe_pb2.TranscribeEvent(
                    event="error",
                    data_json=json.dumps({
                        "text": "",
                        "segments": [],
                        "error": f"Whisper gRPC server error: {error_container['error']}",
                    }, ensure_ascii=False),
                )
            else:
                yield transcribe_pb2.TranscribeEvent(
                    event="result",
                    data_json=json.dumps(result_container.get("result") or {}, ensure_ascii=False, default=str),
                )
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
    max_msg_bytes = 256 * 1024 * 1024  # meeting audio files can be large
    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=[
            ("grpc.max_receive_message_length", max_msg_bytes),
            ("grpc.max_send_message_length", max_msg_bytes),
        ],
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
