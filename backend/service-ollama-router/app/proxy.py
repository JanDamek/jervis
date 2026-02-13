"""HTTP proxy layer – streaming and non-streaming proxy to Ollama backends."""

from __future__ import annotations

import json
import logging
from typing import AsyncIterator

import httpx
from starlette.responses import StreamingResponse, JSONResponse, Response

from .config import settings
from .models import TrackedRequest

logger = logging.getLogger("ollama-router.proxy")


def _build_timeout() -> httpx.Timeout:
    return httpx.Timeout(
        connect=settings.proxy_connect_timeout_s,
        read=None,       # No read timeout for streaming (generation can take minutes)
        write=settings.proxy_write_timeout_s,
        pool=30.0,
    )


async def proxy_streaming(
    target_url: str,
    request: TrackedRequest,
) -> StreamingResponse:
    """Proxy a streaming Ollama request. Supports preemption via cancel_event."""

    async def stream_generator() -> AsyncIterator[bytes]:
        client = httpx.AsyncClient(timeout=_build_timeout())
        try:
            async with client.stream(
                "POST",
                f"{target_url}{request.api_path}",
                json=request.body,
            ) as upstream:
                upstream.raise_for_status()
                async for line in upstream.aiter_lines():
                    # Check preemption
                    if request.cancel_event.is_set():
                        logger.info(
                            "Request %s preempted (model=%s)",
                            request.request_id, request.model,
                        )
                        yield (json.dumps({
                            "error": "preempted",
                            "message": "Request preempted by higher priority",
                        }) + "\n").encode()
                        return
                    if line.strip():
                        yield (line + "\n").encode()
        except httpx.RemoteProtocolError:
            if request.cancel_event.is_set():
                yield (json.dumps({"error": "preempted"}) + "\n").encode()
            else:
                raise
        except httpx.HTTPStatusError as e:
            logger.error("Upstream error %s: %s", e.response.status_code, e.response.text[:500])
            yield (json.dumps({
                "error": f"upstream_error",
                "status_code": e.response.status_code,
                "message": e.response.text[:500],
            }) + "\n").encode()
        finally:
            await client.aclose()

    return StreamingResponse(
        stream_generator(),
        media_type="application/x-ndjson",
    )


async def proxy_non_streaming(
    target_url: str,
    request: TrackedRequest,
) -> Response:
    """Proxy a non-streaming Ollama request (embeddings, show, etc.)."""
    async with httpx.AsyncClient(timeout=_build_timeout()) as client:
        try:
            resp = await client.post(
                f"{target_url}{request.api_path}",
                json=request.body,
            )
            return Response(
                content=resp.content,
                status_code=resp.status_code,
                headers=dict(resp.headers),
                media_type=resp.headers.get("content-type", "application/json"),
            )
        except httpx.HTTPStatusError as e:
            return Response(
                content=e.response.content,
                status_code=e.response.status_code,
                media_type="application/json",
            )
        except httpx.ConnectError as e:
            logger.error("Connection failed to %s: %s", target_url, e)
            return JSONResponse(
                status_code=503,
                content={"error": "backend_unavailable", "message": str(e)},
            )


async def proxy_passthrough_get(
    target_url: str,
    path: str,
) -> Response:
    """Proxy a GET request transparently."""
    async with httpx.AsyncClient(timeout=httpx.Timeout(10.0)) as client:
        try:
            resp = await client.get(f"{target_url}{path}")
            return Response(
                content=resp.content,
                status_code=resp.status_code,
                headers=dict(resp.headers),
                media_type=resp.headers.get("content-type", "application/json"),
            )
        except Exception as e:
            return JSONResponse(
                status_code=503,
                content={"error": "backend_unavailable", "message": str(e)},
            )


async def proxy_passthrough_head(target_url: str, path: str) -> Response:
    """Proxy a HEAD request transparently."""
    async with httpx.AsyncClient(timeout=httpx.Timeout(5.0)) as client:
        try:
            resp = await client.head(f"{target_url}{path}")
            return Response(status_code=resp.status_code, headers=dict(resp.headers))
        except Exception:
            return Response(status_code=503)


def is_streaming_request(body: dict) -> bool:
    """Determine if a request body indicates streaming mode."""
    # Ollama defaults stream=true for generate/chat, stream=false for embeddings
    return body.get("stream", True)
