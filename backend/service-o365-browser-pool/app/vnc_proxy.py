"""VNC WebSocket proxy + static file middleware.

Each browser pod serves VNC only for its own connection.
Routing between pods is handled by the centralized VNC router (nginx).
Session cookie format: {connectionId}_{sessionId}
"""

from __future__ import annotations

import asyncio
import logging
import os

from starlette.requests import Request
from starlette.responses import Response, FileResponse
from starlette.websockets import WebSocket, WebSocketDisconnect
from fastapi import APIRouter

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


def create_vnc_proxy_router(vnc_auth: VncAuthManager) -> APIRouter:
    router = APIRouter()

    @router.websocket("/websockify")
    async def websockify_proxy(ws: WebSocket):
        """Proxy WebSocket to local websockify (localhost:6080)."""
        cookie = ws.cookies.get("vnc_session", "")

        if not vnc_auth.is_session_valid(cookie):
            await ws.close(code=4401)
            return

        await ws.accept()
        logger.info("WebSocket VNC proxy: local websockify")

        try:
            import websockets
            async with websockets.connect("ws://127.0.0.1:6080") as remote:

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
    """Middleware that serves noVNC static files with cookie auth."""

    async def middleware(request: Request, call_next):
        path = request.url.path

        # Let routers handle API and WebSocket paths
        if _skip_auth(path) or path == "/websockify":
            return await call_next(request)

        # Check session cookie
        cookie = request.cookies.get("vnc_session", "")
        if not vnc_auth.is_session_valid(cookie):
            return Response(status_code=404)

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
