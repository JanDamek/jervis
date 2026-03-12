"""Health and readiness endpoints."""

from __future__ import annotations

from fastapi import APIRouter

from app.browser_manager import BrowserManager

router = APIRouter(tags=["health"])


def create_health_router(browser_manager: BrowserManager) -> APIRouter:
    @router.get("/health")
    async def health() -> dict:
        return {
            "status": "ok",
            "service": "o365-browser-pool",
            "active_sessions": browser_manager.active_count,
        }

    @router.get("/ready")
    async def ready() -> dict:
        if browser_manager._browser is None:
            return {"status": "not_ready"}
        return {"status": "ready"}

    return router
