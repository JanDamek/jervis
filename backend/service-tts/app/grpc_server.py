"""gRPC server for `service-tts`.

Hosts TtsService {Speak, SpeakStream} on :5501. FastAPI /tts + /tts/stream
routes are removed; every consumer dials gRPC.
"""

from __future__ import annotations

import asyncio
import logging
import re
import time

import grpc
from grpc_reflection.v1alpha import reflection

from jervis.tts import speak_pb2, speak_pb2_grpc
from jervis_contracts.interceptors import ServerContextInterceptor

logger = logging.getLogger("tts.grpc")


class TtsServicer(speak_pb2_grpc.TtsServiceServicer):
    """TtsService implementation — Piper-backed synthesis.

    Both RPCs share the voice loader + _synthesize_text helper from the
    existing FastAPI module so behavior stays identical to the REST path.
    """

    async def Speak(
        self,
        request: speak_pb2.SpeakRequest,
        context: grpc.aio.ServicerContext,
    ) -> speak_pb2.SpeakResponse:
        from app import tts_server

        text = (request.text or "").strip()
        if not text:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Empty text")
        if len(text) > tts_server.TTS_MAX_TEXT_LENGTH:
            await context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                f"Text too long: {len(text)} chars (max {tts_server.TTS_MAX_TEXT_LENGTH})",
            )

        start = time.monotonic()
        async with tts_server._lock:
            voice = await asyncio.get_event_loop().run_in_executor(
                None, tts_server._load_voice,
            )
        speed = request.speed if request.speed > 0 else 1.0
        wav_data = await asyncio.get_event_loop().run_in_executor(
            None, tts_server._synthesize_text, voice, text, speed,
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
        from app import tts_server

        text = (request.text or "").strip()
        if not text:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Empty text")
        if len(text) > tts_server.TTS_MAX_TEXT_LENGTH:
            await context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                f"Text too long: {len(text)} chars (max {tts_server.TTS_MAX_TEXT_LENGTH})",
            )

        async with tts_server._lock:
            voice = await asyncio.get_event_loop().run_in_executor(
                None, tts_server._load_voice,
            )
        speed = request.speed if request.speed > 0 else 1.0

        sentences = [s for s in re.split(r"(?<=[.!?])\s+", text) if s.strip()]
        loop = asyncio.get_event_loop()
        total = len(sentences)
        for idx, sentence in enumerate(sentences):
            chunk = await loop.run_in_executor(
                None, tts_server._synthesize_text, voice, sentence, speed,
            )
            yield speak_pb2.AudioChunk(data=chunk, is_last=(idx == total - 1))


async def start_grpc_server(port: int = 5501) -> grpc.aio.Server:
    """Start the gRPC TTS server on `port` and return the handle for cleanup."""
    max_msg_bytes = 64 * 1024 * 1024
    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=[
            ("grpc.max_receive_message_length", max_msg_bytes),
            ("grpc.max_send_message_length", max_msg_bytes),
        ],
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
