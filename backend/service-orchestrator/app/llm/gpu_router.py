"""GPU Router announce/release helpers for orchestrator ↔ ollama-router integration.

Usage:
    session_id = f"orch-{thread_id}"
    await announce_gpu(session_id)
    try:
        ... # orchestration work
    finally:
        await release_gpu(session_id)

The helpers are graceful: if the router is unreachable, they log a warning and
proceed without blocking. The orchestrator can still talk to Ollama directly.
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


async def announce_gpu(session_id: str, model: str | None = None) -> bool:
    """Tell the Ollama Router we need the GPU.

    The router will preempt background work, unload background models,
    and load the orchestrator model. Returns True if GPU is ready.
    """
    model = model or settings.default_local_model
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(120.0)) as client:
            resp = await client.post(
                f"{settings.ollama_url}/router/announce",
                json={
                    "model": model,
                    "session_id": session_id,
                    "estimated_duration_s": 300,
                },
            )
            data = resp.json()
            status = data.get("status", "error")
            if status == "ready":
                logger.info("GPU reserved for session %s (model=%s)", session_id, model)
                return True
            else:
                logger.warning("GPU announce returned status=%s for session %s", status, session_id)
                return False
    except Exception as e:
        logger.warning("Router announce failed (proceeding without GPU reservation): %s", e)
        return False


async def release_gpu(session_id: str) -> None:
    """Tell the Ollama Router we're done with the GPU.

    The router will clear the reservation and start loading background models.
    Fire-and-forget – failures are non-critical.
    """
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(5.0)) as client:
            await client.post(
                f"{settings.ollama_url}/router/release",
                json={"session_id": session_id},
            )
            logger.info("GPU released for session %s", session_id)
    except Exception:
        pass  # Non-critical – router will auto-release on idle timeout
