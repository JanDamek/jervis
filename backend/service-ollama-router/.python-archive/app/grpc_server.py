"""gRPC server for `service-ollama-router`.

Two services on port 5501:
  * `RouterAdminService` — admin / telemetry (model errors, stats,
    rate-limits, tier invalidation). Pure unary RPCs.
  * `RouterInferenceService` — Chat (server-stream), Generate
    (server-stream), Embed (unary). Every internal module that used to
    POST to `/api/chat`, `/api/generate`, `/api/embed`, `/api/embeddings`
    now dials this service. The router still forwards outward to the
    Ollama / OpenRouter vendors over HTTP (egress vendor contract), but
    no REST endpoint is exposed on the router's ingress surface.
"""

from __future__ import annotations

import json
import logging
import time
from typing import AsyncIterator

import grpc
import httpx
from google.protobuf import struct_pb2
from google.protobuf.json_format import MessageToDict, ParseDict
from grpc_reflection.v1alpha import reflection

from jervis.router import admin_pb2, admin_pb2_grpc, inference_pb2, inference_pb2_grpc
from jervis.common import enums_pb2
from jervis_contracts.interceptors import ServerContextInterceptor

from .proxy import PreemptedByCriticalError, PreemptedByWhisperError, ProxyError
from .request_queue import QueueCancelled

logger = logging.getLogger("ollama-router.grpc")


# ── Priority + capability enum mapping ────────────────────────────────

# proto Priority → router internal Priority (IntEnum defined in .models).
# BACKGROUND = scheduled work (NORMAL queue); FOREGROUND = user waiting
# (CRITICAL, preempts); CRITICAL proto = voice cascade (CASCADE, preempts
# even queued CRITICAL).
def _proto_priority_to_internal(value) -> "int":
    from .models import Priority as InternalPriority
    if value == enums_pb2.PRIORITY_FOREGROUND:
        return InternalPriority.CRITICAL
    if value == enums_pb2.PRIORITY_CRITICAL:
        return InternalPriority.CASCADE
    return InternalPriority.NORMAL


_CAPABILITY_PROTO_TO_STR = {
    enums_pb2.CAPABILITY_UNSPECIFIED: "",
    enums_pb2.CAPABILITY_CHAT: "chat",
    enums_pb2.CAPABILITY_THINKING: "thinking",
    enums_pb2.CAPABILITY_CODING: "coding",
    enums_pb2.CAPABILITY_EXTRACTION: "extraction",
    enums_pb2.CAPABILITY_EMBEDDING: "embedding",
    enums_pb2.CAPABILITY_VISUAL: "visual",
}


_TIER_CAP_TO_STR = {
    enums_pb2.TIER_CAP_UNSPECIFIED: "NONE",
    enums_pb2.TIER_CAP_NONE: "NONE",
    enums_pb2.TIER_CAP_T1: "FREE",
    enums_pb2.TIER_CAP_T2: "PAID",
}


class RouterAdminServicer(admin_pb2_grpc.RouterAdminServiceServicer):
    def __init__(self, router):
        # Needed for Whisper/TTS preempt handlers; the catalog / model-stats
        # RPCs below use module-level helpers and don't touch `router`.
        self._router = router

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

    async def WhisperNotify(
        self, request: admin_pb2.WhisperNotifyRequest, context: grpc.aio.ServicerContext
    ) -> admin_pb2.WhisperNotifyResponse:
        """Whisper wants GPU. Preempt every Ollama LLM/VLM, unload models,
        return once VRAM is free (or on timeout). XTTS is untouched."""
        timeout = request.preempt_timeout_s if request.preempt_timeout_s > 0 else 30
        granted, preempted, unloaded = await self._router.notify_whisper_wants_gpu(
            preempt_timeout_s=timeout,
        )
        return admin_pb2.WhisperNotifyResponse(
            granted=granted,
            preempted_count=preempted,
            unloaded_models=unloaded,
        )

    async def WhisperDone(
        self, request: admin_pb2.WhisperDoneRequest, context: grpc.aio.ServicerContext
    ) -> admin_pb2.WhisperDoneResponse:
        """Whisper finished. Preempted requests in the gRPC retry loop
        will now wake up and resubmit."""
        self._router.notify_whisper_done()
        return admin_pb2.WhisperDoneResponse(released=True)

    # TtsNotify / TtsDone handlers were removed together with the
    # LLM-based text normalization path. XTTS coexists with bge-m3 on
    # p40-2 within VRAM budget; no dispatcher hold is required.


