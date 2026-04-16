"""Client + server gRPC interceptors shared across every Jervis service.

Mirrors the semantics of `com.jervis.contracts.interceptors.*` in Kotlin:
populates RequestContext.request_id / issued_at_unix_ms when empty, converts
RequestContext.deadline_iso to a gRPC deadline, validates scope.client_id
server-side for non-health RPCs.
"""

from .client import (
    ClientContextInterceptor,
    StreamingClientContextInterceptor,
    deadline_from_iso,
    prepare_context,
)
from .server import ServerContextInterceptor, DEFAULT_UNAUTHENTICATED

__all__ = [
    "ClientContextInterceptor",
    "StreamingClientContextInterceptor",
    "ServerContextInterceptor",
    "DEFAULT_UNAUTHENTICATED",
    "deadline_from_iso",
    "prepare_context",
]
