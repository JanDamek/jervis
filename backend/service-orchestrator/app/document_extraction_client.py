"""gRPC client for the Python document-extraction microservice."""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.common import types_pb2
from jervis.document_extraction import extract_pb2, extract_pb2_grpc

logger = logging.getLogger("orchestrator.document_extraction")

_channel: Optional[grpc.aio.Channel] = None
_stub: Optional[extract_pb2_grpc.DocumentExtractionServiceStub] = None


def _target() -> str:
    url = (
        getattr(settings, "document_extraction_url", None)
        or "http://jervis-document-extraction:8080"
    )
    url = url.rstrip("/")
    if "://" in url:
        url = url.split("://", 1)[1]
    host = url.split("/")[0].split(":")[0]
    return f"{host}:5501"


def _get_stub() -> extract_pb2_grpc.DocumentExtractionServiceStub:
    global _channel, _stub
    if _stub is None:
        _channel = grpc.aio.insecure_channel(
            _target(),
            options=[
                ("grpc.max_receive_message_length", 64 * 1024 * 1024),
                ("grpc.max_send_message_length", 64 * 1024 * 1024),
            ],
        )
        _stub = extract_pb2_grpc.DocumentExtractionServiceStub(_channel)
    return _stub


def _ctx() -> types_pb2.RequestContext:
    return types_pb2.RequestContext(
        request_id="",
        trace={"caller": "service-orchestrator"},
    )


async def document_extraction_extract(
    content: bytes,
    filename: str,
    mime_type: str = "",
    max_tier: str = "NONE",
    timeout: float = 120.0,
) -> dict:
    stub = _get_stub()
    resp = await stub.Extract(
        extract_pb2.ExtractRequest(
            ctx=_ctx(),
            content=bytes(content),
            filename=filename,
            mime_type=mime_type,
            max_tier=max_tier,
        ),
        timeout=timeout,
    )
    return {
        "text": resp.text,
        "method": resp.method,
        "metadata": dict(resp.metadata),
        "pages": [
            {"page_number": p.page_number, "text": p.text}
            for p in resp.pages
        ],
    }
