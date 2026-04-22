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
import datetime
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
# First-token deadline has to cover the case where a kb-extract /
# qualifier job is mid-inference on the chat GPU: CASCADE priority
# jumps the queue but can't preempt a running 30B request, so we may
# wait a minute before our first token lands. UI watchdog (kRPC) is
# 90 s, which leaves enough budget for normalize + synth even at
# worst case.
NORMALIZE_DEADLINE_S = 120.0
FIRST_TOKEN_DEADLINE_S = 60.0

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
            "- Expand acronyms phonetically, case-insensitive: API / api / Api all\n"
            "  become 'ay pee eye'. HTTP/http → 'aitch tee tee pee'. JSON/json →\n"
            "  'jay son'. URL → 'you are ell'. SQL → 'ess queue ell'. AI → 'ay eye'.\n"
            "  GPU / CPU / PDF → 'gee pee you', 'see pee you', 'pee dee eff'.\n"
            "- Any 2-4 letter uppercase OR lowercase sequence that is not a real\n"
            "  word → spell it letter by letter in English.\n"
            "- Short numbers as words (69 → sixty nine).\n"
            "- Hex / UUID / long alphanumeric IDs (6+ chars) MUST NOT be read aloud.\n"
            "  Drop or replace with 'by identifier'. URLs/paths/email/hashes →\n"
            "  descriptor ('link', 'file', 'email'). Strip markdown.\n"
            "- Per-sentence language tagging:\n"
            "  Prefix every output line with [CS] or [EN]. Use [EN] for lines that\n"
            "  are an English technical term, acronym, product/class name, or\n"
            "  English sentence. If a mixed sentence has an English fragment,\n"
            "  SPLIT it into multiple lines so the English fragment gets its own\n"
            "  [EN] line. Example:\n"
            "    Input:  'The TaskTypeEnum value is NEW.'\n"
            "    Output: '[EN] The TaskTypeEnum value is NEW.'\n"
            "- Output one SENTENCE PER LINE. No blank lines.\n"
        "- HARD LIMIT: each line must stay under 180 characters. Long\n"
        "  compound sentences MUST be split into multiple shorter lines —\n"
        "  the TTS engine refuses anything longer.\n"
        )
    return (
        "Přepisuješ text pro hlasové čtení (TTS). Vrať POUZE přepsaný text.\n"
        "Pravidla:\n"
        "- Zachovej každou faktickou informaci; nic nezkracuj, nevynechávej, nepřerovnávej.\n"
        "- Zkratky rozepiš foneticky, bez ohledu na velikost písmen: BMS / bms /\n"
        "  Bms → 'bé-em-es'. SBO / sbo → 'es-bé-ó'. Pravidlo platí pro jakoukoli\n"
        "  sekvenci 2-4 písmen, která není reálné slovo.\n"
        "  * Anglické IT zkratky → anglicky (API → ej-pí-aj, HTTP → ejč-tí-tí-pí,\n"
        "    JSON → džej-sn, URL → jú-ár-el, SQL → es-kvé-el, AI → ej-aj,\n"
        "    GPU → dží-pí-jú, CPU → sí-pí-jú, PDF → pí-dý-ef).\n"
        "  * České zkratky / interní kódy → foneticky česky (SBO → es-bé-ó,\n"
        "    BMS → bé-em-es, VD → vé-dé, ČR → čé-er, DPH → dé-pé-há, IČO → í-čé-ó).\n"
        "- Pokud je lowercase sekvence NEvýslovný shluk (není české slovo), taky\n"
        "  ji rozepiš po písmenkách — anglicky (foo → ef-ou-ou), protože spelling\n"
        "  zní přirozeněji v angličtině.\n"
        "- Krátká čísla slovně (69 → šedesát devět, 2026 → dva tisíce dvacet šest).\n"
        "- Hex / UUID / dlouhé alfanumerické ID (6+ znaků) NEČTI doslova.\n"
        "  Pokud věta dává smysl bez identifikátoru, vynech ho úplně.\n"
        "  Pokud je pro větu nezbytný, nahraď ho slovy 'podle identifikátoru' nebo\n"
        "  'podle ID' — posluchač identifikátor nikdy neuvidí v chatu, takže číst\n"
        "  bloky znaků je zbytečné a rušivé.\n"
        "- Stejně zacházej s URL, cesty souborů, e-mailem, hex kódy barev, hash\n"
        "  řetězci — vynech nebo nahraď popisným slovem ('odkaz', 'soubor', 'e-mail').\n"
        "- Odstraň markdown (**tučně**, `kód`, # nadpisy, odrážky).\n"
        "- Označení jazyka na řádek:\n"
        "  KAŽDÝ výstupní řádek začni prefixem [CS] nebo [EN].\n"
        "  * [EN] použij pro evidentně anglické názvy, technické termíny,\n"
        "    třídy / enum / API názvy, celé anglické věty, nebo samostatné\n"
        "    anglické zkratky (API, HTTP…).\n"
        "  * Pokud česká věta obsahuje anglický fragment (např. název třídy\n"
        "    'TaskTypeEnum'), ROZDĚL ji na víc řádků: českou část jako [CS]\n"
        "    a anglický fragment jako samostatný [EN] řádek.\n"
        "    Vstup:  'Stav úlohy TaskTypeEnum se změnil na NEW.'\n"
        "    Výstup: '[CS] Stav úlohy'\n"
        "            '[EN] TaskTypeEnum'\n"
        "            '[CS] se změnil na'\n"
        "            '[EN] NEW.'\n"
        "  * Pro čistě české věty použij [CS].\n"
        "- Výstup: JEDNA VĚTA (nebo fragment) NA ŘÁDEK s prefixem. Žádné prázdné řádky.\n"
        "- TVRDÝ LIMIT: každý řádek kratší než 180 znaků. Dlouhá souvětí MUSÍŠ\n"
        "  rozlámat na víc kratších řádků — TTS engine delší vstup odmítne.\n"
    )


