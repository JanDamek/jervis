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

    @router.post("/vnc-token/{client_id}")
    async def create_vnc_token(client_id: str) -> dict:
        token = vnc_auth.create_token(client_id)
        return {"token": token, "client_id": client_id}

    @router.get("/vnc-login")
    async def vnc_login(token: str = "") -> Response:
        if not token:
            return Response(status_code=404)

        client_id = vnc_auth.validate_and_consume_token(token)
        if client_id is None:
            return Response(status_code=404)

        session_id = vnc_auth.create_session()

        vnc_pwd = _get_vnc_password()

        # Serve inline HTML that connects noVNC with password injected via JS.
        # Password never appears in URL — only in page source (same-origin, HTTPS).
        html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>VNC</title></head>
<body style="margin:0;overflow:hidden">
<script>
// Set password in sessionStorage before noVNC loads (it reads from there)
sessionStorage.setItem('vnc_password', '{vnc_pwd}');
window.location.replace('/vnc.html?autoconnect=true&resize=scale');
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
