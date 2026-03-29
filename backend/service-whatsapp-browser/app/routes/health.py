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
            "service": "whatsapp-browser",
            "active": browser_manager.active,
        }

    @router.get("/ready")
    async def ready() -> dict:
        if not browser_manager.is_ready:
            return {"status": "not_ready"}
        return {"status": "ready"}

    return router
