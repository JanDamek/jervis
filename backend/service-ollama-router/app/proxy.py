"""HTTP proxy layer – streaming and non-streaming proxy to Ollama backends."""

from __future__ import annotations

import asyncio
import json
import logging
from typing import AsyncIterator, Callable

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
    on_connect_error: Callable[[str], None] | None = None,
) -> StreamingResponse:
    """Proxy a streaming Ollama request. Supports preemption via cancel_event."""

    async def stream_generator() -> AsyncIterator[bytes]:
        client = httpx.AsyncClient(timeout=_build_timeout())
        preempted = False
        error_occurred = False
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
                        preempted = True
                        logger.warning(
                            "PROXY_STREAM: id=%s PREEMPTED (model=%s)",
                            request.request_id, request.model,
                        )
                        yield (json.dumps({
                            "error": "preempted",
                            "message": "Request preempted by higher priority",
                        }) + "\n").encode()
                        return
                    if line.strip():
                        yield (line + "\n").encode()
                # Stream completed successfully
                logger.info(
                    "PROXY_STREAM: id=%s completed successfully",
                    request.request_id,
                )
        except httpx.RemoteProtocolError:
            if request.cancel_event.is_set():
                preempted = True
                logger.warning(
                    "PROXY_STREAM: id=%s PREEMPTED (protocol error)",
                    request.request_id,
                )
                yield (json.dumps({"error": "preempted"}) + "\n").encode()
            else:
                error_occurred = True
                logger.error(
                    "PROXY_STREAM: id=%s protocol error",
                    request.request_id,
                )
                raise
        except httpx.ConnectError as e:
            error_occurred = True
            logger.error(
                "PROXY_STREAM: id=%s connection failed to %s: %s",
                request.request_id, target_url, e,
            )
            if on_connect_error:
                on_connect_error(str(e))
            yield (json.dumps({
                "error": "backend_unavailable",
                "message": str(e),
            }) + "\n").encode()
        except httpx.HTTPStatusError as e:
            error_occurred = True
            logger.error(
                "PROXY_STREAM: id=%s upstream error status=%s",
                request.request_id, e.response.status_code,
            )
            yield (json.dumps({
                "error": f"upstream_error",
                "status_code": e.response.status_code,
                "message": e.response.text[:500],
            }) + "\n").encode()
        finally:
            await client.aclose()
            if not preempted and not error_occurred:
                logger.info("PROXY_STREAM: id=%s finished", request.request_id)

    return StreamingResponse(
        stream_generator(),
        media_type="application/x-ndjson",
    )


async def proxy_non_streaming(
    target_url: str,
    request: TrackedRequest,
    on_connect_error: Callable[[str], None] | None = None,
) -> Response:
    """Proxy a non-streaming Ollama request (embeddings, show, etc.).

    Supports cancellation via request.cancel_event — if the caller disconnects
    or a CRITICAL preemption fires, the upstream request is cancelled to avoid
    zombie requests consuming GPU time.
    """
    async with httpx.AsyncClient(timeout=_build_timeout()) as client:
        try:
            # Race the actual request against the cancel event
            post_task = asyncio.create_task(
                client.post(
                    f"{target_url}{request.api_path}",
                    json=request.body,
                )
            )
            cancel_task = asyncio.create_task(_wait_for_cancel(request.cancel_event))

            done, pending = await asyncio.wait(
                [post_task, cancel_task],
                return_when=asyncio.FIRST_COMPLETED,
            )

            for task in pending:
                task.cancel()
                try:
                    await task
                except (asyncio.CancelledError, Exception):
                    pass

            if cancel_task in done:
                logger.warning(
                    "PROXY_NON_STREAM: id=%s CANCELLED (client disconnect or preemption)",
                    request.request_id,
                )
                return JSONResponse(
                    status_code=499,
                    content={"error": "cancelled", "message": "Request cancelled"},
                )

            resp = post_task.result()
            logger.info(
                "PROXY_NON_STREAM: id=%s status=%s",
                request.request_id, resp.status_code,
            )
            return Response(
                content=resp.content,
                status_code=resp.status_code,
                headers=dict(resp.headers),
                media_type=resp.headers.get("content-type", "application/json"),
            )
        except httpx.HTTPStatusError as e:
            logger.error(
                "PROXY_NON_STREAM: id=%s upstream error status=%s",
                request.request_id, e.response.status_code,
            )
            return Response(
                content=e.response.content,
                status_code=e.response.status_code,
                media_type="application/json",
            )
        except httpx.ConnectError as e:
            logger.error(
                "PROXY_NON_STREAM: id=%s connection failed to %s: %s",
                request.request_id, target_url, e,
            )
            if on_connect_error:
                on_connect_error(str(e))
            return JSONResponse(
                status_code=503,
                content={"error": "backend_unavailable", "message": str(e)},
            )


async def _wait_for_cancel(cancel_event: asyncio.Event) -> None:
    """Wait until the cancel event is set."""
    await cancel_event.wait()


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
