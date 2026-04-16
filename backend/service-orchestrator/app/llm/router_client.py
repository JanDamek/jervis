"""Thin compatibility layer over the unified router /api/chat surface.

DESIGN: see KB agent://claude-code/task-routing-unified-design.

The LEGACY orchestrator pattern was:
    route = await route_request(...)     # asks /route-decision
    llm_provider.completion(route=route, ...)  # dispatches with decided model

The UNIFIED pattern is:
    llm_provider.completion(capability=..., deadline_iso=..., ...)  # one shot through /api/chat
    # router decides + dispatches + retries + rate-limits internally

Full caller migration to the unified pattern is tracked in
agent://claude-code/orchestrator-llm-unification-proposal. Until every
caller is migrated we keep this thin adapter so existing imports keep
working. It does NOT call a separate /route-decision endpoint — that
endpoint has been deleted. It asks the router for a dry-run decision
via /router/admin/decide so the caller can still reason about whether
the target is local vs cloud, but the dispatch itself always happens
through llm_provider.completion → /api/chat.

Scheduled for removal together with the last caller migration.
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
    """Ask the router for a dry-run routing decision.

    The router picks local vs cloud + concrete model based on the new
    (capability, tier, deadline, priority, min_model_size) inputs. This
    wrapper is kept only so older call sites that need a RouteDecision
    *before* dispatch (LiteLLM escape hatch) still compile. New call sites
    should use llm_provider.completion() directly — it talks to /api/chat
    where the decision + dispatch happen in one step.
    """
    url = f"{_router_base_url()}/router/admin/decide"
    try:
        payload: dict = {
            "capability": capability,
            "estimated_tokens": estimated_tokens,
            "priority": priority,
        }
        if deadline_iso:
            payload["deadline_iso"] = deadline_iso
        if min_model_size:
            payload["min_model_size"] = min_model_size
        if client_id:
            payload["client_id"] = client_id
        if max_tier and max_tier != "NONE":
            payload["max_tier"] = max_tier
        if skip_models:
            payload["skip_models"] = skip_models
        if require_tools:
            payload["require_tools"] = True
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()
            model = data.get("model")
            target = data.get("target", "local")
            # Preserve legacy litellm provider prefixes for callers that still
            # pass model_override into llm_provider.completion().
            if model and target == "openrouter" and not model.startswith("openrouter/"):
                model = f"openrouter/{model}"
            elif model and target == "local" and not model.startswith("ollama/"):
                model = f"ollama/{model}"
            return RouteDecision(
                target=target,
                model=model,
                api_base=data.get("api_base"),
                api_key=data.get("api_key"),
            )
    except Exception as e:
        logger.warning("route_request dry-run failed (%s) — defaulting to local", e)
        return RouteDecision(
            target="local",
            model=settings.default_local_model,
            api_base=settings.ollama_url,
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
    """Poll the router for any cloud model coming back online."""
    import asyncio
    import time

    deadline = time.monotonic() + max_wait_s
    while time.monotonic() < deadline:
        try:
            decision = await route_request(
                capability="chat",
                max_tier=max_tier,
                estimated_tokens=1000,
            )
            if decision.target == "openrouter" and decision.model:
                waited = max_wait_s - (deadline - time.monotonic())
                return {"model": decision.model, "waited": waited}
        except Exception:
            pass
        await asyncio.sleep(1.0)
    return None


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
