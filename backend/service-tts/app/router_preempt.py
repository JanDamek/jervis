"""Thin RouterAdminService client for TTS GPU preemption.

Mirrors the whisper preempt pattern: before the first synthesis chunk
fires we tell the router "I want the GPU", and when the stream ends we
release. The router cancels in-flight Ollama LLM/VLM, unloads their
models, and blocks its dispatcher until we call TtsDone — so XTTS gets
the whole P40 for the duration of the stream.

User policy: during whisper streaming AND during XTTS generation nothing
else runs on the GPU. Routing logic in the router ORs `check_whisper_busy`
with `check_tts_busy`, so the two audio clients compose correctly when
they happen simultaneously.
"""

from __future__ import annotations

import logging
import os
import time
import uuid
from typing import Optional

import grpc.aio

from jervis.common import types_pb2
from jervis.router import admin_pb2, admin_pb2_grpc

logger = logging.getLogger("tts.router_preempt")

ROUTER_GRPC_HOST = os.getenv("ROUTER_GRPC_HOST", "")
ROUTER_GRPC_PORT = int(os.getenv("ROUTER_GRPC_PORT", "5501"))

_channel: Optional[grpc.aio.Channel] = None
_stub: Optional[admin_pb2_grpc.RouterAdminServiceStub] = None


def _get_stub() -> Optional[admin_pb2_grpc.RouterAdminServiceStub]:
    """Return a cached admin stub, or None if the router address isn't set
    (dev / standalone runs without a K8s router)."""
    global _channel, _stub
    if not ROUTER_GRPC_HOST:
        return None
    if _stub is None:
        target = f"{ROUTER_GRPC_HOST}:{ROUTER_GRPC_PORT}"
        _channel = grpc.aio.insecure_channel(
            target,
            options=[
                ("grpc.keepalive_time_ms", 30_000),
                ("grpc.keepalive_timeout_ms", 10_000),
                ("grpc.keepalive_permit_without_calls", 1),
            ],
        )
        _stub = admin_pb2_grpc.RouterAdminServiceStub(_channel)
        logger.info("RouterAdminService (preempt) channel opened to %s", target)
    return _stub


def _ctx() -> types_pb2.RequestContext:
    return types_pb2.RequestContext(
        request_id=str(uuid.uuid4()),
        issued_at_unix_ms=int(time.time() * 1000),
    )


async def tts_notify(preempt_timeout_s: int = 30) -> bool:
    """Ask the router to clear GPU for XTTS. Returns True if the router
    confirmed all Ollama work is quiesced, False on timeout / error / no
    router configured (in which case we just synthesize anyway — no
    preemption is still better than refusing to speak)."""
    stub = _get_stub()
    if stub is None:
        return False
    try:
        resp = await stub.TtsNotify(admin_pb2.TtsNotifyRequest(
            ctx=_ctx(), preempt_timeout_s=preempt_timeout_s,
        ))
        logger.info(
            "TTS_NOTIFY: granted=%s preempted=%d unloaded=%d",
            resp.granted, resp.preempted_count, resp.unloaded_models,
        )
        return resp.granted
    except Exception as e:  # noqa: BLE001 — preempt is best-effort
        logger.warning("TTS_NOTIFY failed (continuing without preempt): %s", e)
        return False


async def tts_done() -> None:
    """Release the TTS semaphore. Always best-effort — a missed Done
    would auto-expire in the router after 2 h."""
    stub = _get_stub()
    if stub is None:
        return
    try:
        await stub.TtsDone(admin_pb2.TtsDoneRequest(ctx=_ctx()))
        logger.info("TTS_DONE: released")
    except Exception as e:  # noqa: BLE001
        logger.warning("TTS_DONE failed: %s", e)
