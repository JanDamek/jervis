"""VLM (Vision Language Model) client for frame analysis.

Sends base64-encoded JPEG frames to the ollama-router's OpenAI-compatible
``/v1/chat/completions`` endpoint. Three analysis modes are supported:

- **scene**: general office scene — OCR + layout + object identification.
- **whiteboard**: focused whiteboard/paper OCR, preserving structure.
- **screen**: focused monitor/screen OCR, code-aware.

Adapted from ``service-document-extraction/extractor.py:865-894``.
"""

from __future__ import annotations

import base64
import json
import logging
from typing import Literal

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

AnalysisMode = Literal["scene", "whiteboard", "screen"]

_RETRY_DELAYS = [5, 10, 20]  # seconds — same as whatsapp-browser vlm_client
_TIMEOUT = httpx.Timeout(connect=10.0, read=None, write=10.0, pool=30.0)  # VLM trvá jak trvá

_PROMPTS: dict[AnalysisMode, str] = {
    "scene": settings.visual_capture_vlm_prompt_scene,
    "whiteboard": settings.visual_capture_vlm_prompt_whiteboard,
    "screen": settings.visual_capture_vlm_prompt_screen,
}


async def analyze_frame(
    image_bytes: bytes,
    mode: AnalysisMode = "scene",
    custom_prompt: str | None = None,
) -> dict:
    """Send a JPEG frame to VLM and return structured analysis.

    Returns:
        ``{"description": str, "ocr_text": str, "mode": str, "model": str}``
        On failure: ``{"description": "", "ocr_text": "", "error": str}``
    """
    b64 = base64.b64encode(image_bytes).decode("ascii")
    prompt = custom_prompt or _PROMPTS.get(mode, _PROMPTS["scene"])
    model = settings.visual_capture_vlm_model

    payload = {
        "model": model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/jpeg;base64,{b64}"},
                    },
                ],
            }
        ],
        "max_tokens": 4096,
        "temperature": 0,
    }

    url = f"{settings.ollama_router_url}/v1/chat/completions"
    headers = {"X-Ollama-Priority": "1"}  # foreground priority for real-time hints

    last_error = ""
    for attempt, delay in enumerate(_RETRY_DELAYS, 1):
        try:
            async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
                resp = await client.post(url, json=payload, headers=headers)
                resp.raise_for_status()
                data = resp.json()

            text = (
                data.get("choices", [{}])[0]
                .get("message", {})
                .get("content", "")
            )

            # Split into description + OCR if the model structured it
            # (best-effort heuristic — VLM output is free-form)
            ocr_text = ""
            description = text
            for marker in ["OCR:", "Text:", "Visible text:", "Extrahovaný text:"]:
                if marker in text:
                    parts = text.split(marker, 1)
                    description = parts[0].strip()
                    ocr_text = parts[1].strip()
                    break

            logger.info(
                "VLM_ANALYZE: mode=%s model=%s desc_len=%d ocr_len=%d",
                mode, model, len(description), len(ocr_text),
            )
            return {
                "description": description,
                "ocr_text": ocr_text,
                "mode": mode,
                "model": model,
            }

        except Exception as e:
            last_error = f"{type(e).__name__}: {e}"
            logger.warning(
                "VLM_ANALYZE: attempt %d/%d failed: %s — retry in %ds",
                attempt, len(_RETRY_DELAYS), last_error, delay,
            )
            if attempt < len(_RETRY_DELAYS):
                import asyncio
                await asyncio.sleep(delay)

    logger.error("VLM_ANALYZE: all retries exhausted: %s", last_error)
    return {"description": "", "ocr_text": "", "error": last_error, "mode": mode}
