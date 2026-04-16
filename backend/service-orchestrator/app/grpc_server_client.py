"""Cached gRPC stubs for the Kotlin server's pod-to-pod services.

Every Python → Kotlin server contract migrates here one-by-one. Each
stub is cached behind a grpc.aio.insecure_channel; the module is
import-safe (no network on import) and stubs are lazily constructed on
first use.
"""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.server import cache_pb2_grpc

logger = logging.getLogger(__name__)

_channel: Optional[grpc.aio.Channel] = None
_cache_stub: Optional[cache_pb2_grpc.ServerCacheServiceStub] = None


def _kotlin_server_grpc_target() -> str:
    # Host from `kotlin_server_url`, port 5501 (gRPC). Ktor kRPC stays on
    # whatever port the URL says (currently 5500).
    url = settings.kotlin_server_url.rstrip("/")
    if "://" in url:
        url = url.split("://", 1)[1]
    host = url.split("/")[0].split(":")[0]
    return f"{host}:5501"


def _get_channel() -> grpc.aio.Channel:
    global _channel
    if _channel is None:
        target = _kotlin_server_grpc_target()
        _channel = grpc.aio.insecure_channel(target)
        logger.debug("kotlin-server gRPC channel opened to %s", target)
    return _channel


def server_cache_stub() -> cache_pb2_grpc.ServerCacheServiceStub:
    global _cache_stub
    if _cache_stub is None:
        _cache_stub = cache_pb2_grpc.ServerCacheServiceStub(_get_channel())
    return _cache_stub
