"""VNC WebSocket proxy + static file middleware with multi-pod routing.

Every pod can serve VNC for any connection. Session cookie contains pod ordinal:
  vnc_session={ordinal}_{sessionId}

If request lands on the wrong pod, it's transparently proxied to the correct one
via StatefulSet DNS. WebSocket proxy uses httpx + websockets for full duplex.
"""

from __future__ import annotations

import asyncio
import logging
import os

import httpx
from starlette.requests import Request
from starlette.responses import Response, FileResponse
from starlette.websockets import WebSocket, WebSocketDisconnect
from fastapi import APIRouter

from app.config import settings
from app.vnc_auth import VncAuthManager

logger = logging.getLogger("o365-browser-pool.vnc")

NOVNC_DIR = os.environ.get("NOVNC_DIR", "/usr/share/novnc")

# Paths that bypass VNC cookie auth (internal API endpoints)
_SKIP_AUTH_PREFIXES = (
    "/health", "/ready",
    "/session/", "/token/",
    "/vnc-login", "/vnc-token/", "/vnc-auth",
    "/graph/",
    "/scrape/",
    "/meeting/",
)


def _skip_auth(path: str) -> bool:
    return any(path.startswith(p) for p in _SKIP_AUTH_PREFIXES)


def _pod_url(ordinal: int) -> str:
    return (
        f"http://{settings.statefulset_service}-{ordinal}"
        f".{settings.statefulset_service}.{settings.k8s_namespace}"
        f".svc.cluster.local"
    )


def _parse_session_cookie(cookie: str) -> tuple[int | None, str]:
    """Parse vnc_session cookie: '{ordinal}_{sessionId}' → (ordinal, full_cookie)."""
    parts = cookie.split("_", 1)
    if len(parts) == 2:
        try:
            return int(parts[0]), cookie
        except ValueError:
            pass
    return None, cookie


def create_vnc_proxy_router(vnc_auth: VncAuthManager) -> APIRouter:
    router = APIRouter()

    @router.websocket("/websockify")
    async def websockify_proxy(ws: WebSocket):
        """Proxy WebSocket to local or remote websockify.

        Reads pod ordinal from vnc_session cookie. If this is the correct pod,
        proxies to local websockify (localhost:6080). Otherwise proxies to the
        correct pod's websockify endpoint.
        """
        cookie = ws.cookies.get("vnc_session", "")
        target_ordinal, full_cookie = _parse_session_cookie(cookie)

        if target_ordinal is None or not vnc_auth.is_session_valid(full_cookie):
            await ws.close(code=4401)
            return

        my_ordinal = settings.pod_ordinal

        if target_ordinal == my_ordinal:
            # Local proxy to websockify
            target = "ws://127.0.0.1:6080"
        else:
            # Remote proxy to correct pod
            target = f"ws://{settings.statefulset_service}-{target_ordinal}.{settings.statefulset_service}.{settings.k8s_namespace}.svc.cluster.local:6080"

        await ws.accept()
        logger.info("WebSocket VNC: pod=%d (target=%d, %s)", my_ordinal, target_ordinal,
                     "local" if target_ordinal == my_ordinal else "remote")

        try:
            import websockets
            async with websockets.connect(target) as remote:
                async def forward(src, dst_send):
                    try:
                        async for msg in src:
                            if isinstance(msg, bytes):
                                await dst_send(msg)
                            else:
                                await dst_send(msg)
                    except Exception:
                        pass

                async def ws_to_remote():
                    try:
                        while True:
                            data = await ws.receive_bytes()
                            await remote.send(data)
                    except (WebSocketDisconnect, Exception):
                        pass

                async def remote_to_ws():
                    try:
                        async for msg in remote:
                            if isinstance(msg, bytes):
                                await ws.send_bytes(msg)
                            else:
                                await ws.send_text(msg)
                    except (WebSocketDisconnect, Exception):
                        pass

                await asyncio.gather(ws_to_remote(), remote_to_ws())
        except Exception as e:
            logger.error("WebSocket VNC proxy error: %s", e)
        finally:
            try:
                await ws.close()
            except Exception:
                pass

    return router


def create_vnc_static_middleware(vnc_auth: VncAuthManager):
    """Middleware that serves noVNC static files with cookie auth + multi-pod proxy.

    If session cookie indicates a different pod, proxies the request there.
    """

    async def middleware(request: Request, call_next):
        path = request.url.path

        # Let routers handle API and WebSocket paths
        if _skip_auth(path) or path == "/websockify":
            return await call_next(request)

        # Check session cookie
        cookie = request.cookies.get("vnc_session", "")
        target_ordinal, full_cookie = _parse_session_cookie(cookie)

        if not vnc_auth.is_session_valid(full_cookie):
            return Response(status_code=404)

        my_ordinal = settings.pod_ordinal

        # If session belongs to a different pod, proxy the static file request
        if target_ordinal is not None and target_ordinal != my_ordinal:
            target_base = _pod_url(target_ordinal)
            try:
                async with httpx.AsyncClient(timeout=10) as client:
                    resp = await client.get(
                        f"{target_base}:8090{path}",
                        cookies={"vnc_session": full_cookie},
                    )
                return Response(
                    content=resp.content,
                    status_code=resp.status_code,
                    media_type=resp.headers.get("content-type"),
                )
            except Exception as e:
                logger.error("VNC static proxy to pod %d failed: %s", target_ordinal, e)
                return Response(status_code=502)

        # Serve local noVNC static file
        if path in ("/", ""):
            file_path = os.path.join(NOVNC_DIR, "vnc.html")
        else:
            clean = path.lstrip("/").replace("..", "")
            file_path = os.path.join(NOVNC_DIR, clean)

        if os.path.isfile(file_path):
            return FileResponse(file_path)
        return Response(status_code=404)

    return middleware
