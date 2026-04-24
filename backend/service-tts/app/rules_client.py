"""gRPC client to fetch TTS normalization rules from the Kotlin server.

Called once per `SpeakStream`. Per-request fetch is intentional — rules
change when the user edits the dictionary over chat, and we want the
next TTS call to see the update immediately (guideline: simple beats
clever, 10-20ms of gRPC doesn't matter vs. 2-5s of XTTS synthesis).
"""

from __future__ import annotations

import logging
import os
from typing import Optional

import grpc.aio

from jervis.common import types_pb2
from jervis.server import tts_rules_pb2, tts_rules_pb2_grpc

logger = logging.getLogger("tts.rules_client")

# Kotlin server exposes this service on its pod-to-pod gRPC port. Defaults
# match the usual K8s naming; deploy_xtts_gpu.sh can override.
SERVER_GRPC_HOST = os.getenv("SERVER_GRPC_HOST", "jervis-server.jervis.svc.cluster.local")
SERVER_GRPC_PORT = int(os.getenv("SERVER_GRPC_PORT", "5501"))

_GRPC_MAX_MSG_BYTES = 16 * 1024 * 1024

_channel: Optional[grpc.aio.Channel] = None
_stub: Optional[tts_rules_pb2_grpc.ServerTtsRulesServiceStub] = None


def _get_stub() -> tts_rules_pb2_grpc.ServerTtsRulesServiceStub:
    global _channel, _stub
    if _stub is None:
        target = f"{SERVER_GRPC_HOST}:{SERVER_GRPC_PORT}"
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
        _stub = tts_rules_pb2_grpc.ServerTtsRulesServiceStub(_channel)
        logger.info("ServerTtsRulesService gRPC channel opened to %s", target)
    return _stub


async def fetch_rules(
    language: str,
    client_id: str = "",
    project_id: str = "",
    timeout_s: float = 5.0,
) -> list[tts_rules_pb2.TtsRule]:
    """Fetch rules applicable to (language, clientId?, projectId?).

    On RPC failure returns an empty list — XTTS will still synthesize
    the raw input (no crash), just without normalization. Failure is
    logged so it's visible in Kibana / journald.
    """
    stub = _get_stub()
    ctx = types_pb2.RequestContext()
    req = tts_rules_pb2.GetForScopeRequest(
        ctx=ctx,
        language=language,
        client_id=client_id,
        project_id=project_id,
    )
    try:
        resp = await stub.GetForScope(req, timeout=timeout_s)
        return list(resp.rules)
    except grpc.aio.AioRpcError as e:
        logger.warning(
            "TTS_RULES_FETCH failed code=%s detail=%s lang=%s client=%s project=%s",
            e.code(), e.details(), language, client_id or "-", project_id or "-",
        )
        return []
