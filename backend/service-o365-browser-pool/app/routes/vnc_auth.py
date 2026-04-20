"""VNC authentication endpoints – one-time token login.

Token format: {connectionId}_{randomHex}

Flow:
1. Server calls O365BrowserPoolService.CreateVncToken (gRPC) → pod mints
   a token bound to connectionId.
2. UI opens https://jervis-vnc.damek-soft.eu/vnc-login?token={connId}_{hash}
3. VNC router parses connectionId → proxies to this pod's /vnc-login.
4. This pod validates the token (single-use), sets the vnc_session cookie,
   and returns an HTML wrapper (iframe → /vnc.html). The browser URL stays
   /vnc-login?token=... — no 302 redirect, no `?password=` query leak.
5. Subsequent requests (static assets, /websockify WS) use the session
   cookie — VNC router proxies based on cookie.

The x11vnc server itself runs with -nopw: authorization is enforced by
the token + cookie + nginx auth_request chain, so a second password
layer would only add attack surface via URLs.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Cookie, Response
from fastapi.responses import HTMLResponse

from app.config import settings
from app.vnc_auth import VncAuthManager

logger = logging.getLogger("o365-browser-pool.vnc-auth")

router = APIRouter(tags=["vnc-auth"])


_VNC_WRAPPER_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Jervis VNC</title>
<style>
  html, body { margin: 0; padding: 0; height: 100%; background: #000; }
  iframe { border: 0; width: 100%; height: 100%; display: block; }
</style>
</head>
<body>
<iframe src="/vnc.html?autoconnect=true&resize=scale" allow="clipboard-read; clipboard-write"></iframe>
</body>
</html>
"""


def create_vnc_auth_router(vnc_auth: VncAuthManager) -> APIRouter:

    # /vnc-token/{client_id} moved to O365BrowserPoolService.CreateVncToken (gRPC).
    # /vnc-login + /vnc-auth stay HTTP — they're browser-facing, not pod-to-pod.

    @router.get("/vnc-login")
    async def vnc_login(token: str = "") -> Response:
        """Validate one-time token, set session cookie, render noVNC.

        VNC router has already routed this request to the correct pod
        based on connectionId parsed from the token. The response is an
        HTML wrapper embedding noVNC in an iframe — the browser URL
        stays /vnc-login?token=... so no secret ever appears in the
        address bar or history.
        """
        if not token:
            return Response(status_code=404)

        client_id = vnc_auth.validate_and_consume_token(token)
        if client_id is None:
            return Response(status_code=404)

        session_id = vnc_auth.create_session()
        response = HTMLResponse(content=_VNC_WRAPPER_HTML)
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
        if vnc_auth.is_session_valid(vnc_session):
            return Response(status_code=200)
        return Response(status_code=401)

    return router
