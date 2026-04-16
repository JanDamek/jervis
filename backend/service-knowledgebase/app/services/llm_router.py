"""LLM wrapper for KB service — thin pass-through to ollama-router.

The router owns all routing decisions. KB simply posts to `/api/generate`
(or `/api/chat` for vision) with the minimal contract:

  X-Capability  extraction | visual
  X-Client-Id   tenant (router resolves tier from CloudModelPolicy)

The router returns the response — local Ollama or OpenRouter, picked by
capability + client tier. Legacy `max_tier`, `model`, `priority`,
`route_decision` params are accepted but ignored so existing callers keep
compiling.
"""

from __future__ import annotations

import base64
import logging

import httpx

from app.core.config import settings

logger = logging.getLogger(__name__)

# Reuse a single httpx client for LLM calls — žádný read timeout (LLM trvá jak trvá)
_llm_http = httpx.AsyncClient(
    timeout=httpx.Timeout(connect=10.0, read=None, write=10.0, pool=30.0),
)


def _router_base_url() -> str:
    return settings.OLLAMA_INGEST_BASE_URL.rstrip("/")


def _headers(capability: str, client_id: str | None) -> dict[str, str]:
    h = {"Content-Type": "application/json", "X-Capability": capability}
    if client_id:
        h["X-Client-Id"] = client_id
    return h


async def llm_generate(
    prompt: str,
    max_tier: str = "NONE",           # Ignored — router resolves from client tier.
    model: str | None = None,         # Ignored — router picks the model.
    num_ctx: int = 8192,
    priority: int | None = None,      # Ignored — router manages queue priority.
    temperature: float = 0,
    format_json: bool = True,
    client_id: str | None = None,
) -> str:
    """Background extraction call (KB indexing, graph build, summarisation).

    Capability is always `extraction` for text prompts.
    """
    url = f"{_router_base_url()}/api/generate"
    payload: dict = {
        "prompt": prompt,
        "stream": False,
        "options": {"temperature": temperature, "num_ctx": num_ctx},
    }
    if format_json:
        payload["format"] = "json"

    max_retries = 3
    import asyncio
    for attempt in range(1 + max_retries):
        resp = await _llm_http.post(
            url, json=payload, headers=_headers("extraction", client_id),
        )
        if resp.status_code == 499 and attempt < max_retries:
            wait = 5 * (attempt + 1)
            logger.warning(
                "LLM_ROUTER: 499 GPU busy (attempt %d/%d), retrying in %ds",
                attempt + 1, max_retries + 1, wait,
            )
            await asyncio.sleep(wait)
            continue
        resp.raise_for_status()
        data = resp.json()
        # Router may return either Ollama-style {"response": "..."} or
        # OpenAI-style {"choices": [{"message": {"content": "..."}}]}
        if "response" in data:
            return data["response"]
        if "choices" in data:
            return data["choices"][0]["message"]["content"]
        return ""

    raise RuntimeError("llm_generate: exhausted retries (499 GPU busy)")


async def llm_generate_vision(
    image_bytes: bytes,
    prompt: str,
    max_tier: str = "NONE",           # Ignored — router resolves from client tier.
    priority: int | None = None,      # Ignored.
    client_id: str | None = None,
) -> str:
    """VLM call for image understanding. Capability is `visual`."""
    image_b64 = base64.b64encode(image_bytes).decode("utf-8")
    url = f"{_router_base_url()}/api/generate"
    payload = {
        "prompt": prompt,
        "images": [image_b64],
        "stream": False,
    }

    import asyncio
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            resp = await _llm_http.post(
                url, json=payload, headers=_headers("visual", client_id),
            )
            resp.raise_for_status()
            data = resp.json()
            if "response" in data:
                return data["response"]
            if "choices" in data:
                return data["choices"][0]["message"]["content"]
            return ""
        except Exception as e:
            last_error = e
            backoff = 5 * (2 ** attempt)
            logger.warning(
                "VLM call failed (attempt %d/3): %s — retrying in %ds",
                attempt + 1, e, backoff,
            )
            await asyncio.sleep(backoff)

    raise RuntimeError(f"VLM failed after 3 attempts: {last_error}")
