"""Document text extraction via jervis-document-extraction microservice.

All extraction logic (VLM, pymupdf, python-docx, openpyxl, etc.) lives in the
dedicated jervis-document-extraction service. This module is a thin gRPC
client that delegates to it over DocumentExtractionService.Extract, keeping
KB service focused on ingestion and retrieval.

The dataclass interfaces (ExtractedDocument, PageContent, ImageDescription) are
preserved for backward compatibility with all callers in knowledge_service.py.
"""

from __future__ import annotations

import logging
import mimetypes
from dataclasses import dataclass, field
from typing import Optional

import grpc.aio

from app.core.config import settings
from jervis.common import types_pb2
from jervis.document_extraction import extract_pb2, extract_pb2_grpc

logger = logging.getLogger(__name__)


@dataclass
class ImageDescription:
    description: str
    ocr_text: str = ""


@dataclass
class PageContent:
    page_number: int
    text: str
    images: list[ImageDescription] = field(default_factory=list)


@dataclass
class ExtractedDocument:
    text: str
    pages: list[PageContent] = field(default_factory=list)
    language: str = ""
    method: str = "direct"  # "pymupdf"|"vlm"|"direct"|"hybrid"|"docx"|"xlsx"|"html"
    metadata: dict = field(default_factory=dict)


_channel: Optional[grpc.aio.Channel] = None
_stub: Optional[extract_pb2_grpc.DocumentExtractionServiceStub] = None


def _target() -> str:
    url = (settings.DOCUMENT_EXTRACTION_URL or "").rstrip("/")
    if not url:
        raise ValueError(
            "DOCUMENT_EXTRACTION_URL not configured. "
            "Set it in ConfigMap (k8s/configmap.yaml) or environment.",
        )
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


class DocumentExtractor:
    """Thin gRPC client to jervis-document-extraction microservice."""

    def __init__(self):
        # Validate config at construction; stub is lazy so no network on init.
        _target()
        self._base_url = settings.DOCUMENT_EXTRACTION_URL

    async def extract(
        self,
        file_bytes: bytes,
        filename: str,
        mime_type: str = "",
        max_tier: str = "NONE",
    ) -> ExtractedDocument:
        """Extract text via gRPC DocumentExtractionService.Extract."""
        if not mime_type:
            mime_type, _ = mimetypes.guess_type(filename)
            mime_type = mime_type or "application/octet-stream"

        logger.info(
            "DocumentExtractor: delegating to %s file=%s mime=%s size=%d",
            self._base_url, filename, mime_type, len(file_bytes),
        )

        stub = _get_stub()
        resp = await stub.Extract(
            extract_pb2.ExtractRequest(
                ctx=types_pb2.RequestContext(trace={"caller": "service-knowledgebase"}),
                content=bytes(file_bytes),
                filename=filename,
                mime_type=mime_type,
                max_tier=max_tier,
            ),
            timeout=1800.0,   # VLM extraction can take long on large PDFs
        )

        text = resp.text
        method = resp.method or "unknown"
        metadata = dict(resp.metadata)
        pages = [
            PageContent(page_number=int(p.page_number), text=str(p.text), images=[])
            for p in resp.pages
        ]

        logger.info(
            "DocumentExtractor: result file=%s method=%s chars=%d pages=%d",
            filename, method, len(text), len(pages),
        )

        return ExtractedDocument(
            text=text,
            pages=pages,
            language="",
            method=method,
            metadata=metadata,
        )
