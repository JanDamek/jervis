"""HTTP proxy layer — streaming and non-streaming forward to Ollama backends.

The router's *external* surface is gRPC (RouterInferenceService). This
module is called from the queue dispatch path and returns parsed dict
chunks — never Starlette responses — so the gRPC servicer can translate
them to proto without a REST intermediate. Ollama's native API is HTTP,
so outbound calls to the GPU backends remain HTTP (vendor contract).
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import AsyncIterator, Callable

import httpx

from .config import settings
from .models import TrackedRequest

logger = logging.getLogger("ollama-router.proxy")


class ProxyError(RuntimeError):
    """Raised from proxy calls when the upstream GPU cannot serve the
    request. Carries a short reason and, when available, the upstream
    HTTP status code. The gRPC servicer maps these to gRPC Status codes.
    """

    def __init__(self, reason: str, *, status_code: int | None = None, message: str = "") -> None:
        super().__init__(reason)
        self.reason = reason
        self.status_code = status_code
        self.message = message


def _build_timeout() -> httpx.Timeout:
    return httpx.Timeout(
        connect=settings.proxy_connect_timeout_s,
        read=None,       # streaming generation can take minutes
        write=settings.proxy_write_timeout_s,
        pool=30.0,
    )


async def proxy_streaming(
    target_url: str,
    request: TrackedRequest,
    on_connect_error: Callable[[str], None] | None = None,
) -> AsyncIterator[dict]:
    """Stream NDJSON chunks from an Ollama backend, yielding parsed dicts.

    Raises ProxyError on terminal failures. Preemption (cancel_event set
    during an active stream) ends the iteration cleanly — the consumer
    sees StopAsyncIteration and can react via gRPC context cancellation.
    """

    client = httpx.AsyncClient(timeout=_build_timeout())
    preempted = False
    try:
        async with client.stream(
            "POST",
            f"{target_url}{request.api_path}",
            json=request.body,
        ) as upstream:
            if upstream.status_code != 200:
                body = (await upstream.aread()).decode("utf-8", errors="replace")
                raise ProxyError(
                    "upstream_error",
                    status_code=upstream.status_code,
                    message=body[:500],
                )
            async for line in upstream.aiter_lines():
                if request.cancel_event.is_set():
                    preempted = True
                    logger.warning(
                        "PROXY_STREAM: id=%s PREEMPTED (model=%s)",
                        request.request_id, request.model,
                    )
                    return
                if not line.strip():
                    continue
                try:
                    yield json.loads(line)
                except json.JSONDecodeError:
                    logger.debug("PROXY_STREAM: non-JSON line: %s", line[:120])
            logger.info("PROXY_STREAM: id=%s completed", request.request_id)
    except httpx.RemoteProtocolError:
        if request.cancel_event.is_set():
            preempted = True
            logger.warning(
                "PROXY_STREAM: id=%s PREEMPTED (protocol error)",
                request.request_id,
            )
            return
        logger.error("PROXY_STREAM: id=%s protocol error", request.request_id)
        raise ProxyError("protocol_error") from None
    except httpx.ConnectError as e:
        logger.error(
            "PROXY_STREAM: id=%s connection failed to %s: %s",
            request.request_id, target_url, e,
        )
        if on_connect_error:
            on_connect_error(str(e))
        raise ProxyError("backend_unavailable", message=str(e)) from e
    finally:
        await client.aclose()
        if preempted:
            logger.info("PROXY_STREAM: id=%s exit (preempted)", request.request_id)


async def proxy_non_streaming(
    target_url: str,
    request: TrackedRequest,
    on_connect_error: Callable[[str], None] | None = None,
) -> dict:
    """Forward a non-streaming Ollama request (embeddings, show, …).

    Supports cancellation via `request.cancel_event`. Returns the parsed
    JSON response dict; raises ProxyError on failure.
    """
    async with httpx.AsyncClient(timeout=_build_timeout()) as client:
        try:
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
                raise ProxyError("cancelled")

            resp = post_task.result()
            logger.info(
                "PROXY_NON_STREAM: id=%s status=%s",
                request.request_id, resp.status_code,
            )
            if resp.status_code != 200:
                raise ProxyError(
                    "upstream_error",
                    status_code=resp.status_code,
                    message=resp.text[:500],
                )
            return resp.json()
        except httpx.HTTPStatusError as e:
            logger.error(
                "PROXY_NON_STREAM: id=%s upstream error status=%s",
                request.request_id, e.response.status_code,
            )
            raise ProxyError(
                "upstream_error",
                status_code=e.response.status_code,
                message=e.response.text[:500],
            ) from e
        except httpx.ConnectError as e:
            logger.error(
                "PROXY_NON_STREAM: id=%s connection failed to %s: %s",
                request.request_id, target_url, e,
            )
            if on_connect_error:
                on_connect_error(str(e))
            raise ProxyError("backend_unavailable", message=str(e)) from e


async def _wait_for_cancel(cancel_event: asyncio.Event) -> None:
    await cancel_event.wait()


async def proxy_passthrough_get(target_url: str, path: str):
    """Proxy a GET request transparently. Used by FastAPI ops-only endpoints
    (/api/tags, /api/ps, /) that forward to a GPU backend."""
    from starlette.responses import Response, JSONResponse
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


async def proxy_passthrough_head(target_url: str, path: str):
    """Proxy a HEAD request transparently."""
    from starlette.responses import Response
    async with httpx.AsyncClient(timeout=httpx.Timeout(5.0)) as client:
        try:
            resp = await client.head(f"{target_url}{path}")
            return Response(status_code=resp.status_code, headers=dict(resp.headers))
        except Exception:
            return Response(status_code=503)


def is_streaming_request(body: dict) -> bool:
    """Ollama defaults stream=true for generate/chat, stream=false for embeddings."""
    return body.get("stream", True)
