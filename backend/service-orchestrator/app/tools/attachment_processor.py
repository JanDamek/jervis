"""Attachment processor — extracts text from attachments via Tika.

Processes base64-encoded attachments received from Kotlin server.
For images with insufficient OCR text, uses the existing vision description
from the Qualifier Agent (already populated by Kotlin before dispatch).
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

# Minimum OCR text length to consider sufficient (skip VLM fallback)
_OCR_TEXT_THRESHOLD = 50


async def process_attachment(attachment: dict) -> str | None:
    """Extract text from a single attachment.

    Args:
        attachment: Dict with keys: id, filename, mime_type, size_bytes,
                    attachment_type, data_base64, vision_description

    Returns:
        Extracted text string, or None if processing failed.
    """
    filename = attachment.get("filename", "unknown")
    mime_type = attachment.get("mime_type", "")
    attachment_type = attachment.get("attachment_type", "UNKNOWN")
    data_base64 = attachment.get("data_base64", "")
    vision_description = attachment.get("vision_description")

    if not data_base64:
        logger.warning("Attachment %s has no data, skipping", filename)
        return None

    # If Qualifier Agent already provided a vision description, use it directly
    # (this is the case for images that were already analyzed by qwen3-vl)
    if vision_description and attachment_type in ("IMAGE", "PDF_SCANNED"):
        logger.info(
            "ATTACHMENT_PROCESSOR: using existing vision description for %s (%d chars)",
            filename, len(vision_description),
        )
        return f"[Attachment: {filename}]\n{vision_description}"

    # Extract text via Tika
    try:
        text = await _call_tika(data_base64, filename)
    except Exception as e:
        logger.error("ATTACHMENT_PROCESSOR: Tika failed for %s: %s", filename, e)
        return None

    if not text or len(text.strip()) < _OCR_TEXT_THRESHOLD:
        # For images with poor OCR, there's no text to use
        if attachment_type in ("IMAGE", "PDF_SCANNED"):
            logger.info(
                "ATTACHMENT_PROCESSOR: OCR insufficient for %s (%d chars), no vision fallback available",
                filename, len((text or "").strip()),
            )
            return f"[Attachment: {filename} — image, text extraction insufficient]"
        # For other types with poor extraction, still return what we have
        if text and text.strip():
            return f"[Attachment: {filename}]\n{text.strip()}"
        return f"[Attachment: {filename} — content could not be extracted]"

    logger.info(
        "ATTACHMENT_PROCESSOR: extracted %d chars from %s via Tika",
        len(text), filename,
    )
    return f"[Attachment: {filename}]\n{text}"


async def process_all_attachments(attachments: list[dict]) -> str:
    """Process all attachments and return combined text block.

    Args:
        attachments: List of attachment dicts from CodingTask.

    Returns:
        Combined text from all attachments, or empty string if none.
    """
    if not attachments:
        return ""

    parts: list[str] = []
    for attachment in attachments:
        result = await process_attachment(attachment)
        if result:
            parts.append(result)

    if not parts:
        return ""

    logger.info(
        "ATTACHMENT_PROCESSOR: processed %d/%d attachments successfully",
        len(parts), len(attachments),
    )
    return "\n\n".join(parts)


async def _call_tika(data_base64: str, filename: str) -> str:
    """Call Tika service to extract text from base64-encoded file.

    Uses the same Tika API as the KB service's TikaClient.
    """
    payload = {
        "source": {
            "type": "FileBytes",
            "fileName": filename,
            "dataBase64": data_base64,
        },
        "includeMetadata": False,
    }

    tika_url = settings.tika_url.rstrip("/")
    logger.info("Calling Tika for attachment %s", filename)
    async with httpx.AsyncClient() as client:
        response = await client.post(
            f"{tika_url}/api/tika/process",
            json=payload,
            timeout=60.0,
        )
        response.raise_for_status()
        result = response.json()
        text = result.get("plainText", "")
        logger.info("Tika result for %s: %d chars", filename, len(text))
        return text
