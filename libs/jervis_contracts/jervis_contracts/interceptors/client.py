"""Client-side gRPC interceptors for Jervis services.

Parity with `com.jervis.contracts.interceptors.ClientContextInterceptor`.
The interceptor cannot mutate the request payload itself (gRPC serializes
before the interceptor runs), so populating RequestContext fields is the
caller's responsibility — use the `prepare_context()` helper from this
module before building the request.

What the interceptor does:
    - attaches a gRPC deadline derived from RequestContext.deadline_iso
      (via the separately-exported `deadline_from_iso` helper; consumers
      call `stub.<rpc>(req, timeout=deadline_from_iso(req.ctx.deadline_iso))`).
    - logs request_id at DEBUG so every outbound RPC is traceable.
"""

from __future__ import annotations

import logging
import time
import uuid
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable, Optional

import grpc
from grpc.aio import (
    ClientCallDetails,
    UnaryUnaryCall,
    UnaryUnaryClientInterceptor,
    UnaryStreamCall,
    UnaryStreamClientInterceptor,
)

log = logging.getLogger(__name__)


def deadline_from_iso(iso: str | None) -> float | None:
    """Convert an RFC3339 UTC string into a gRPC `timeout` (seconds from now).

    Returns `None` if `iso` is empty or unparseable — callers should treat
    that as "no deadline" and rely on server-side fallback logic.
    """
    if not iso:
        return None
    try:
        instant = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    except ValueError:
        return None
    if instant.tzinfo is None:
        instant = instant.replace(tzinfo=timezone.utc)
    remaining = (instant - datetime.now(timezone.utc)).total_seconds()
    if remaining <= 0:
        return 0.0
    return remaining


def prepare_context(ctx: Any) -> None:
    """Mutate `ctx` in-place: fill request_id/issued_at_unix_ms if empty.

    `ctx` is a `jervis.common.RequestContext` message; we use duck typing
    so this file doesn't import the generated module (which may not be
    generated yet at bootstrap).
    """
    if not getattr(ctx, "request_id", ""):
        ctx.request_id = str(uuid.uuid4())
    if not getattr(ctx, "issued_at_unix_ms", 0):
        ctx.issued_at_unix_ms = int(time.time() * 1000)


class ClientContextInterceptor(UnaryUnaryClientInterceptor):
    async def intercept_unary_unary(
        self,
        continuation: Callable[[ClientCallDetails, Any], Awaitable[UnaryUnaryCall]],
        client_call_details: ClientCallDetails,
        request: Any,
    ) -> UnaryUnaryCall:
        request_id = _peek_request_id(request)
        log.debug("grpc-outbound %s request_id=%s", client_call_details.method, request_id)
        return await continuation(client_call_details, request)


class StreamingClientContextInterceptor(UnaryStreamClientInterceptor):
    async def intercept_unary_stream(
        self,
        continuation: Callable[[ClientCallDetails, Any], Awaitable[UnaryStreamCall]],
        client_call_details: ClientCallDetails,
        request: Any,
    ) -> UnaryStreamCall:
        request_id = _peek_request_id(request)
        log.debug("grpc-outbound-stream %s request_id=%s", client_call_details.method, request_id)
        return await continuation(client_call_details, request)


def _peek_request_id(request: Any) -> Optional[str]:
    ctx = getattr(request, "ctx", None)
    if ctx is None:
        return None
    return getattr(ctx, "request_id", None) or None
