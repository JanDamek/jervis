"""Thin helpers around the router's gRPC admin surface.

All model routing happens inside the router: callers invoke
`llm_provider.completion(capability=..., max_tier=..., client_id=...,
extra_headers={"X-Intent": ...})` and the router picks local vs cloud +
the concrete model from headers.

This module holds best-effort feedback from the orchestrator into the
router's model-reputation tracker, plus one cheap read for context-budget
math. Every call goes over gRPC `jervis.router.RouterAdminService` —
there is no REST fallback.
"""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.router import admin_pb2, admin_pb2_grpc
from jervis.common import enums_pb2, types_pb2
from jervis_contracts.interceptors import prepare_context

logger = logging.getLogger(__name__)

_TIER_CAP_FROM_STR = {
    "NONE": enums_pb2.TIER_CAP_NONE,
    "T1": enums_pb2.TIER_CAP_T1,
    "T2": enums_pb2.TIER_CAP_T2,
}

_channel: Optional[grpc.aio.Channel] = None
_stub: Optional[admin_pb2_grpc.RouterAdminServiceStub] = None


def _router_grpc_target() -> str:
    # service DNS name inside the cluster; port 5501 is gRPC (FastAPI
    # :11430 only exposes the transparent Ollama passthrough).
    explicit = getattr(settings, "ollama_router_grpc", None)
    if explicit:
        return explicit
    host = settings.ollama_url.rstrip("/")
    for strip in ("/v1", "/api", "/api/"):
        if host.endswith(strip):
            host = host[: -len(strip)]
    # strip scheme + port
    if "://" in host:
        host = host.split("://", 1)[1]
    host = host.split("/")[0]
    host = host.split(":")[0]
    return f"{host}:5501"


def _get_stub() -> admin_pb2_grpc.RouterAdminServiceStub:
    global _channel, _stub
    if _stub is None:
        target = _router_grpc_target()
        _channel = grpc.aio.insecure_channel(target)
        _stub = admin_pb2_grpc.RouterAdminServiceStub(_channel)
        logger.debug("router-admin gRPC channel opened to %s", target)
    return _stub


def _ctx() -> types_pb2.RequestContext:
    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    return ctx


async def report_model_error(model_id: str, error_message: str = "") -> dict:
    try:
        resp = await _get_stub().ReportModelError(
            admin_pb2.ReportModelErrorRequest(
                ctx=_ctx(),
                model_id=model_id,
                error_message=(error_message or "")[:500],
            ),
            timeout=3.0,
        )
        return {
            "model_id": resp.model_id,
            "disabled": resp.disabled,
            "error_count": resp.error_count,
            "just_disabled": resp.just_disabled,
        }
    except Exception as e:
        logger.debug("report_model_error best-effort: %s", e)
        return {}


async def report_model_success(
    model_id: str,
    duration_s: float = 0.0,
    input_tokens: int = 0,
    output_tokens: int = 0,
) -> None:
    try:
        await _get_stub().ReportModelSuccess(
            admin_pb2.ReportModelSuccessRequest(
                ctx=_ctx(),
                model_id=model_id,
                duration_s=float(duration_s) if duration_s > 0 else 0.0,
                input_tokens=int(input_tokens) if input_tokens > 0 else 0,
                output_tokens=int(output_tokens) if output_tokens > 0 else 0,
            ),
            timeout=3.0,
        )
    except Exception as e:
        logger.debug("report_model_success best-effort: %s", e)


async def get_max_context_tokens(max_tier: str = "NONE") -> int:
    try:
        resp = await _get_stub().GetMaxContext(
            admin_pb2.MaxContextRequest(
                ctx=_ctx(),
                max_tier=_TIER_CAP_FROM_STR.get(max_tier.upper(), enums_pb2.TIER_CAP_NONE),
            ),
            timeout=3.0,
        )
        return int(resp.max_context_tokens) or 48_000
    except Exception as e:
        logger.debug("get_max_context_tokens best-effort: %s", e)
        return 48_000
