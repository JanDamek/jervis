"""Router-backed LangChain chat model.

Dispatches through `jervis-ollama-router`:
  1. POST /router/admin/decide → { target, model, api_base, api_key? }
  2. If target=openrouter → POST OpenRouter /v1/chat/completions
     If target=local     → POST {api_base}/api/chat (Ollama native)
  3. Normalize reply to LangChain AIMessage.

No `langchain-ollama`. No LiteLLM. Same pattern as
`backend/service-orchestrator/app/llm/provider.py`.
"""

from __future__ import annotations

import json
import logging
from typing import Any, Iterator, List, Optional

import httpx
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

from app.config import settings

logger = logging.getLogger("o365-browser-pool.llm")


def _message_to_dict(msg: BaseMessage, *, ollama_native: bool) -> dict:
    """Convert a LangChain message to wire format.

    Ollama native `/api/chat` and OpenAI `/v1/chat/completions` mostly agree,
    but differ on:
      - Ollama assistant messages carrying tool_calls: Ollama's validator
        rejects `id` + `type` wrappers on each tool call → emit only
        `function: { name, arguments }` (arguments as dict, not stringified).
      - Ollama tool messages: no `tool_call_id`, no `name` — Ollama matches
        tool replies positionally with the preceding tool_calls array.
    OpenAI (OpenRouter) expects the full `id`/`type` wrappers and
    `tool_call_id`.
    """
    if isinstance(msg, SystemMessage):
        return {"role": "system", "content": msg.content}
    if isinstance(msg, HumanMessage):
        return {"role": "user", "content": msg.content}
    if isinstance(msg, AIMessage):
        tc = getattr(msg, "tool_calls", None) or []
        base: dict[str, Any] = {"role": "assistant", "content": msg.content or ""}
        if tc:
            if ollama_native:
                base["tool_calls"] = [
                    {"function": {
                        "name": t["name"],
                        "arguments": t.get("args") or {},
                    }}
                    for t in tc
                ]
            else:
                base["tool_calls"] = [
                    {
                        "id": t.get("id") or f"call_{i}",
                        "type": "function",
                        "function": {
                            "name": t["name"],
                            "arguments": json.dumps(t.get("args") or {}),
                        },
                    }
                    for i, t in enumerate(tc)
                ]
        return base
    if isinstance(msg, ToolMessage):
        content = msg.content if isinstance(msg.content, str) else json.dumps(msg.content, default=str)
        if ollama_native:
            return {"role": "tool", "content": content}
        return {
            "role": "tool",
            "content": content,
            "tool_call_id": msg.tool_call_id,
        }
    return {"role": "user", "content": str(msg.content)}


def _parse_ai_reply(data: dict) -> AIMessage:
    """Turn an Ollama /api/chat or OpenAI chat response into AIMessage."""
    # Ollama shape
    if "message" in data and "choices" not in data:
        msg = data["message"] or {}
        content = msg.get("content", "") or ""
        tool_calls = []
        for i, tc in enumerate(msg.get("tool_calls") or []):
            fn = tc.get("function") or {}
            args = fn.get("arguments")
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except Exception:
                    args = {"_raw": args}
            tool_calls.append({
                "id": tc.get("id") or f"call_{i}",
                "name": fn.get("name", ""),
                "args": args or {},
                "type": "tool_call",
            })
        return AIMessage(content=content, tool_calls=tool_calls)

    # OpenAI shape (OpenRouter)
    choice = (data.get("choices") or [{}])[0]
    msg = choice.get("message") or {}
    content = msg.get("content", "") or ""
    tool_calls = []
    for i, tc in enumerate(msg.get("tool_calls") or []):
        fn = tc.get("function") or {}
        raw_args = fn.get("arguments")
        try:
            args = json.loads(raw_args) if isinstance(raw_args, str) else (raw_args or {})
        except Exception:
            args = {"_raw": raw_args}
        tool_calls.append({
            "id": tc.get("id") or f"call_{i}",
            "name": fn.get("name", ""),
            "args": args,
            "type": "tool_call",
        })
    return AIMessage(content=content, tool_calls=tool_calls)


