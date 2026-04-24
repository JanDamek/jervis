"""gRPC server for `service-tts` (XTTS v2 backend).

Hosts TtsService {Speak, SpeakStream} on :5501. Runs on the VD GPU VM
alongside Whisper — not inside K8s, per `feedback-audio-services-on-vd`.

Text normalization is done on-box: `normalizer.py` applies rules loaded
from the Kotlin server's `ttsRules` collection (acronym / strip /
replace) in deterministic CPU order — no LLM, no cloud, no router hop.
SSOT: `docs/tts-normalization.md`.
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

    `Speak` invokes the blocking `_synthesize_text` helper on a thread;
    the response carries a full WAV blob. `SpeakStream` applies the
    rule-based normalizer and pumps normalized sentences through
    XTTS's streaming inference for gapless playback.
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
        from app import normalizer, rules_client, xtts_server

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
        language = request.language or xtts_server.TTS_LANGUAGE
        voice = request.voice or None
        client_id = request.ctx.scope.client_id if request.HasField("ctx") else ""
        project_id = request.ctx.scope.project_id if request.HasField("ctx") else ""

        # No router coordination needed any more. XTTS + bge-m3 fit
        # comfortably in the 24 GB P40 VRAM together (~5 GB combined),
        # and normalization runs on CPU here — nothing on p40-2
        # competes for VRAM during synthesis, so the old TtsNotify /
        # TtsDone handshake was removed with the LLM normalize path.
        start = time.monotonic()
        logger.info(
            "[TTS] SpeakStream START textLen=%d lang=%s client=%s project=%s",
            len(text), language, client_id or "-", project_id or "-",
        )

        # 1. Fetch rules, normalize on CPU. Deterministic ~1ms.
        rules = await rules_client.fetch_rules(
            language=language, client_id=client_id, project_id=project_id,
        )
        lines = normalizer.normalize(text, rules=rules, language=language)
        logger.info(
            "[TTS] NORMALIZE rules=%d input_chars=%d → lines=%d t=%.3fs",
            len(rules), len(text), len(lines), time.monotonic() - start,
        )

        if not lines:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Normalized to empty — nothing to say")
            return

        # 2. Feed normalized lines into the XTTS worker and stream PCM
        #    back to the caller.
        sentence_q: queue.Queue = queue.Queue()
        chunk_q: queue.Queue = queue.Queue()
        thread = threading.Thread(
            target=xtts_server._stream_inference_from_sentence_queue,
            args=(sentence_q, speed, language, chunk_q, voice),
            daemon=True,
        )
        thread.start()

        for line in lines:
            # Worker expects `[CS]` / `[EN]` prefix so it can switch the
            # phonemizer per sentence. normalizer.NormalizedLine.lang is
            # already lowercased; XTTS worker accepts both cases.
            sentence_q.put(f"[{line.lang.upper()}] {line.text}")
        sentence_q.put(None)  # end-of-stream sentinel for the worker

        loop = asyncio.get_event_loop()
        total_chunks = 0
        while True:
            try:
                msg_type, data = await loop.run_in_executor(None, chunk_q.get, True, 120.0)
            except Exception:
                await context.abort(grpc.StatusCode.DEADLINE_EXCEEDED, "Timeout waiting for audio chunk")
                return

            if msg_type == "header":
                continue  # sample rate is a fixed 24 kHz contract

            if msg_type == "pcm":
                total_chunks += 1
                if total_chunks == 1:
                    logger.info(
                        "[TTS] FIRST_PCM_YIELD t=%.2fs bytes=%d",
                        time.monotonic() - start, len(data),
                    )
                yield speak_pb2.AudioChunk(data=data, is_last=False)
                continue

            if msg_type == "done":
                elapsed = time.monotonic() - start
                logger.info(
                    "[TTS] SpeakStream DONE %d chars → %d PCM chunks in %.2fs (lang=%s)",
                    len(text), total_chunks, elapsed, language,
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
