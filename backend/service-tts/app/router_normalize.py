"""Stream-normalize TTS text via the Jervis router.

Design goal: the caller hands us raw text + language; we ask an LLM to
rewrite the text so XTTS reads it naturally (acronyms expanded
phonetically, numbers spelled out, markdown stripped) and we stream the
LLM output *per sentence* — every time a newline arrives, the current
sentence is complete and can be handed to XTTS for immediate audio
synthesis. This overlaps LLM thinking time with GPU inference, so the
first PCM chunk arrives ~0.3 s after the first complete sentence rather
than after the whole text is normalized.

Normalization is an XTTS-local concern (only XTTS cares how text is
pronounced), so it lives in this service rather than in the Kotlin
server. Kotlin just forwards (text, language) and tunnels the PCM back.

On router failure / timeout we fall back to yielding the raw text split
on sentence terminators — XTTS still produces audio, just without the
prettier pronunciation.
"""

from __future__ import annotations

import asyncio
import logging
import os
import re
import time
import uuid
from typing import AsyncIterator, Optional

import grpc.aio

from jervis.common import enums_pb2, types_pb2
from jervis.router import inference_pb2, inference_pb2_grpc

logger = logging.getLogger("tts.router_normalize")

# Router gRPC endpoint. Same host as the rest of the pod-to-pod contracts.
ROUTER_GRPC_HOST = os.getenv("ROUTER_GRPC_HOST", "jervis-ollama-router.jervis.svc.cluster.local")
ROUTER_GRPC_PORT = int(os.getenv("ROUTER_GRPC_PORT", "5501"))

# Hard ceilings — user is waiting for audio, a slow LLM falls back to
# raw sentence split so playback is never blocked.
NORMALIZE_DEADLINE_S = 30.0
FIRST_TOKEN_DEADLINE_S = 8.0

_GRPC_MAX_MSG_BYTES = 16 * 1024 * 1024

_channel: Optional[grpc.aio.Channel] = None
_stub: Optional[inference_pb2_grpc.RouterInferenceServiceStub] = None

_FALLBACK_SENTENCE_SPLIT = re.compile(r"(?<=[.!?…])\s+")


def _get_stub() -> inference_pb2_grpc.RouterInferenceServiceStub:
    global _channel, _stub
    if _stub is None:
        target = f"{ROUTER_GRPC_HOST}:{ROUTER_GRPC_PORT}"
        _channel = grpc.aio.insecure_channel(
            target,
            options=[
                ("grpc.max_send_message_length", _GRPC_MAX_MSG_BYTES),
                ("grpc.max_receive_message_length", _GRPC_MAX_MSG_BYTES),
                ("grpc.keepalive_time_ms", 30_000),
                ("grpc.keepalive_timeout_ms", 10_000),
                ("grpc.keepalive_permit_without_calls", 1),
            ],
        )
        _stub = inference_pb2_grpc.RouterInferenceServiceStub(_channel)
        logger.info("RouterInferenceService gRPC channel opened to %s", target)
    return _stub


def _system_prompt(language: str) -> str:
    """System prompt for TTS normalization. One clear rule set, no extras.

    The model's only job is to rewrite the input so a text-to-speech
    engine pronounces it naturally. It MUST preserve the content — no
    summarizing, no paraphrasing. It MUST emit exactly one sentence per
    line so the downstream consumer can stream sentence-by-sentence to
    XTTS.
    """
    if language.lower().startswith("en"):
        return (
            "You rewrite text for text-to-speech. Return ONLY the rewritten text.\n"
            "Rules:\n"
            "- Keep every factual statement; do not summarize, omit, or reorder.\n"
            "- Expand acronyms phonetically (API -> ay pee eye, HTTP -> aitch tee tee pee,\n"
            "  JSON -> jay son, URL -> you are ell, SQL -> ess queue ell, AI -> ay eye,\n"
            "  GPU -> gee pee you, CPU -> see pee you, PDF -> pee dee eff).\n"
            "- Spell long digit/hex runs as the word 'identifier' or omit them.\n"
            "- Strip markdown (**bold**, `code`, # headings, list bullets, links).\n"
            "- Output one SENTENCE PER LINE — newline separates sentences. No blank lines.\n"
        )
    return (
        "Přepisuješ text pro hlasové čtení (TTS). Vrať POUZE přepsaný text.\n"
        "Pravidla:\n"
        "- Zachovej každou faktickou informaci; nic nezkracuj, nevynechávej, nepřerovnávej.\n"
        "- Zkratky rozepiš foneticky podle jazyka:\n"
        "  * Anglické IT zkratky → anglicky (API → ej-pí-aj, HTTP → ejč-tí-tí-pí,\n"
        "    JSON → džej-sn, URL → jú-ár-el, SQL → es-kvé-el, AI → ej-aj,\n"
        "    GPU → dží-pí-jú, CPU → sí-pí-jú, PDF → pí-dý-ef).\n"
        "  * České zkratky / interní kódy → foneticky česky (SBO → es-bé-ó,\n"
        "    BMS → bé-em-es, VD → vé-dé, ČR → čé-er, DPH → dé-pé-há, IČO → í-čé-ó).\n"
        "- Čísla převeď slovně (69 → šedesát devět, 2026 → dva tisíce dvacet šest).\n"
        "- Dlouhé hex/ID řetězce (6+ znaků) nahraď slovem 'identifikátor' nebo vynech.\n"
        "- Odstraň markdown (**tučně**, `kód`, # nadpisy, odrážky, odkazy).\n"
        "- Výstup: JEDNA VĚTA NA ŘÁDEK — nový řádek odděluje věty. Žádné prázdné řádky.\n"
    )


