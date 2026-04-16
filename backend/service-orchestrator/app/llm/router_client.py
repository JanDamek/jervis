"""Thin compatibility layer over the unified router /api/chat surface.

The router is the single source of truth for model routing. There is no
separate `/router/admin/decide` dry-run — callers must simply invoke
`llm_provider.completion(capability=..., max_tier=..., client_id=...,
extra_headers={"X-Intent": ...})` and the router picks local vs cloud
+ the concrete model when `/api/chat` arrives.

`route_request()` is kept only as a thin, pure-Python stub so that legacy
callers that still want to branch on "was this going local or cloud?"
can compile. It returns a synthetic `RouteDecision` derived from the
caller's `max_tier` — without any HTTP call.

`report_model_error()` / `report_model_success()` still exist as
best-effort fire-and-forget feedback channels to the router's own
model reputation tracker. They never block and never fail the caller.

`get_max_context_tokens()` is a cheap GET kept for context-budget math
on the caller side.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


@dataclass
class RouteDecision:
    target: str = "local"           # "local" | "openrouter"
    model: str | None = None
    api_base: str | None = None
    api_key: str | None = None


def _router_base_url() -> str:
    return settings.ollama_url.rstrip("/").replace("/v1", "").replace("/api", "")


async def route_request(
    capability: str = "chat",
    max_tier: str = "NONE",
    estimated_tokens: int = 0,
    deadline_iso: str | None = None,
    priority: str = "NORMAL",
    min_model_size: int = 0,
    skip_models: list[str] | None = None,
    require_tools: bool = False,
    client_id: str | None = None,
) -> RouteDecision:
    """Synthetic pre-dispatch hint — does NOT call the router.

    Returns `target="openrouter"` whenever the caller allows any cloud tier,
    otherwise `target="local"`. `model` is always `None` — the actual model
    is selected by the router when `/api/chat` is hit via
    `llm_provider.completion(...)`. Legacy callers that still branch on
    `route.target` keep working; the branches now only influence which
    `X-Intent` / `max_tier` headers the caller emits, not the dispatch
    itself.
    """
    tier = (max_tier or "NONE").strip().upper()
    return RouteDecision(
        target="openrouter" if tier != "NONE" else "local",
        model=None,
        api_base=None,
        api_key=None,
    )


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


async def wait_for_any_model_recovery(
    max_tier: str = "NONE", max_wait_s: float = 20.0,
) -> dict | None:
    """Best-effort wait for the router to recover a cloud model.

    Router tracks its own model outage + cooldown; we simply pause this many
    seconds so retry logic lets the router try again. The exact "when did a
    model come back" is not observable without the deleted decide endpoint.
    """
    import asyncio

    if (max_tier or "NONE").strip().upper() == "NONE":
        return None
    await asyncio.sleep(min(max_wait_s, 5.0))
    return {"model": None, "waited": min(max_wait_s, 5.0)}


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
