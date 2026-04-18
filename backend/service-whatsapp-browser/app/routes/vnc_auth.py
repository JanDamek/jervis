"""VNC authentication endpoints — one-time token login, cookie validation."""

from __future__ import annotations

import logging
from urllib.parse import quote

from fastapi import APIRouter, Cookie, Response
from fastapi.responses import RedirectResponse

from app.vnc_auth import VncAuthManager

logger = logging.getLogger("whatsapp-browser.vnc-auth")

router = APIRouter(tags=["vnc-auth"])


def _get_vnc_password() -> str:
    try:
        return open("/tmp/vnc_password").read().strip()
    except FileNotFoundError:
        return ""


def create_vnc_auth_router(vnc_auth: VncAuthManager) -> APIRouter:

    # /vnc-token/{client_id} moved to WhatsAppBrowserService.CreateVncToken (gRPC).
    # /vnc-login + /vnc-auth stay HTTP — they're browser-facing, not pod-to-pod.

    @router.get("/vnc-login")
    async def vnc_login(token: str = "") -> Response:
        if not token:
            return Response(status_code=404)

        client_id = vnc_auth.validate_and_consume_token(token)
        if client_id is None:
            return Response(status_code=404)

        session_id = vnc_auth.create_session()

        vnc_pwd = _get_vnc_password()

        # Serve page with hidden iframe — password in iframe src, not in address bar.
        escaped_pwd = quote(vnc_pwd, safe="")
        vnc_params = f"autoconnect=true&resize=scale&reconnect=true&password={escaped_pwd}"
        html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>WhatsApp VNC</title>
<style>
body{{margin:0;overflow:hidden}}
iframe{{border:none;width:100vw;height:100vh}}
</style></head><body>
<iframe id="vnc" src="/vnc.html?{vnc_params}"></iframe>
<script>
// Hide noVNC control bar after connection (runs inside iframe)
var f=document.getElementById('vnc');
f.onload=function(){{try{{
  var d=f.contentDocument;
  var s=d.createElement('style');
  s.textContent='#noVNC_control_bar{{display:none!important}}#noVNC_control_bar_anchor{{display:none!important}}';
  d.head.appendChild(s);
}}catch(e){{}}}};
</script>
</body></html>"""

        response = Response(content=html, media_type="text/html", status_code=200)
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
