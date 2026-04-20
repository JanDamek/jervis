"""gRPC server for `service-tts` (XTTS v2 backend).

Hosts TtsService {Speak, SpeakStream} on :5501. FastAPI /tts + /tts/stream
routes are removed; every consumer dials gRPC.

Backed by Coqui XTTS v2 helpers in `xtts_server` (multilingual neural TTS
with voice cloning). Runs on the VD GPU VM alongside whisper — not inside
K8s, per feedback-audio-services-on-vd.md.
"""

from __future__ import annotations

import asyncio
import logging
import queue
import threading
import time

import grpc
from grpc_reflection.v1alpha import reflection

from jervis.tts import speak_pb2, speak_pb2_grpc
from jervis_contracts.interceptors import ServerContextInterceptor

logger = logging.getLogger("tts.grpc")


class TtsServicer(speak_pb2_grpc.TtsServiceServicer):
    """TtsService implementation backed by XTTS v2.

    `Speak` invokes the blocking `_synthesize_text` helper on a thread; the
    response carries a full WAV blob. `SpeakStream` uses
    `_stream_inference` which produces raw int16 PCM chunks via XTTS
    `inference_stream()` — the first chunk arrives in ~2s on P40 and each
    subsequent chunk streams continuously for gapless playback.
    """

    async def Speak(
        self,
        request: speak_pb2.SpeakRequest,
        context: grpc.aio.ServicerContext,
    ) -> speak_pb2.SpeakResponse:
        from app import xtts_server

        text = (request.text or "").strip()
        if not text:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Empty text")
        if len(text) > xtts_server.TTS_MAX_TEXT_LENGTH:
            await context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                f"Text too long: {len(text)} chars (max {xtts_server.TTS_MAX_TEXT_LENGTH})",
            )

        start = time.monotonic()
        async with xtts_server._lock:
            await asyncio.get_event_loop().run_in_executor(None, xtts_server._load_tts)

        speed = request.speed if request.speed > 0 else 1.0
        language = request.language or ""
        voice = request.voice or None

        wav_data = await asyncio.get_event_loop().run_in_executor(
            None, xtts_server._synthesize_text, text, speed, language, voice,
        )
        elapsed = time.monotonic() - start
        logger.info("[TTS] gRPC Speak %d chars → %d bytes in %.2fs", len(text), len(wav_data), elapsed)
        return speak_pb2.SpeakResponse(
            wav=wav_data,
            duration_ms=int(elapsed * 1000),
            text_length=len(text),
        )

    async def SpeakStream(
        self,
        request: speak_pb2.SpeakRequest,
        context: grpc.aio.ServicerContext,
    ):
        from app import xtts_server

        text = (request.text or "").strip()
        if not text:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Empty text")
        if len(text) > xtts_server.TTS_MAX_TEXT_LENGTH:
            await context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                f"Text too long: {len(text)} chars (max {xtts_server.TTS_MAX_TEXT_LENGTH})",
            )

        async with xtts_server._lock:
            await asyncio.get_event_loop().run_in_executor(None, xtts_server._load_tts)

        speed = request.speed if request.speed > 0 else 1.0
        language = request.language or ""
        voice = request.voice or None

        # _stream_inference runs in a worker thread and pushes
        # ("header"|"pcm"|"done"|"error", data) tuples into the queue.
        chunk_q: queue.Queue = queue.Queue()
        thread = threading.Thread(
            target=xtts_server._stream_inference,
            args=(text, speed, language, chunk_q, voice),
            daemon=True,
        )
        thread.start()

        loop = asyncio.get_event_loop()
        start = time.monotonic()
        total_chunks = 0

        while True:
            try:
                msg_type, data = await loop.run_in_executor(None, chunk_q.get, True, 60.0)
            except Exception:
                await context.abort(grpc.StatusCode.DEADLINE_EXCEEDED, "Timeout waiting for audio chunk")
                return

            if msg_type == "header":
                # Header (sample_rate) is implicit — clients infer it from the WAV
                # shape Kotlin emits over Compose playback. Skipped in the stream.
                continue

            if msg_type == "pcm":
                total_chunks += 1
                yield speak_pb2.AudioChunk(data=data, is_last=False)
                continue

            if msg_type == "done":
                # Final sentinel with empty payload + is_last=True.
                elapsed = time.monotonic() - start
                logger.info(
                    "[TTS] gRPC SpeakStream %d chars → %d PCM chunks in %.2fs",
                    len(text), total_chunks, elapsed,
                )
                yield speak_pb2.AudioChunk(data=b"", is_last=True)
                return

            if msg_type == "error":
                await context.abort(grpc.StatusCode.INTERNAL, str(data))
                return


async def start_grpc_server(port: int = 5501) -> grpc.aio.Server:
    """Start the gRPC TTS server on `port` and return the handle for cleanup."""
    from jervis_contracts.grpc_options import build_server_options

    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=build_server_options(max_msg_bytes=64 * 1024 * 1024),
    )
    speak_pb2_grpc.add_TtsServiceServicer_to_server(TtsServicer(), server)

    service_names = (
        speak_pb2.DESCRIPTOR.services_by_name["TtsService"].full_name,
        reflection.SERVICE_NAME,
    )
    reflection.enable_server_reflection(service_names, server)

    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info("gRPC TTS service listening on :%d", port)
    return server
