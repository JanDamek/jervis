"""noVNC reverse proxy with cookie-based authentication.

Serves noVNC static files and proxies WebSocket to local websockify,
with cookie authentication on all VNC routes. All traffic goes through
FastAPI on port 8090 — websockify on localhost:6080 is internal only.
"""

from __future__ import annotations

import asyncio
import logging
import os

import aiohttp
from fastapi import APIRouter, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, Response
from starlette.websockets import WebSocketState

from app.vnc_auth import VncAuthManager

logger = logging.getLogger("o365-browser-pool.vnc-proxy")

NOVNC_DIR = "/usr/share/novnc"

# Paths that skip VNC cookie auth (internal API or public entry)
_SKIP_AUTH_PREFIXES = (
    "/health", "/ready",
    "/session/", "/token/",
    "/vnc-login", "/vnc-token/", "/vnc-auth",
    "/graph/",    # Graph API proxy — internal K8s service calls
    "/scrape/",   # VLM scrape endpoints — internal K8s service calls
    "/meeting/",  # Meeting join/stop/sessions — internal K8s service calls
)


def _skip_auth(path: str) -> bool:
    return any(path.startswith(p) for p in _SKIP_AUTH_PREFIXES)


def create_vnc_proxy_router(vnc_auth: VncAuthManager) -> APIRouter:
    router = APIRouter()

    @router.websocket("/websockify")
    async def websockify_proxy(ws: WebSocket):
        """Proxy WebSocket to local websockify (localhost:6080) with auth."""
        cookie = ws.cookies.get("vnc_session", "")
        if not vnc_auth.is_session_valid(cookie):
            await ws.close(code=1008, reason="Unauthorized")
            return

        await ws.accept()

        session = aiohttp.ClientSession()
        try:
            backend = await session.ws_connect(
                "ws://127.0.0.1:6080",
                protocols=["binary"],
                max_msg_size=0,
            )

            async def client_to_backend():
                try:
                    while True:
                        data = await ws.receive_bytes()
                        await backend.send_bytes(data)
                except (WebSocketDisconnect, Exception):
                    pass

            async def backend_to_client():
                try:
                    async for msg in backend:
                        if ws.client_state != WebSocketState.CONNECTED:
                            break
                        if msg.type == aiohttp.WSMsgType.BINARY:
                            await ws.send_bytes(msg.data)
                        elif msg.type == aiohttp.WSMsgType.TEXT:
                            await ws.send_text(msg.data)
                        elif msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.ERROR):
                            break
                except Exception:
                    pass

            tasks = [
                asyncio.create_task(client_to_backend()),
                asyncio.create_task(backend_to_client()),
            ]
            _, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for t in pending:
                t.cancel()

            await backend.close()
        except Exception as e:
            logger.warning("WebSocket proxy error: %s", e)
            if ws.client_state == WebSocketState.CONNECTED:
                await ws.close(code=1011)
        finally:
            await session.close()

    return router


def create_vnc_static_middleware(vnc_auth: VncAuthManager):
    """Middleware that serves noVNC static files with cookie auth.

    API paths and WebSocket are handled by routers (bypass this middleware).
    Everything else is treated as a noVNC static file request.
    """

    async def middleware(request: Request, call_next):
        path = request.url.path

        # Let routers handle API and WebSocket paths
        if _skip_auth(path) or path == "/websockify":
            return await call_next(request)

        # Check cookie for noVNC static files — return 404 (not 401) for security
        cookie = request.cookies.get("vnc_session", "")
        if not vnc_auth.is_session_valid(cookie):
            return Response(status_code=404)

        # Serve noVNC static file
        if path in ("/", ""):
            file_path = os.path.join(NOVNC_DIR, "vnc.html")
        else:
            # Prevent path traversal
            clean = path.lstrip("/").replace("..", "")
            file_path = os.path.join(NOVNC_DIR, clean)

        if os.path.isfile(file_path):
            return FileResponse(file_path)
        return Response(status_code=404)

    return middleware
