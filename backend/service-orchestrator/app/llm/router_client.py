"""Thin helpers around the unified router `/api/chat` surface.

All model routing happens inside the router: callers invoke
`llm_provider.completion(capability=..., max_tier=..., client_id=...,
extra_headers={"X-Intent": ...})` and the router picks local vs cloud +
the concrete model from headers.

What lives here now:

- `report_model_error()` / `report_model_success()` — best-effort
  fire-and-forget feedback to the router's own model reputation tracker.
  Never block, never fail the caller.
- `get_max_context_tokens()` — cheap GET kept for context-budget math
  on the caller side (e.g. chat history compression).

The old `route_request()` dry-run helper and its companion `RouteDecision`
dataclass were removed together with the `/router/admin/decide` endpoint.
Callers that used to branch on `route.target` now pass `X-Intent` and let
the router decide.
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


def _router_base_url() -> str:
    return settings.ollama_url.rstrip("/").replace("/v1", "").replace("/api", "")


# ── Model reputation reporting — optional, best-effort ──────────────────
# Router already tracks its own dispatch outcomes. These helpers exist so
# orchestrator-side heuristics (hallucination guard, language mismatch) can
# still nudge the router's model-error table. Not required for correctness.


async def report_model_error(model_id: str, error_message: str = "") -> dict:
    url = f"{_router_base_url()}/router/admin/model-error"
    try:
        payload: dict = {"model_id": model_id}
        if error_message:
            payload["error_message"] = error_message[:500]
        async with httpx.AsyncClient(timeout=3.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            return resp.json()
    except Exception as e:
        logger.debug("report_model_error best-effort: %s", e)
        return {}


async def report_model_success(
    model_id: str, duration_s: float = 0.0,
    input_tokens: int = 0, output_tokens: int = 0,
) -> None:
    url = f"{_router_base_url()}/router/admin/model-success"
    try:
        payload: dict = {"model_id": model_id}
        if duration_s > 0:
            payload["duration_s"] = round(duration_s, 2)
        if input_tokens > 0:
            payload["input_tokens"] = input_tokens
        if output_tokens > 0:
            payload["output_tokens"] = output_tokens
        async with httpx.AsyncClient(timeout=3.0) as client:
            await client.post(url, json=payload)
    except Exception as e:
        logger.debug("report_model_success best-effort: %s", e)


async def get_max_context_tokens(max_tier: str = "NONE") -> int:
    url = f"{_router_base_url()}/router/admin/max-context?max_tier={max_tier}"
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            return resp.json().get("max_context_tokens", 48_000)
    except Exception as e:
        logger.debug("get_max_context_tokens best-effort: %s", e)
        return 48_000
