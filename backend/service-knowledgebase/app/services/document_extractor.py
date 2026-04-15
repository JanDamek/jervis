"""Document text extraction via jervis-document-extraction microservice.

All extraction logic (VLM, pymupdf, python-docx, openpyxl, etc.) lives in the
dedicated jervis-document-extraction service. This module is a thin HTTP client
that delegates to it, keeping KB service focused on ingestion and retrieval.

The dataclass interfaces (ExtractedDocument, PageContent, ImageDescription) are
preserved for backward compatibility with all callers in knowledge_service.py.
"""

from __future__ import annotations

import logging
import mimetypes
from dataclasses import dataclass, field

import httpx
from app.core.config import settings

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


class DocumentExtractor:
    """Thin HTTP client to jervis-document-extraction microservice."""

    def __init__(self):
        self._base_url = settings.DOCUMENT_EXTRACTION_URL
        if not self._base_url:
            raise ValueError(
                "DOCUMENT_EXTRACTION_URL not configured. "
                "Set it in ConfigMap (k8s/configmap.yaml) or environment."
            )

    async def extract(
        self,
        file_bytes: bytes,
        filename: str,
        mime_type: str = "",
        max_tier: str = "NONE",
    ) -> ExtractedDocument:
        """Extract text from file via jervis-document-extraction service.

        Calls POST /extract (multipart) and maps response to ExtractedDocument.
        """
        if not mime_type:
            mime_type, _ = mimetypes.guess_type(filename)
            mime_type = mime_type or "application/octet-stream"

        logger.info(
            "DocumentExtractor: delegating to %s file=%s mime=%s size=%d",
            self._base_url, filename, mime_type, len(file_bytes),
        )

        async with httpx.AsyncClient(timeout=httpx.Timeout(connect=10.0, read=None, write=10.0, pool=30.0)) as client:
            resp = await client.post(
                f"{self._base_url}/extract",
                files={"file": (filename, file_bytes, mime_type)},
                data={
                    "filename": filename,
                    "mime_type": mime_type,
                    "max_tier": max_tier,
                },
            )
            resp.raise_for_status()
            data = resp.json()

        text = data.get("text", "")
        method = data.get("method", "unknown")
        metadata = data.get("metadata", {})

        # Map pages if present (PDF extraction)
        pages = []
        for p in data.get("pages", []):
            images = [
                ImageDescription(description=img.get("description", ""), ocr_text=img.get("ocr_text", ""))
                for img in p.get("images", [])
            ]
            pages.append(PageContent(
                page_number=p.get("page_number", 0),
                text=p.get("text", ""),
                images=images,
            ))

        logger.info(
            "DocumentExtractor: result file=%s method=%s chars=%d pages=%d",
            filename, method, len(text), len(pages),
        )

        return ExtractedDocument(
            text=text,
            pages=pages,
            language=data.get("language", ""),
            method=method,
            metadata=metadata,
        )
