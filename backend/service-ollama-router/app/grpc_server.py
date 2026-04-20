"""gRPC server for `service-ollama-router`.

Hosts every `/router/admin/*` and `/router/internal/*` endpoint on gRPC
port 5501. The transparent Ollama-compatible surface (`/api/generate`,
`/api/chat`, `/api/embeddings`) stays on FastAPI port 11430 because that
is a vendor contract (Ollama API) — consumers dial it as a generic
OpenAI/Ollama client, not via our proto.
"""

from __future__ import annotations

import logging
import time

import grpc
import httpx
from grpc_reflection.v1alpha import reflection

from jervis.router import admin_pb2, admin_pb2_grpc
from jervis.common import enums_pb2
from jervis_contracts.interceptors import ServerContextInterceptor

logger = logging.getLogger("ollama-router.grpc")


_TIER_CAP_TO_STR = {
    enums_pb2.TIER_CAP_UNSPECIFIED: "NONE",
    enums_pb2.TIER_CAP_NONE: "NONE",
    enums_pb2.TIER_CAP_T1: "T1",
    enums_pb2.TIER_CAP_T2: "T2",
}


class RouterAdminServicer(admin_pb2_grpc.RouterAdminServiceServicer):
    async def GetMaxContext(
        self, request: admin_pb2.MaxContextRequest, context: grpc.aio.ServicerContext
    ) -> admin_pb2.MaxContextResponse:
        from .openrouter_catalog import get_max_context_tokens

        tier = request.max_tier if request.max_tier != enums_pb2.TIER_CAP_UNSPECIFIED else request.ctx.max_tier
        max_tier = _TIER_CAP_TO_STR.get(tier, "NONE")
        max_ctx = await get_max_context_tokens(max_tier)
        return admin_pb2.MaxContextResponse(max_context_tokens=int(max_ctx))

    async def ReportModelError(
        self, request: admin_pb2.ReportModelErrorRequest, context: grpc.aio.ServicerContext
    ) -> admin_pb2.ReportModelErrorResponse:
        from .openrouter_catalog import report_model_error, get_model_errors

        just_disabled = report_model_error(request.model_id, request.error_message)
        info = get_model_errors().get(request.model_id, {})
        return admin_pb2.ReportModelErrorResponse(
            model_id=request.model_id,
            disabled=bool(info.get("disabled", False)),
            error_count=int(info.get("count", 0)),
            just_disabled=bool(just_disabled),
        )

    async def ReportModelSuccess(
        self, request: admin_pb2.ReportModelSuccessRequest, context: grpc.aio.ServicerContext
    ) -> admin_pb2.ReportModelSuccessResponse:
        from .openrouter_catalog import report_model_success, record_model_call

        report_model_success(request.model_id)
        if request.duration_s > 0:
            record_model_call(
                request.model_id,
                float(request.duration_s),
                int(request.input_tokens),
                int(request.output_tokens),
            )
        return admin_pb2.ReportModelSuccessResponse(model_id=request.model_id, reset=True)

    async def ListModelErrors(
        self, request: admin_pb2.ListModelErrorsRequest, context: grpc.aio.ServicerContext
    ) -> admin_pb2.ListModelErrorsResponse:
        from .openrouter_catalog import get_model_errors

        errors = get_model_errors()
        items: list[admin_pb2.ModelErrorInfo] = []
        for model_id, payload in errors.items():
            entries = [
                admin_pb2.ModelErrorEntry(
                    message=str(e.get("message", "")),
                    timestamp=float(e.get("timestamp", 0.0)),
                )
                for e in (payload.get("errors") or [])
            ]
            items.append(
                admin_pb2.ModelErrorInfo(
                    model_id=model_id,
                    count=int(payload.get("count", 0)),
                    disabled=bool(payload.get("disabled", False)),
                    entries=entries,
                )
            )
        return admin_pb2.ListModelErrorsResponse(errors=items)

    async def ListModelStats(
        self, request: admin_pb2.ListModelStatsRequest, context: grpc.aio.ServicerContext
    ) -> admin_pb2.ListModelStatsResponse:
        from .openrouter_catalog import get_model_stats

        stats = get_model_stats()
        items = [
            admin_pb2.ModelStatInfo(
                model_id=model_id,
                call_count=int(data.get("call_count", 0)),
                avg_response_s=float(data.get("avg_response_s", 0.0)),
                total_time_s=float(data.get("total_time_s", 0.0)),
                total_input_tokens=int(data.get("total_input_tokens", 0)),
                total_output_tokens=int(data.get("total_output_tokens", 0)),
                tokens_per_s=float(data.get("tokens_per_s", 0.0)),
                last_call=float(data.get("last_call", 0.0)),
            )
            for model_id, data in stats.items()
        ]
        return admin_pb2.ListModelStatsResponse(stats=items)

    async def ResetModelError(
        self, request: admin_pb2.ResetModelErrorRequest, context: grpc.aio.ServicerContext
    ) -> admin_pb2.ResetModelErrorResponse:
        from .openrouter_catalog import reset_model_error

        was_disabled = reset_model_error(request.model_id)
        if was_disabled:
            logger.info("Model %s re-enabled via gRPC", request.model_id)
        return admin_pb2.ResetModelErrorResponse(model_id=request.model_id, re_enabled=bool(was_disabled))

    async def TestModel(
        self, request: admin_pb2.TestModelRequest, context: grpc.aio.ServicerContext
    ) -> admin_pb2.TestModelResponse:
        model_id = request.model_id
        if not model_id:
            return admin_pb2.TestModelResponse(ok=False, model_id="", error="model_id required")

        from .openrouter_catalog import get_api_key

        api_key = await get_api_key()
        if not api_key:
            return admin_pb2.TestModelResponse(
                ok=False, model_id=model_id, error="No OpenRouter API key configured"
            )

        body = {
            "model": model_id,
            "messages": [{"role": "user", "content": "Reply with exactly: OK"}],
            "stream": False,
            "max_tokens": 10,
            "temperature": 0.0,
        }
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "HTTP-Referer": "https://jervis.damek-soft.eu",
            "X-Title": "Jervis AI Assistant",
        }

        start = time.monotonic()
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(connect=10.0, read=30.0, write=10.0, pool=10.0)
            ) as client:
                resp = await client.post(
                    "https://openrouter.ai/api/v1/chat/completions",
                    json=body,
                    headers=headers,
                )
                elapsed_ms = int((time.monotonic() - start) * 1000)

                if resp.status_code != 200:
                    error_text = resp.text[:300]
                    logger.warning("TEST_MODEL: %s returned %d: %s", model_id, resp.status_code, error_text)
                    return admin_pb2.TestModelResponse(
                        ok=False,
                        model_id=model_id,
                        response_ms=elapsed_ms,
                        error=f"HTTP {resp.status_code}: {error_text}",
                    )

                data = resp.json()
                choices = data.get("choices") or []
                first_choice = choices[0] if choices else {}
                message = first_choice.get("message") or {}
                content = (message.get("content") or "")[:100]
                logger.info("TEST_MODEL: %s OK in %dms", model_id, elapsed_ms)
                return admin_pb2.TestModelResponse(
                    ok=True,
                    model_id=model_id,
                    response_ms=elapsed_ms,
                    response_preview=content,
                )
        except Exception as e:
            logger.warning("TEST_MODEL: %s failed — %s", model_id, e)
            return admin_pb2.TestModelResponse(ok=False, model_id=model_id, error=str(e))

    async def GetRateLimits(
        self, request: admin_pb2.RateLimitsRequest, context: grpc.aio.ServicerContext
    ) -> admin_pb2.RateLimitsResponse:
        from .rate_limiter import get_rate_limit_status

        status = get_rate_limit_status() or {}
        items = [
            admin_pb2.QueueRateLimit(
                queue_name=queue_name,
                limit=int(info.get("limit", 0)),
                remaining=int(info.get("remaining", 0)),
                reset_time=float(info.get("reset_time", 0.0)),
            )
            for queue_name, info in status.items()
        ]
        return admin_pb2.RateLimitsResponse(queues=items)

    async def InvalidateClientTier(
        self, request: admin_pb2.InvalidateClientTierRequest, context: grpc.aio.ServicerContext
    ) -> admin_pb2.InvalidateClientTierResponse:
        from .client_tier_cache import invalidate_cache

        client_id = request.client_id or None
        invalidate_cache(client_id)
        return admin_pb2.InvalidateClientTierResponse(invalidated=client_id or "all")


async def start_grpc_server(port: int = 5501) -> grpc.aio.Server:
    """Start the gRPC server on `port` and return the handle for later cleanup."""
    from jervis_contracts.grpc_options import build_server_options

    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=build_server_options(),
    )
    admin_pb2_grpc.add_RouterAdminServiceServicer_to_server(RouterAdminServicer(), server)

    service_names = (
        admin_pb2.DESCRIPTOR.services_by_name["RouterAdminService"].full_name,
        reflection.SERVICE_NAME,
    )
    reflection.enable_server_reflection(service_names, server)

    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info("gRPC RouterAdminService listening on :%d", port)
    return server