class RouterInferenceServicer(inference_pb2_grpc.RouterInferenceServiceServicer):
    """gRPC inference surface for every internal module.

    Chat / Generate are server-streamed; Embed is unary. Each call resolves
    routing policy via `OllamaRouter.dispatch_inference`, which returns
    either an `AsyncIterator[dict]` (streaming) or a `dict` (unary). We
    translate those to proto messages on the way out.
    """

    def __init__(self, router):
        self._router = router  # OllamaRouter instance

    async def Chat(
        self,
        request: inference_pb2.ChatRequest,
        context: grpc.aio.ServicerContext,
    ):
        body = _chat_request_to_body(request)
        try:
            async for chunk in self._dispatch_stream(request.ctx, "/api/chat", body, context):
                yield _ollama_chunk_to_chat(chunk)
        except QueueCancelled as e:
            await context.abort(grpc.StatusCode.CANCELLED, str(e))
        except ProxyError as e:
            await _abort_from_proxy_error(context, e)

    async def Generate(
        self,
        request: inference_pb2.GenerateRequest,
        context: grpc.aio.ServicerContext,
    ):
        body = _generate_request_to_body(request)
        try:
            async for chunk in self._dispatch_stream(request.ctx, "/api/generate", body, context):
                yield _ollama_chunk_to_generate(chunk)
        except QueueCancelled as e:
            await context.abort(grpc.StatusCode.CANCELLED, str(e))
        except ProxyError as e:
            await _abort_from_proxy_error(context, e)

    async def Embed(
        self,
        request: inference_pb2.EmbedRequest,
        context: grpc.aio.ServicerContext,
    ) -> inference_pb2.EmbedResponse:
        body = {"model": request.model_hint or "bge-m3", "input": list(request.inputs)}
        try:
            result = await self._router.dispatch_inference(
                "/api/embed", body,
                capability=_CAPABILITY_PROTO_TO_STR.get(
                    request.ctx.capability, "embedding",
                ) or "embedding",
                client_id=request.ctx.scope.client_id or None,
                intent=request.ctx.intent or "",
                priority=_proto_priority_to_internal(request.ctx.priority),
                deadline_iso=request.ctx.deadline_iso or None,
            )
        except QueueCancelled as e:
            await context.abort(grpc.StatusCode.CANCELLED, str(e))
            raise
        except ProxyError as e:
            await _abort_from_proxy_error(context, e)
            raise

        if not isinstance(result, dict):
            # Unary path should always return dict; if the queue handed us
            # an iterator (misconfig) drain it into a single dict.
            logger.warning("Embed: got iterator instead of dict — draining")
            acc: dict = {}
            async for chunk in result:
                if chunk.get("embeddings"):
                    acc.setdefault("embeddings", []).extend(chunk["embeddings"])
                elif chunk.get("embedding"):
                    acc.setdefault("embeddings", []).append(chunk["embedding"])
                acc["model"] = chunk.get("model", acc.get("model", ""))
            result = acc

        vectors = _extract_embeddings(result)
        return inference_pb2.EmbedResponse(
            embeddings=[
                inference_pb2.Embedding(vector=v) for v in vectors
            ],
            model_used=result.get("model") or request.model_hint,
        )

    async def _dispatch_stream(
        self,
        ctx: "jervis_contracts.common.types_pb2.RequestContext",  # type: ignore
        api_path: str,
        body: dict,
        context: grpc.aio.ServicerContext,
    ) -> AsyncIterator[dict]:
        capability = _CAPABILITY_PROTO_TO_STR.get(ctx.capability, "") or None
        client_id = ctx.scope.client_id or None
        priority = _proto_priority_to_internal(ctx.priority)
        deadline_iso = ctx.deadline_iso or None
        max_tier_override = (
            _TIER_CAP_TO_STR.get(ctx.max_tier)
            if ctx.max_tier != enums_pb2.TIER_CAP_UNSPECIFIED
            else None
        )

        # Whisper-preemption retry loop. If our run on Ollama is cut short
        # because whisper grabbed the GPU, wait for WhisperDone and retry
        # from scratch — the caller sees a single delayed response. We cap
        # the retries so a stuck whisper semaphore can't spin forever.
        MAX_WHISPER_RETRIES = 3
        WHISPER_WAIT_S = 600  # whisper transcriptions rarely exceed 10 min
        MAX_CRITICAL_RETRIES = 5  # a kb-extract may get preempted several times in a burst
        retries = 0
        critical_retries = 0
        while True:
            try:
                result = await self._router.dispatch_inference(
                    api_path, body,
                    capability=capability,
                    client_id=client_id,
                    intent=ctx.intent or "",
                    priority=priority,
                    deadline_iso=deadline_iso,
                    max_tier_override=max_tier_override,
                )
            except QueueCancelled as e:
                await context.abort(grpc.StatusCode.CANCELLED, str(e))
                return
            except PreemptedByWhisperError as e:
                retries += 1
                if retries > MAX_WHISPER_RETRIES:
                    logger.error(
                        "DISPATCH: whisper preemption retry budget exhausted (%d)", retries,
                    )
                    await context.abort(
                        grpc.StatusCode.UNAVAILABLE,
                        "whisper kept preempting after 3 retries",
                    )
                    return
                logger.warning(
                    "DISPATCH: preempted by whisper (emitted=%d), retry %d/%d after WhisperDone",
                    e.emitted_chunks, retries, MAX_WHISPER_RETRIES,
                )
                await self._router.wait_for_whisper_done(timeout=WHISPER_WAIT_S)
                continue
            except PreemptedByCriticalError as e:
                critical_retries += 1
                if critical_retries > MAX_CRITICAL_RETRIES:
                    logger.error(
                        "DISPATCH: critical preemption retry budget exhausted (%d)",
                        critical_retries,
                    )
                    await context.abort(
                        grpc.StatusCode.UNAVAILABLE,
                        "higher-priority traffic kept preempting after 5 retries",
                    )
                    return
                logger.warning(
                    "DISPATCH: preempted by critical (emitted=%d), retry %d/%d — "
                    "re-queueing at original priority",
                    e.emitted_chunks, critical_retries, MAX_CRITICAL_RETRIES,
                )
                # No wait — re-submit immediately. The priority queue will
                # hold this behind the critical burst until the GPU frees.
                continue
            except ProxyError as e:
                await _abort_from_proxy_error(context, e)
                return

            if isinstance(result, dict):
                # Non-streaming upstream — emit a single final chunk.
                yield result
                return

            # Streaming: if whisper preempts mid-stream we'll propagate
            # PreemptedByWhisperError through the iterator and retry from
            # scratch. The caller's view is that the first tokens they saw
            # were bogus, which is the best we can do since Ollama has no
            # resume-from-offset. In practice whisper preemption during an
            # active chat is rare — the user is either typing or dictating,
            # not both.
            try:
                async for chunk in result:
                    yield chunk
                return
            except PreemptedByWhisperError as e:
                retries += 1
                if retries > MAX_WHISPER_RETRIES:
                    logger.error(
                        "DISPATCH: whisper preemption retry budget exhausted mid-stream (%d)",
                        retries,
                    )
                    await context.abort(
                        grpc.StatusCode.UNAVAILABLE,
                        "whisper kept preempting mid-stream",
                    )
                    return
                logger.warning(
                    "DISPATCH: mid-stream preempted by whisper (emitted=%d), retry %d/%d",
                    e.emitted_chunks, retries, MAX_WHISPER_RETRIES,
                )
                await self._router.wait_for_whisper_done(timeout=WHISPER_WAIT_S)
                # loop back to dispatch_inference for a fresh run.
            except PreemptedByCriticalError as e:
                critical_retries += 1
                if critical_retries > MAX_CRITICAL_RETRIES:
                    logger.error(
                        "DISPATCH: critical preemption retry budget exhausted mid-stream (%d)",
                        critical_retries,
                    )
                    await context.abort(
                        grpc.StatusCode.UNAVAILABLE,
                        "higher-priority traffic kept preempting mid-stream",
                    )
                    return
                logger.warning(
                    "DISPATCH: mid-stream preempted by critical (emitted=%d), retry %d/%d",
                    e.emitted_chunks, critical_retries, MAX_CRITICAL_RETRIES,
                )
                # loop back for a fresh run — request goes back into queue.