def _fallback_sentences(text: str) -> list[str]:
    parts = [s.strip() for s in _FALLBACK_SENTENCE_SPLIT.split(text) if s.strip()]
    return parts if parts else [text.strip()]


def _build_ctx(client_id: str, project_id: str, max_tier: int) -> types_pb2.RequestContext:
    scope = types_pb2.Scope(client_id=client_id or "", project_id=project_id or "")
    # Tight deadline + CRITICAL priority: the user is already waiting for
    # audio, this request MUST jump ahead of background kb-extract jobs.
    # Router `_resolve_priority` currently reads only explicit priority
    # (deadline_iso → priority routing is on the roadmap), so we set
    # priority explicitly; deadline stays informational for downstream.
    deadline_iso = (datetime.datetime.utcnow() + datetime.timedelta(seconds=15)).isoformat() + "Z"
    return types_pb2.RequestContext(
        scope=scope,
        capability=enums_pb2.CAPABILITY_CHAT,
        priority=enums_pb2.PRIORITY_CRITICAL,
        max_tier=max_tier or enums_pb2.TIER_CAP_UNSPECIFIED,
        intent="tts_normalize",
        deadline_iso=deadline_iso,
        request_id=str(uuid.uuid4()),
        issued_at_unix_ms=int(time.time() * 1000),
    )


async def stream_sentences(
    text: str,
    language: str,
    client_id: str = "",
    project_id: str = "",
    max_tier: int = 0,
) -> AsyncIterator[str]:
    """Yield complete, normalized sentences as they arrive from the router.

    Opens a streaming RouterInferenceService.Chat call, accumulates
    `content_delta` into a line buffer and yields every time a newline
    lands. On router failure or timeout the fallback is plain sentence
    split of the raw text — playback must never stall.
    """
    stub = _get_stub()

    request = inference_pb2.ChatRequest(
        ctx=_build_ctx(client_id, project_id, max_tier),
        # No model_hint — let the router pick the default chat model on
        # the GPU that does NOT host XTTS (p40-1 with qwen3-coder-tool:30b).
        # qwen3:14b would technically be faster, but it lives on p40-2 next
        # to XTTS and would fight for compute. 30B on p40-1 is slower per
        # token but runs without contention, and TTS normalization is
        # short so the total wait is tolerable.
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
