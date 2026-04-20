"""Router-backed LangChain chat model — gRPC RouterInferenceService.

The agent dispatches every LLM call through `jervis-ollama-router:5501`
via gRPC. The server-side service (`RouterInferenceService.Chat`) owns
the routing policy (local GPU vs cloud OpenRouter, model selection, queue
priority, tier resolution). We translate LangChain messages + bound tool
schemas to proto and drain the server stream into a single AIMessage —
LangChain's `_agenerate` is unary, so we accumulate chunks internally.

No HTTP. No `/api/chat` POST. No custom wire format.
"""

from __future__ import annotations

import json
import logging
from typing import Any, Iterator, List, Optional

from google.protobuf import struct_pb2
from google.protobuf.json_format import MessageToDict, ParseDict
from langchain_core.callbacks import AsyncCallbackManagerForLLMRun, CallbackManagerForLLMRun
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.runnables import Runnable
from langchain_core.tools import BaseTool
from langchain_core.utils.function_calling import convert_to_openai_tool

from app.grpc_clients import router_inference_stub
from jervis.common import enums_pb2, types_pb2
from jervis.router import inference_pb2
from jervis_contracts.interceptors import prepare_context

logger = logging.getLogger("o365-browser-pool.llm")


_CAPABILITY_TO_ENUM = {
    "chat": enums_pb2.CAPABILITY_CHAT,
    "thinking": enums_pb2.CAPABILITY_THINKING,
    "coding": enums_pb2.CAPABILITY_CODING,
    "extraction": enums_pb2.CAPABILITY_EXTRACTION,
    "embedding": enums_pb2.CAPABILITY_EMBEDDING,
    "visual": enums_pb2.CAPABILITY_VISUAL,
    "vision": enums_pb2.CAPABILITY_VISUAL,
}


def _dict_to_struct(d: dict) -> struct_pb2.Struct:
    out = struct_pb2.Struct()
    if d:
        ParseDict(d, out)
    return out


def _struct_to_dict(s: struct_pb2.Struct) -> dict:
    if not s or not s.fields:
        return {}
    return MessageToDict(s, preserving_proto_field_name=True)


def _message_to_proto(msg: BaseMessage) -> inference_pb2.ChatMessage:
    if isinstance(msg, SystemMessage):
        return inference_pb2.ChatMessage(role="system", content=msg.content or "")
    if isinstance(msg, HumanMessage):
        return inference_pb2.ChatMessage(role="user", content=msg.content or "")
    if isinstance(msg, AIMessage):
        tc_list: list[inference_pb2.ToolCall] = []
        for i, t in enumerate(getattr(msg, "tool_calls", None) or []):
            args = t.get("args") or {}
            tc_list.append(
                inference_pb2.ToolCall(
                    id=t.get("id") or f"call_{i}",
                    name=t.get("name", ""),
                    args=_dict_to_struct(args if isinstance(args, dict) else {}),
                )
            )
        return inference_pb2.ChatMessage(
            role="assistant",
            content=msg.content or "",
            tool_calls=tc_list,
        )
    if isinstance(msg, ToolMessage):
        content = msg.content if isinstance(msg.content, str) else json.dumps(
            msg.content, default=str,
        )
        return inference_pb2.ChatMessage(
            role="tool",
            content=content,
            tool_call_id=msg.tool_call_id or "",
        )
    return inference_pb2.ChatMessage(role="user", content=str(msg.content))


def _tool_to_proto(t: dict) -> inference_pb2.Tool:
    """Accepts an OpenAI-style tool schema (`{type, function: {name, description,
    parameters}}`) — the format produced by `convert_to_openai_tool`.
    """
    fn = t.get("function") or {}
    return inference_pb2.Tool(
        name=fn.get("name", ""),
        description=fn.get("description", ""),
        parameters=_dict_to_struct(fn.get("parameters") or {}),
    )