# ── Proto ↔ Ollama dict helpers ────────────────────────────────────────

def _struct_to_dict(s: struct_pb2.Struct) -> dict:
    if not s or not s.fields:
        return {}
    return MessageToDict(s, preserving_proto_field_name=True)


def _dict_to_struct(d: dict) -> struct_pb2.Struct:
    out = struct_pb2.Struct()
    if d:
        ParseDict(d, out)
    return out


def _chat_request_to_body(req: inference_pb2.ChatRequest) -> dict:
    """Turn a ChatRequest into the Ollama /api/chat JSON body."""
    messages: list[dict] = []
    for m in req.messages:
        msg: dict = {"role": m.role or "user"}
        if m.content:
            msg["content"] = m.content
        if m.name:
            msg["name"] = m.name
        if m.tool_call_id:
            msg["tool_call_id"] = m.tool_call_id
        if m.images:
            # Ollama expects base64 strings.
            import base64
            msg["images"] = [base64.b64encode(b).decode() for b in m.images]
        if m.tool_calls:
            msg["tool_calls"] = [
                {
                    "id": tc.id,
                    "type": "function",
                    "function": {
                        "name": tc.name,
                        "arguments": _struct_to_dict(tc.args),
                    },
                }
                for tc in m.tool_calls
            ]
        messages.append(msg)

    body: dict = {
        "model": req.model_hint or "qwen3-coder-tool:latest",
        "messages": messages,
        "stream": True,
    }
    if req.tools:
        body["tools"] = [
            {
                "type": "function",
                "function": {
                    "name": t.name,
                    "description": t.description,
                    "parameters": _struct_to_dict(t.parameters),
                },
            }
            for t in req.tools
        ]
    opts: dict = {}
    if req.options.temperature:
        opts["temperature"] = req.options.temperature
    if req.options.num_predict:
        opts["num_predict"] = req.options.num_predict
    if req.options.num_ctx:
        opts["num_ctx"] = req.options.num_ctx
    if req.options.top_p:
        opts["top_p"] = req.options.top_p
    if opts:
        body["options"] = opts
    return body


