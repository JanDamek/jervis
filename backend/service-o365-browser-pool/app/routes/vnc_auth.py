"""VNC authentication endpoints – one-time token login, cookie validation."""

from __future__ import annotations

import logging
from urllib.parse import quote

from fastapi import APIRouter, Cookie, Response
from fastapi.responses import RedirectResponse

from app.vnc_auth import VncAuthManager

logger = logging.getLogger("o365-browser-pool.vnc-auth")

router = APIRouter(tags=["vnc-auth"])


def _get_vnc_password() -> str:
    """Read VNC password from generated file."""
    try:
        return open("/tmp/vnc_password").read().strip()
    except FileNotFoundError:
        return ""


def create_vnc_auth_router(vnc_auth: VncAuthManager) -> APIRouter:

    @router.post("/vnc-token/{client_id}")
    async def create_vnc_token(client_id: str) -> dict:
        """Generate a one-time VNC access token for a client.

        Called by Kotlin backend. Returns token string.
        """
        token = vnc_auth.create_token(client_id)
        return {"token": token, "client_id": client_id}

    @router.get("/vnc-login")
    async def vnc_login(token: str = "") -> Response:
        """Validate one-time token, set session cookie, redirect to noVNC.

        This endpoint is NOT behind auth_request (separate ingress).
        """
        if not token:
            return Response(status_code=404)

        client_id = vnc_auth.validate_and_consume_token(token)
        if client_id is None:
            return Response(status_code=404)

        # Token valid — create session and set cookie
        session_id = vnc_auth.create_session()

        # Build redirect URL to noVNC with auto-connect and VNC password.
        vnc_pwd = _get_vnc_password()
        redirect_url = "/vnc.html?autoconnect=true&resize=scale"
        if vnc_pwd:
            redirect_url += f"&password={quote(vnc_pwd, safe='')}"

        response = RedirectResponse(url=redirect_url, status_code=302)
        response.set_cookie(
            key="vnc_session",
            value=session_id,
            httponly=True,
            secure=True,
            samesite="lax",
            path="/",
            max_age=3600,  # 1 hour
        )
        logger.info("VNC login successful for client %s, session created", client_id)
        return response

    @router.get("/vnc-auth")
    async def vnc_auth_check(vnc_session: str = Cookie(default="")) -> Response:
        """Validate VNC session cookie.

        Called by nginx auth_request subrequest. Returns 200 if valid, 401 if not.
        """
        if vnc_session and vnc_auth.is_session_valid(vnc_session):
            return Response(status_code=200)
        return Response(status_code=401)

    return router