class RouterChatModel(BaseChatModel):
    """LangChain chat model backed by RouterInferenceService.Chat.

    Attributes:
        client_id: passed via RequestContext.scope.client_id — lets the
            router resolve tier + quota from CloudModelPolicy.
        capability: one of chat/thinking/coding/extraction/visual. Default
            "chat" maps to the tool-calling LLM.
        processing_mode: BACKGROUND | FOREGROUND — influences queue
            priority (router treats BACKGROUND as NORMAL, FOREGROUND as
            CRITICAL). Pod default is BACKGROUND.
        temperature: 0 → deterministic.
        max_tokens: soft cap on completion tokens.
    """

    client_id: str
    capability: str = "chat"
    processing_mode: str = "BACKGROUND"
    temperature: float = 0.0
    max_tokens: int = 4096
    intent: str = ""

    @property
    def _llm_type(self) -> str:
        return "jervis-router-chat-grpc"

    def bind_tools(
        self,
        tools: list,
        *,
        tool_choice: Any | None = None,
        **kwargs: Any,
    ) -> Runnable[list[BaseMessage], BaseMessage]:
        """Serialize each tool to the OpenAI function schema and bind via
        LangChain's standard `.bind()`. The resulting list is read back in
        `_agenerate` from kwargs['tools'].
        """
        formatted = [convert_to_openai_tool(t) for t in tools]
        bind_kwargs: dict[str, Any] = {"tools": formatted}
        if tool_choice is not None:
            bind_kwargs["tool_choice"] = tool_choice
        return self.bind(**bind_kwargs, **kwargs)

    def _build_request(
        self,
        messages: list[BaseMessage],
        tools: list | None,
    ) -> inference_pb2.ChatRequest:
        ctx = types_pb2.RequestContext(
            scope=types_pb2.Scope(client_id=self.client_id),
            priority=(
                enums_pb2.PRIORITY_FOREGROUND
                if self.processing_mode == "FOREGROUND"
                else enums_pb2.PRIORITY_BACKGROUND
            ),
            capability=_CAPABILITY_TO_ENUM.get(
                self.capability.lower(), enums_pb2.CAPABILITY_CHAT,
            ),
            intent=self.intent or "",
        )
        prepare_context(ctx)

        proto_messages = [_message_to_proto(m) for m in messages]
        proto_tools = [_tool_to_proto(t) for t in (tools or [])]

        return inference_pb2.ChatRequest(
            ctx=ctx,
            model_hint=(
                "qwen3-vl-tool:latest" if self.capability == "visual"
                else "qwen3-coder-tool:latest"
            ),
            messages=proto_messages,
            tools=proto_tools,
            options=inference_pb2.ChatOptions(
                temperature=self.temperature,
                num_predict=self.max_tokens,
            ),
        )

    async def _agenerate(
        self,
        messages: List[BaseMessage],
        stop: Optional[List[str]] = None,
        run_manager: Optional[AsyncCallbackManagerForLLMRun] = None,
        **kwargs: Any,
    ) -> ChatResult:
        tools = kwargs.get("tools") or []
        request = self._build_request(messages, tools)

        stub = router_inference_stub()
        content_parts: list[str] = []
        thinking_parts: list[str] = []
        # OpenAI-style streaming splits a single tool_call across chunks:
        # chunk N may carry only `id` + `name`, chunk N+1 only `args`. We
        # accumulate by id when present, else by position — the model emits
        # tool_calls in order, so the Kth tool_call across chunks targets
        # slot K in the assembled list.
        tool_call_slots: list[dict] = []
        id_to_slot: dict[str, int] = {}
        model_used = ""
        finish_reason = ""
        prompt_tokens = 0
        completion_tokens = 0

        async for chunk in stub.Chat(request):
            if chunk.model_used and not model_used:
                model_used = chunk.model_used
            if chunk.content_delta:
                content_parts.append(chunk.content_delta)
            if chunk.thinking_delta:
                thinking_parts.append(chunk.thinking_delta)
            for idx_in_chunk, tc in enumerate(chunk.tool_calls):
                args_delta = _struct_to_dict(tc.args)
                if tc.id and tc.id in id_to_slot:
                    slot = tool_call_slots[id_to_slot[tc.id]]
                elif tc.id:
                    slot = {"id": tc.id, "name": "", "args": {}, "type": "tool_call"}
                    id_to_slot[tc.id] = len(tool_call_slots)
                    tool_call_slots.append(slot)
                elif idx_in_chunk < len(tool_call_slots):
                    slot = tool_call_slots[idx_in_chunk]
                else:
                    slot = {"id": "", "name": "", "args": {}, "type": "tool_call"}
                    tool_call_slots.append(slot)
                if tc.name:
                    slot["name"] = tc.name
                if args_delta:
                    slot["args"].update(args_delta)
            if chunk.done:
                finish_reason = chunk.finish_reason or finish_reason
                prompt_tokens = int(chunk.prompt_tokens) or prompt_tokens
                completion_tokens = int(chunk.completion_tokens) or completion_tokens

        # Drop any empty slots that ended up without a name (malformed upstream).
        tool_calls_by_id = {
            (slot["id"] or f"tc_{i}"): slot
            for i, slot in enumerate(tool_call_slots)
            if slot["name"]
        }

        full_content = "".join(content_parts)
        additional_kwargs: dict[str, Any] = {}
        if thinking_parts:
            additional_kwargs["thinking"] = "".join(thinking_parts)
        if model_used:
            additional_kwargs["model_used"] = model_used
        if finish_reason:
            additional_kwargs["finish_reason"] = finish_reason

        reply = AIMessage(
            content=full_content,
            tool_calls=list(tool_calls_by_id.values()),
            additional_kwargs=additional_kwargs,
            usage_metadata={
                "input_tokens": prompt_tokens,
                "output_tokens": completion_tokens,
                "total_tokens": prompt_tokens + completion_tokens,
            } if (prompt_tokens or completion_tokens) else None,
        )
        return ChatResult(generations=[ChatGeneration(message=reply)])

    def _generate(
        self,
        messages: List[BaseMessage],
        stop: Optional[List[str]] = None,
        run_manager: Optional[CallbackManagerForLLMRun] = None,
        **kwargs: Any,
    ) -> ChatResult:
        import asyncio
        return asyncio.get_event_loop().run_until_complete(
            self._agenerate(messages, stop, None, **kwargs)
        )

    def _stream(self, *args, **kwargs) -> Iterator:
        raise NotImplementedError(
            "Use _agenerate — streaming is folded into a single AIMessage "
            "by the Chat stub consumer.",
        )
