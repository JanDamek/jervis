"""VNC authentication endpoints – one-time token login with multi-pod routing.

Token format: {podOrdinal}_{randomHex}
Any pod can receive the vnc-login request. It parses the ordinal from the token:
- If this IS the owning pod → validate, consume, set cookie, redirect to noVNC
- If this is NOT the owning pod → proxy the request to the correct pod via
  StatefulSet DNS (internal K8s network, not exposed externally)
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
        """Generate a one-time VNC access token for a client.

        Called by Kotlin backend on the correct pod (routed via browserPoolUrl).
        Token includes this pod's ordinal so vnc-login can route correctly.
        """
        token = vnc_auth.create_token(client_id)
        return {"token": token, "client_id": client_id}

    @router.get("/vnc-login")
    async def vnc_login(token: str = "") -> Response:
        """Validate one-time token, set session cookie, redirect to noVNC.

        Multi-pod routing: parses pod ordinal from token. If request landed
        on the wrong pod (load balancer), proxies to the correct pod internally.
        """
        if not token:
            return Response(status_code=404)

        # Parse pod ordinal from token
        target_ordinal = VncAuthManager.parse_pod_ordinal(token)
        if target_ordinal is None:
            logger.warning("VNC token has invalid format (no ordinal): %s", token[:20])
            return Response(status_code=404)

        my_ordinal = settings.pod_ordinal

        # If token belongs to a different pod, proxy the request there
        if target_ordinal != my_ordinal:
            logger.info(
                "VNC token for pod %d, I am pod %d — proxying",
                target_ordinal, my_ordinal,
            )
            target_url = f"{_pod_internal_url(target_ordinal)}/vnc-login?token={quote(token, safe='')}"
            try:
                async with httpx.AsyncClient(timeout=10) as client:
                    resp = await client.get(target_url, follow_redirects=False)

                # Forward the response (redirect + cookies) from the correct pod
                response = Response(
                    content=resp.content,
                    status_code=resp.status_code,
                    headers=dict(resp.headers),
                )
                return response
            except Exception as e:
                logger.error("Failed to proxy VNC login to pod %d: %s", target_ordinal, e)
                return Response(status_code=502)

        # This IS the owning pod — validate and consume token
        client_id = vnc_auth.validate_and_consume_token(token)
        if client_id is None:
            return Response(status_code=404)

        # Token valid — create session, set cookie, serve noVNC directly.
        # No redirect — URL stays as /vnc-login?token=... (token already consumed,
        # session cookie is the auth mechanism from here on).
        session_id = vnc_auth.create_session()
        vnc_pwd = _get_vnc_password()

        import os
        novnc_dir = os.environ.get("NOVNC_DIR", "/usr/share/novnc")
        vnc_html_path = os.path.join(novnc_dir, "vnc.html")
        try:
            with open(vnc_html_path) as f:
                html = f.read()
        except FileNotFoundError:
            return Response(status_code=500, content="noVNC not found")

        # Inject auto-connect config into noVNC HTML
        inject_script = f"""
        <script>
        window.addEventListener('load', function() {{
            var ui = window.UI || {{}};
            if (ui.connect) {{
                ui.connect('{vnc_pwd}');
            }} else {{
                // Fallback: set URL params for noVNC to read
                var params = new URLSearchParams(window.location.search);
                if (!params.has('autoconnect')) {{
                    params.set('autoconnect', 'true');
                    params.set('resize', 'scale');
                    params.set('password', '{vnc_pwd}');
                }}
            }}
        }});
        </script>
        """
        html = html.replace("</head>", inject_script + "</head>")

        response = Response(content=html, media_type="text/html")
        response.set_cookie(
            key="vnc_session",
            value=session_id,
            httponly=True,
            secure=True,
            samesite="lax",
            path="/",
            max_age=3600,
        )
        logger.info("VNC login successful for client %s on pod %d", client_id, my_ordinal)
        return response

    @router.get("/vnc-auth")
    async def vnc_auth_check(vnc_session: str = Cookie(default="")) -> Response:
        """Validate VNC session cookie."""
        if vnc_session and vnc_auth.is_session_valid(vnc_session):
            return Response(status_code=200)
        return Response(status_code=401)

    return router
