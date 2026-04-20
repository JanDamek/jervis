"""gRPC server for service-document-extraction.

Exposes DocumentExtractionService.Extract as a unary RPC over h2c. The
FastAPI routes are retired in the same slice; callers (Kotlin server
DocumentExtractionClient, orchestrator chat handler_context) dial this
gRPC server instead.
"""

from __future__ import annotations

import asyncio
import logging
from typing import TYPE_CHECKING

import grpc
from grpc_reflection.v1alpha import reflection

from jervis.document_extraction import extract_pb2, extract_pb2_grpc
from jervis_contracts.interceptors import ServerContextInterceptor

if TYPE_CHECKING:
    pass

logger = logging.getLogger("document_extraction.grpc")


class DocumentExtractionServicer(extract_pb2_grpc.DocumentExtractionServiceServicer):
    """DocumentExtractionService implementation.

    Delegates to the same `DocumentExtractor` instance the FastAPI
    routes used (owned by `app.state.extractor`).
    """

    def __init__(self, extractor):
        self._extractor = extractor

    async def Extract(
        self,
        request: extract_pb2.ExtractRequest,
        context: grpc.aio.ServicerContext,
    ) -> extract_pb2.ExtractResponse:
        if not request.content:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "content required")

        filename = request.filename or "unknown"
        mime = request.mime_type or ""
        tier = request.max_tier or "NONE"

        logger.info(
            "EXTRACT_GRPC: file=%s mime=%s size=%d tier=%s",
            filename, mime, len(request.content), tier,
        )

        try:
            result = await self._extractor.extract(
                bytes(request.content), filename, mime, tier,
            )
        except Exception as e:
            logger.exception("EXTRACT_GRPC_FAILED: file=%s", filename)
            await context.abort(grpc.StatusCode.INTERNAL, f"Extraction failed: {e}")

        metadata = {str(k): str(v) for k, v in (result.metadata or {}).items() if v is not None}
        pages = [
            extract_pb2.ExtractedPage(page_number=int(p.page_number), text=str(p.text or ""))
            for p in (result.pages or [])
        ]
        return extract_pb2.ExtractResponse(
            text=str(result.text or ""),
            method=str(result.method or ""),
            metadata=metadata,
            pages=pages,
        )

    async def Health(
        self,
        request: extract_pb2.HealthRequest,
        context: grpc.aio.ServicerContext,
    ) -> extract_pb2.HealthResponse:
        return extract_pb2.HealthResponse(status="ok", service="document-extraction")


async def start_grpc_server(extractor, port: int = 5501) -> grpc.aio.Server:
    """Start the gRPC server on `port` and return the handle for cleanup."""
    from jervis_contracts.grpc_options import build_server_options

    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=build_server_options(),
    )
    extract_pb2_grpc.add_DocumentExtractionServiceServicer_to_server(
        DocumentExtractionServicer(extractor), server,
    )
    reflection.enable_server_reflection(
        (
            extract_pb2.DESCRIPTOR.services_by_name["DocumentExtractionService"].full_name,
            reflection.SERVICE_NAME,
        ),
        server,
    )

    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info("gRPC document-extraction listening on :%d", port)
    return server
