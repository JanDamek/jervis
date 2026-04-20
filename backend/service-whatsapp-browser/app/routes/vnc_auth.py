"""VNC authentication endpoints — one-time token login, cookie validation.

Authorization chain: single-use /vnc-login?token=X → vnc_session cookie →
nginx auth_request on every static asset and WebSocket upgrade. The
x11vnc server runs with -nopw, so no password ever appears in a URL
(neither address bar nor iframe src).
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Cookie, Response

from app.vnc_auth import VncAuthManager

logger = logging.getLogger("whatsapp-browser.vnc-auth")

router = APIRouter(tags=["vnc-auth"])


_VNC_WRAPPER_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>WhatsApp VNC</title>
<style>
  html, body { margin: 0; padding: 0; height: 100%; overflow: hidden; background: #000; }
  iframe { border: 0; width: 100%; height: 100%; display: block; }
</style>
</head>
<body>
<iframe id="vnc" src="/vnc.html?autoconnect=true&resize=scale&reconnect=true" allow="clipboard-read; clipboard-write"></iframe>
<script>
var f=document.getElementById('vnc');
f.onload=function(){try{
  var d=f.contentDocument;
  var s=d.createElement('style');
  s.textContent='#noVNC_control_bar{display:none!important}#noVNC_control_bar_anchor{display:none!important}';
  d.head.appendChild(s);
}catch(e){}};
</script>
</body>
</html>
"""


def create_vnc_auth_router(vnc_auth: VncAuthManager) -> APIRouter:

    # /vnc-token/{client_id} moved to WhatsAppBrowserService.CreateVncToken (gRPC).
    # /vnc-login + /vnc-auth stay HTTP — they're browser-facing, not pod-to-pod.

    @router.get("/vnc-login")
    async def vnc_login(token: str = "") -> Response:
        """Validate one-time token, set session cookie, render noVNC.

        Response is an HTML wrapper embedding noVNC in an iframe with
        `autoconnect=true&resize=scale`. The browser URL stays
        /vnc-login?token=... — no 302, no password query leak.
        """
        if not token:
            return Response(status_code=404)

        client_id = vnc_auth.validate_and_consume_token(token)
        if client_id is None:
            return Response(status_code=404)

        session_id = vnc_auth.create_session()
        response = Response(content=_VNC_WRAPPER_HTML, media_type="text/html", status_code=200)
        response.set_cookie(
            key="vnc_session",
            value=session_id,
            httponly=True,
            secure=True,
            samesite="lax",
            path="/",
            max_age=3600,
        )
        logger.info("VNC login successful for client %s", client_id)
        return response

    @router.get("/vnc-auth")
    async def vnc_auth_check(vnc_session: str = Cookie(default="")) -> Response:
        if vnc_session and vnc_auth.is_session_valid(vnc_session):
            return Response(status_code=200)
        return Response(status_code=401)

    return router
