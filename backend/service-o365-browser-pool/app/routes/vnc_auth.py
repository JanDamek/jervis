"""VNC authentication endpoints – one-time token login.

Token format: {connectionId}_{randomHex}

Flow:
1. Server calls /vnc-token on this pod → gets token with connectionId
2. UI opens https://jervis-vnc.damek-soft.eu/vnc-login?token={connId}_{hash}
3. VNC router parses connectionId → proxies to this pod's /vnc-login
4. This pod validates token, sets session cookie, redirects to /vnc.html
5. Subsequent requests use session cookie — VNC router proxies based on cookie
"""

from __future__ import annotations

import logging
from urllib.parse import quote

from fastapi import APIRouter, Cookie, Response
from fastapi.responses import RedirectResponse

from app.config import settings
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

    # /vnc-token/{client_id} moved to O365BrowserPoolService.CreateVncToken (gRPC).
    # /vnc-login + /vnc-auth stay HTTP — they're browser-facing, not pod-to-pod.

    @router.get("/vnc-login")
    async def vnc_login(token: str = "") -> Response:
        """Validate one-time token, set session cookie, redirect to noVNC.

        VNC router has already routed this request to the correct pod
        based on connectionId parsed from the token.
        """
        if not token:
            return Response(status_code=404)

        client_id = vnc_auth.validate_and_consume_token(token)
        if client_id is None:
            return Response(status_code=404)

        session_id = vnc_auth.create_session()
        vnc_pwd = _get_vnc_password()
        redirect_url = "/vnc.html?autoconnect=true&resize=scale"
        if vnc_pwd:
            redirect_url += f"&password={quote(vnc_pwd, safe='')}"

        response = RedirectResponse(url=redirect_url, status_code=302)
        response.set_cookie(
            key="vnc_session",
            value=f"{settings.connection_id}_{session_id}",
            httponly=True,
            secure=True,
            samesite="lax",
            path="/",
            max_age=3600,
        )
        logger.info("VNC login OK for client %s on connection %s", client_id, settings.connection_id)
        return response

    @router.get("/vnc-auth")
    async def vnc_auth_check(vnc_session: str = Cookie(default="")) -> Response:
        """Validate VNC session cookie."""
        if not vnc_session:
            return Response(status_code=401)
        # Parse connectionId from session cookie: "{connectionId}_{sessionId}"
        parts = vnc_session.split("_", 1)
        if len(parts) != 2:
            if vnc_auth.is_session_valid(vnc_session):
                return Response(status_code=200)
            return Response(status_code=401)
        if vnc_auth.is_session_valid(vnc_session):
            return Response(status_code=200)
        return Response(status_code=401)

    return router