def _generate_request_to_body(req: inference_pb2.GenerateRequest) -> dict:
    body: dict = {
        "model": req.model_hint or "qwen3-vl-tool:latest",
        "prompt": req.prompt,
        "stream": True,
    }
    if req.response_format:
        # "json" forces the model to emit a single valid JSON object.
        # Bypasses qwen3-vl chain-of-thought streams.
        body["format"] = req.response_format
    if req.images:
        import base64
        body["images"] = [base64.b64encode(b).decode() for b in req.images]
    opts: dict = {}
    if req.options.temperature:
        opts["temperature"] = req.options.temperature
    if req.options.num_predict:
        opts["num_predict"] = req.options.num_predict
    if req.options.num_ctx:
        opts["num_ctx"] = req.options.num_ctx
    if req.options.top_p:
        opts["top_p"] = req.options.top_p
    if opts:
        body["options"] = opts
    return body


def _ollama_chunk_to_chat(chunk: dict) -> inference_pb2.ChatChunk:
    msg = chunk.get("message") or {}
    content = msg.get("content", "") or ""
    thinking = msg.get("thinking", "") or ""
    tool_calls_proto: list[inference_pb2.ToolCall] = []
    for tc in msg.get("tool_calls") or []:
        fn = tc.get("function") or {}
        args = fn.get("arguments")
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except Exception:
                args = {}
        tool_calls_proto.append(
            inference_pb2.ToolCall(
                id=tc.get("id", "") or "",
                name=fn.get("name", "") or "",
                args=_dict_to_struct(args or {}),
            )
        )

    out = inference_pb2.ChatChunk(
        content_delta=content,
        thinking_delta=thinking,
        tool_calls=tool_calls_proto,
        done=bool(chunk.get("done", False)),
        finish_reason=chunk.get("done_reason", "") or "",
        prompt_tokens=int(chunk.get("prompt_eval_count", 0) or 0),
        completion_tokens=int(chunk.get("eval_count", 0) or 0),
        model_used=chunk.get("model", "") or "",
    )
    return out


