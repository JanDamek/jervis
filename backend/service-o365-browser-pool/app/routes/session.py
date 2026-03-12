"""Session management endpoints – init, status, refresh, close."""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException

from app.browser_manager import BrowserManager
from app.models import (
    SessionInitRequest,
    SessionInitResponse,
    SessionState,
    SessionStatus,
)
from app.token_extractor import TokenExtractor

logger = logging.getLogger("o365-browser-pool")

router = APIRouter(tags=["session"])


def create_session_router(
    browser_manager: BrowserManager,
    token_extractor: TokenExtractor,
) -> APIRouter:
    @router.get("/session/{client_id}")
    async def get_session(client_id: str) -> SessionStatus:
        """Get session status for a client."""
        state = browser_manager.get_state(client_id)
        token_info = token_extractor.get_graph_token(client_id)
        return SessionStatus(
            client_id=client_id,
            state=state,
            last_activity=datetime.now(timezone.utc).isoformat(),
            last_token_extract=(
                token_info.extracted_at.isoformat() if token_info else None
            ),
        )

    @router.post("/session/{client_id}/init")
    async def init_session(
        client_id: str,
        request: SessionInitRequest | None = None,
    ) -> SessionInitResponse:
        """Create a browser context and navigate to the O365 login page.

        The user must complete login manually (via noVNC or screenshot).
        After login, token interception starts automatically.
        """
        req = request or SessionInitRequest()
        try:
            context = await browser_manager.get_or_create_context(
                client_id, user_agent=req.user_agent
            )
        except RuntimeError as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc

        page = await context.new_page()
        await token_extractor.setup_interception(client_id, page)

        try:
            await page.goto(req.login_url, wait_until="domcontentloaded", timeout=30000)
        except Exception:
            logger.warning("Navigation to %s timed out for %s", req.login_url, client_id)

        browser_manager.set_state(client_id, SessionState.PENDING_LOGIN)
        logger.info("Session initialized for %s → %s", client_id, req.login_url)

        return SessionInitResponse(
            client_id=client_id,
            state=SessionState.PENDING_LOGIN,
            message=f"Session created. Navigate to login page: {req.login_url}",
        )

    @router.post("/session/{client_id}/refresh")
    async def refresh_session(client_id: str) -> SessionStatus:
        """Force navigation to Teams to trigger a token refresh.

        Opens a new page pointing to Teams, which causes the browser to
        send authenticated requests and thus refresh the captured token.
        """
        context = browser_manager.get_context(client_id)
        if not context:
            raise HTTPException(
                status_code=404,
                detail=f"No session for client '{client_id}'",
            )

        page = await context.new_page()
        await token_extractor.setup_interception(client_id, page)

        try:
            await page.goto(
                "https://teams.microsoft.com",
                wait_until="networkidle",
                timeout=30000,
            )
        except Exception:
            logger.warning("Refresh navigation timed out for %s", client_id)

        # Close the refresh page (keep main session pages)
        await page.close()

        if token_extractor.has_valid_token(client_id):
            browser_manager.set_state(client_id, SessionState.ACTIVE)
        else:
            browser_manager.set_state(client_id, SessionState.EXPIRED)

        await browser_manager.save_state(client_id)

        return SessionStatus(
            client_id=client_id,
            state=browser_manager.get_state(client_id),
            last_activity=datetime.now(timezone.utc).isoformat(),
        )

    @router.delete("/session/{client_id}")
    async def delete_session(client_id: str) -> dict:
        """Close browser context and clean up."""
        await browser_manager.close_context(client_id)
        token_extractor.invalidate(client_id)
        logger.info("Session closed for %s", client_id)
        return {"status": "closed", "client_id": client_id}

    return router
