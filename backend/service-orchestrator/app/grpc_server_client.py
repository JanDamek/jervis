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
from jervis.server import (
    cache_pb2_grpc,
    chat_context_pb2_grpc,
    filter_rules_pb2_grpc,
    guidelines_pb2_grpc,
    urgency_pb2_grpc,
)

logger = logging.getLogger(__name__)

_channel: Optional[grpc.aio.Channel] = None
_cache_stub: Optional[cache_pb2_grpc.ServerCacheServiceStub] = None
_chat_context_stub: Optional[chat_context_pb2_grpc.ServerChatContextServiceStub] = None
_filter_rules_stub: Optional[filter_rules_pb2_grpc.ServerFilterRulesServiceStub] = None
_guidelines_stub: Optional[guidelines_pb2_grpc.ServerGuidelinesServiceStub] = None
_urgency_stub: Optional[urgency_pb2_grpc.ServerUrgencyServiceStub] = None


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


def server_guidelines_stub() -> guidelines_pb2_grpc.ServerGuidelinesServiceStub:
    global _guidelines_stub
    if _guidelines_stub is None:
        _guidelines_stub = guidelines_pb2_grpc.ServerGuidelinesServiceStub(_get_channel())
    return _guidelines_stub


def server_filter_rules_stub() -> filter_rules_pb2_grpc.ServerFilterRulesServiceStub:
    global _filter_rules_stub
    if _filter_rules_stub is None:
        _filter_rules_stub = filter_rules_pb2_grpc.ServerFilterRulesServiceStub(_get_channel())
    return _filter_rules_stub


def server_urgency_stub() -> urgency_pb2_grpc.ServerUrgencyServiceStub:
    global _urgency_stub
    if _urgency_stub is None:
        _urgency_stub = urgency_pb2_grpc.ServerUrgencyServiceStub(_get_channel())
    return _urgency_stub


def server_chat_context_stub() -> chat_context_pb2_grpc.ServerChatContextServiceStub:
    global _chat_context_stub
    if _chat_context_stub is None:
        _chat_context_stub = chat_context_pb2_grpc.ServerChatContextServiceStub(_get_channel())
    return _chat_context_stub