def _ollama_chunk_to_generate(chunk: dict) -> inference_pb2.GenerateChunk:
    # Qwen3-VL-tool and other reasoning models route the actual text
    # through Ollama's `thinking` field (chain-of-thought stream) rather
    # than `response`. We concat both so the semantic content isn't lost
    # — the consumer of Generate wants the final text output, it doesn't
    # care which field Ollama used to transport it.
    delta = (chunk.get("response", "") or "") + (chunk.get("thinking", "") or "")
    return inference_pb2.GenerateChunk(
        response_delta=delta,
        done=bool(chunk.get("done", False)),
        prompt_tokens=int(chunk.get("prompt_eval_count", 0) or 0),
        completion_tokens=int(chunk.get("eval_count", 0) or 0),
        model_used=chunk.get("model", "") or "",
        finish_reason=chunk.get("done_reason", "") or "",
    )


def _extract_embeddings(result: dict) -> list[list[float]]:
    """Ollama returns either {embedding: [...]} or {embeddings: [[...]]}."""
    if "embeddings" in result and isinstance(result["embeddings"], list):
        return [list(v) for v in result["embeddings"]]
    if "embedding" in result and isinstance(result["embedding"], list):
        return [list(result["embedding"])]
    return []


async def _abort_from_proxy_error(
    context: grpc.aio.ServicerContext, err: ProxyError,
) -> None:
    code = grpc.StatusCode.INTERNAL
    if err.reason == "cancelled":
        code = grpc.StatusCode.CANCELLED
    elif err.reason == "backend_unavailable":
        code = grpc.StatusCode.UNAVAILABLE
    elif err.reason == "upstream_error" and err.status_code == 429:
        code = grpc.StatusCode.RESOURCE_EXHAUSTED
    elif err.reason == "upstream_error" and err.status_code and 400 <= err.status_code < 500:
        code = grpc.StatusCode.INVALID_ARGUMENT
    detail = err.message or err.reason
    logger.warning("gRPC inference abort: code=%s reason=%s detail=%s", code, err.reason, detail)
    await context.abort(code, detail)


# ── Server bootstrap ──────────────────────────────────────────────────

async def start_grpc_server(router, port: int = 5501) -> grpc.aio.Server:
    """Start the gRPC server on `port` and return the handle for later cleanup."""
    from jervis_contracts.grpc_options import build_server_options

    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=build_server_options(),
    )
    admin_pb2_grpc.add_RouterAdminServiceServicer_to_server(RouterAdminServicer(router), server)
    inference_pb2_grpc.add_RouterInferenceServiceServicer_to_server(
        RouterInferenceServicer(router), server,
    )

    service_names = (
        admin_pb2.DESCRIPTOR.services_by_name["RouterAdminService"].full_name,
        inference_pb2.DESCRIPTOR.services_by_name["RouterInferenceService"].full_name,
        reflection.SERVICE_NAME,
    )
    reflection.enable_server_reflection(service_names, server)

    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info(
        "gRPC RouterAdminService + RouterInferenceService listening on :%d",
        port,
    )
    return server
