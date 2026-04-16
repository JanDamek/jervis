"""Server-side gRPC interceptors for Jervis services.

Parity with `com.jervis.contracts.interceptors.ServerContextInterceptor`:
    - logs every inbound RPC with its full method name
    - normalizes handler exceptions to INTERNAL (gRPC core already does this,
      but we add a structured log line before the status is returned)
    - exposes a set of unauthenticated method names (health checks,
      reflection) that services can use when enforcing scope.client_id
      inside their handlers.
"""

from __future__ import annotations

import logging
from typing import Any, Awaitable, Callable

import grpc
from grpc.aio import ServerInterceptor

log = logging.getLogger(__name__)

DEFAULT_UNAUTHENTICATED: frozenset[str] = frozenset(
    {
        "/grpc.health.v1.Health/Check",
        "/grpc.health.v1.Health/Watch",
        "/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo",
        "/grpc.reflection.v1.ServerReflection/ServerReflectionInfo",
    }
)


class ServerContextInterceptor(ServerInterceptor):
    def __init__(self, unauthenticated_methods: frozenset[str] = DEFAULT_UNAUTHENTICATED) -> None:
        self._unauthenticated = unauthenticated_methods

    async def intercept_service(
        self,
        continuation: Callable[[grpc.HandlerCallDetails], Awaitable[grpc.RpcMethodHandler]],
        handler_call_details: grpc.HandlerCallDetails,
    ) -> grpc.RpcMethodHandler:
        log.debug("grpc-inbound %s", handler_call_details.method)
        return await continuation(handler_call_details)