def _fallback_sentences(text: str) -> list[str]:
    parts = [s.strip() for s in _FALLBACK_SENTENCE_SPLIT.split(text) if s.strip()]
    return parts if parts else [text.strip()]


def _build_ctx(client_id: str, project_id: str) -> types_pb2.RequestContext:
    scope = types_pb2.Scope(client_id=client_id or "", project_id=project_id or "")
    return types_pb2.RequestContext(
        scope=scope,
        capability=enums_pb2.CAPABILITY_CHAT,
        intent="tts_normalize",
        request_id=str(uuid.uuid4()),
        issued_at_unix_ms=int(time.time() * 1000),
    )


async def stream_sentences(
    text: str,
    language: str,
    client_id: str = "",
    project_id: str = "",
) -> AsyncIterator[str]:
    """Yield complete, normalized sentences as they arrive from the router.

    Opens a streaming RouterInferenceService.Chat call, accumulates
    `content_delta` into a line buffer and yields every time a newline
    lands. On router failure or timeout the fallback is plain sentence
    split of the raw text — playback must never stall.
    """
    stub = _get_stub()

    request = inference_pb2.ChatRequest(
        ctx=_build_ctx(client_id, project_id),
        messages=[
            inference_pb2.ChatMessage(role="system", content=_system_prompt(language)),
            inference_pb2.ChatMessage(role="user", content=text),
        ],
        options=inference_pb2.ChatOptions(temperature=0.2),
    )

    started = time.monotonic()
    got_first_token = False

    async def _raw_chunks():
        async for chunk in stub.Chat(request):
            yield chunk

    chunks_iter = _raw_chunks()
    buffer = ""

    try:
        while True:
            # First-token deadline protects against router queue saturation.
            # After the first token we fall back to the overall deadline.
            remaining = NORMALIZE_DEADLINE_S - (time.monotonic() - started)
            deadline = FIRST_TOKEN_DEADLINE_S if not got_first_token else remaining
            if deadline <= 0:
                logger.warning("TTS_NORMALIZE overall deadline exceeded")
                break
            try:
                chunk = await asyncio.wait_for(chunks_iter.__anext__(), timeout=deadline)
            except StopAsyncIteration:
                break
            except asyncio.TimeoutError:
                logger.warning(
                    "TTS_NORMALIZE timed out (%s, got_first=%s)",
                    "first-token" if not got_first_token else "inter-chunk",
                    got_first_token,
                )
                break

            got_first_token = True
            if chunk.content_delta:
                buffer += chunk.content_delta
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()
                    if line:
                        yield line
            if chunk.done:
                tail = buffer.strip()
                buffer = ""
                if tail:
                    yield tail
                return
    except grpc.aio.AioRpcError as e:
        logger.warning("TTS_NORMALIZE router RPC error: code=%s detail=%s", e.code(), e.details())
        buffer = ""
    except Exception as e:  # noqa: BLE001 — fallback path
        logger.warning("TTS_NORMALIZE unexpected error: %s", e)
        buffer = ""

    # If we exited early but have partial buffer, flush it.
    if buffer.strip():
        yield buffer.strip()
        return

    # Nothing useful came back → raw sentence split so audio still plays.
    if not got_first_token:
        logger.info("TTS_NORMALIZE fallback: router silent, using raw sentence split")
        for s in _fallback_sentences(text):
            yield s