class RouterChatModel(BaseChatModel):
    """LangChain chat model that goes through jervis-ollama-router.

    Attributes:
        client_id: passed to /router/admin/decide so the router can resolve tier.
        capability: one of chat/thinking/coding/extraction/visual. Default "chat"
            which in pod context means tool-calling LLM (qwen3-coder-tool).
        processing_mode: BACKGROUND | INTERACTIVE | BATCH — influences queue
            priority. BACKGROUND is the pod default.
        temperature: 0 → deterministic.
        max_tokens: soft cap for completion tokens.
    """

    client_id: str
    capability: str = "chat"
    processing_mode: str = "BACKGROUND"
    temperature: float = 0.0
    max_tokens: int = 4096

    @property
    def _llm_type(self) -> str:
        return "jervis-router-chat"

    def bind_tools(
        self,
        tools: list,
        *,
        tool_choice: Any | None = None,
        **kwargs: Any,
    ) -> Runnable[list[BaseMessage], BaseMessage]:
        """Bind OpenAI-style tool schemas so the router/Ollama sees them on /api/chat.

        Accepts `@tool`-decorated functions, Pydantic models, or raw dicts.
        Converts each to the OpenAI function-tool schema via
        `convert_to_openai_tool`, then wraps this model with them via the
        standard `Runnable.bind` mechanism. `_agenerate` reads them from
        kwargs['tools'].
        """
        formatted = [convert_to_openai_tool(t) for t in tools]
        bind_kwargs: dict[str, Any] = {"tools": formatted}
        if tool_choice is not None:
            bind_kwargs["tool_choice"] = tool_choice
        return self.bind(**bind_kwargs, **kwargs)

    async def _decide(self, estimated_tokens: int = 0) -> dict:
        """Synthetic pre-dispatch hint — the router is the single source of
        truth (no separate /router/admin/decide endpoint). We always target
        the router's /api/chat with the model hint; the router picks local
        vs cloud internally based on capability + client tier. Mirrors
        service-orchestrator/app/llm/router_client.route_request().
        """
        model = "qwen3-vl-tool:latest" if self.capability == "visual" else "qwen3-coder-tool:latest"
        return {
            "target": "local",
            "model": model,
            "api_base": settings.ollama_router_url,
        }

    async def _dispatch(self, route: dict, messages: list[dict], tools: list | None) -> AIMessage:
        target = route.get("target", "local")
        model = route.get("model", "qwen3-coder-tool:latest")
        api_base = route.get("api_base", settings.ollama_router_url)

        if target == "openrouter":
            api_key = route.get("api_key", settings.openrouter_api_key)
            if not api_key:
                raise RuntimeError("OpenRouter selected but no api_key returned")
            payload = {
                "model": model,
                "messages": messages,
                "stream": False,
                "temperature": self.temperature,
                "max_tokens": self.max_tokens,
            }
            if tools:
                payload["tools"] = tools
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(connect=10, read=None, write=10, pool=30),
            ) as client:
                resp = await client.post(
                    "https://openrouter.ai/api/v1/chat/completions",
                    headers={
                        "Authorization": f"Bearer {api_key}",
                        "HTTP-Referer": "https://jervis.app",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                )
                resp.raise_for_status()
                return _parse_ai_reply(resp.json())

        # local Ollama
        payload = {
            "model": model,
            "messages": messages,
            "stream": False,
            "options": {"temperature": self.temperature},
        }
        if tools:
            payload["tools"] = tools
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(connect=10, read=None, write=10, pool=30),
        ) as client:
            resp = await client.post(f"{api_base}/api/chat", json=payload)
            resp.raise_for_status()
            return _parse_ai_reply(resp.json())

    async def _agenerate(
        self,
        messages: List[BaseMessage],
        stop: Optional[List[str]] = None,
        run_manager: Optional[AsyncCallbackManagerForLLMRun] = None,
        **kwargs: Any,
    ) -> ChatResult:
        tools = kwargs.get("tools") or []
        # Peek the router's first decision so we know whether to wire-format
        # as Ollama native or OpenAI (OpenRouter). Passing messages twice is
        # fine — the estimate only influences queue bucket, not messages shape.
        rough_chars = sum(len(getattr(m, "content", "") or "") for m in messages)
        estimated = rough_chars // 4 + 512
        route = await self._decide(estimated)
        ollama_native = route.get("target", "local") != "openrouter"
        wire_messages = [_message_to_dict(m, ollama_native=ollama_native) for m in messages]
        reply = await self._dispatch(route, wire_messages, tools or None)
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
        raise NotImplementedError("Use _agenerate — streaming not exposed by the router yet.")
