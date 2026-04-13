"""VNC authentication endpoints – one-time token login with multi-pod routing.

Token format: {podOrdinal}_{randomHex}

Flow:
1. UI calls server → server calls /vnc-token on correct pod → gets token with ordinal
2. UI opens https://jervis-vnc.damek-soft.eu/vnc-login?token=1_abc123
3. Ingress routes to any pod (lb service)
4. vnc-login parses ordinal from token:
   - If this IS the owning pod → validate token, set session cookie with pod ordinal,
     redirect to /vnc.html?autoconnect=true&resize=scale&password=...
   - If NOT → proxy to owning pod, forward response (including Set-Cookie)
5. Session cookie contains pod ordinal → VNC middleware uses it to proxy
   all subsequent requests (static files + WebSocket) to the correct pod
"""

from __future__ import annotations

import logging
from urllib.parse import quote

import httpx
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


def _pod_internal_url(ordinal: int) -> str:
    """Build internal URL for a specific StatefulSet pod."""
    return (
        f"http://{settings.statefulset_service}-{ordinal}"
        f".{settings.statefulset_service}.{settings.k8s_namespace}"
        f".svc.cluster.local:8090"
    )


def create_vnc_auth_router(vnc_auth: VncAuthManager) -> APIRouter:

    @router.post("/vnc-token/{client_id}")
    async def create_vnc_token(client_id: str) -> dict:
        """Generate a one-time VNC access token. Called by Kotlin server on the correct pod."""
        token = vnc_auth.create_token(client_id)
        return {"token": token, "client_id": client_id}

    @router.get("/vnc-login")
    async def vnc_login(token: str = "") -> Response:
        """Validate one-time token, set session cookie, redirect to noVNC.

        Multi-pod: parses ordinal from token. If wrong pod, proxies to correct pod
        and forwards its response (including Set-Cookie with correct pod ordinal).
        """
        if not token:
            return Response(status_code=404)

        target_ordinal = VncAuthManager.parse_pod_ordinal(token)
        if target_ordinal is None:
            return Response(status_code=404)

        my_ordinal = settings.pod_ordinal

        if target_ordinal != my_ordinal:
            # Proxy to correct pod — forward entire response including cookies
            logger.info("VNC login: token for pod %d, proxying (I am pod %d)", target_ordinal, my_ordinal)
            target_url = f"{_pod_internal_url(target_ordinal)}/vnc-login?token={quote(token, safe='')}"
            try:
                async with httpx.AsyncClient(timeout=15) as client:
                    resp = await client.get(target_url, follow_redirects=False)
                response = Response(
                    content=resp.content,
                    status_code=resp.status_code,
                    headers={k: v for k, v in resp.headers.items()
                             if k.lower() in ("set-cookie", "location", "content-type")},
                )
                return response
            except Exception as e:
                logger.error("VNC proxy to pod %d failed: %s", target_ordinal, e)
                return Response(status_code=502)

        # This IS the owning pod
        client_id = vnc_auth.validate_and_consume_token(token)
        if client_id is None:
            return Response(status_code=404)

        session_id = vnc_auth.create_session()
        vnc_pwd = _get_vnc_password()
        redirect_url = f"/vnc.html?autoconnect=true&resize=scale"
        if vnc_pwd:
            redirect_url += f"&password={quote(vnc_pwd, safe='')}"

        response = RedirectResponse(url=redirect_url, status_code=302)
        response.set_cookie(
            key="vnc_session",
            value=f"{my_ordinal}_{session_id}",
            httponly=True,
            secure=True,
            samesite="lax",
            path="/",
            max_age=3600,
        )
        logger.info("VNC login OK for client %s on pod %d", client_id, my_ordinal)
        return response

    @router.get("/vnc-auth")
    async def vnc_auth_check(vnc_session: str = Cookie(default="")) -> Response:
        """Validate VNC session cookie."""
        if not vnc_session:
            return Response(status_code=401)
        # Parse pod ordinal from session cookie: "{ordinal}_{sessionId}"
        parts = vnc_session.split("_", 1)
        if len(parts) != 2:
            # Legacy format without ordinal
            if vnc_auth.is_session_valid(vnc_session):
                return Response(status_code=200)
            return Response(status_code=401)
        session_id = parts[1]
        if vnc_auth.is_session_valid(f"{parts[0]}_{session_id}"):
            return Response(status_code=200)
        return Response(status_code=401)

    return router
