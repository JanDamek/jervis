"""SSE handler — foreground chat entry point via Paměťová mapa.

Thin entry-point that routes chat messages through the memory map:
1. Load context + Paměťová mapa
2. Route via chat_router → ChatRoute
3. Execute: direct_response / new_vertex / resume_vertex / answer_ask_user
4. Record REQUEST vertex + cleanup

Delegates to:
- chat.handler_context: runtime context loading, message building
- chat.handler_streaming: LLM calls, token streaming, message saving
- chat.handler_agentic: main agentic loop (until full merge into vertex_executor)
- chat.tools: tool definitions
"""
from __future__ import annotations

import asyncio
import logging
from typing import AsyncIterator

from app.agent.chat_router import route_chat_message
from app.chat.context import chat_context_assembler
from app.chat.handler_agentic import run_agentic_loop
from app.chat.handler_context import load_runtime_context, build_messages, load_task_context_message
from app.chat.handler_streaming import call_llm, stream_text, save_assistant_message
from app.chat.models import ChatRequest, ChatStreamEvent
from app.chat.system_prompt import build_system_prompt
from app.chat.tools import CHAT_TOOLS
from app.models import ModelTier

logger = logging.getLogger(__name__)


async def handle_chat_sse(
    request: ChatRequest,
    disconnect_event: asyncio.Event | None = None,
) -> AsyncIterator[ChatStreamEvent]:
    """Route a chat message through Paměťová mapa and stream response.

    Replaces the old chat/handler.py with simpler routing:
    - No intent classification (all tools always available)
    - No long message decomposition (agent handles itself)
    - Vertex recorded in memory map for every interaction
    """
    try:
        # ── 1. Load context ──────────────────────────────────────────
        context = await chat_context_assembler.assemble_context(
            conversation_id=request.session_id,
        )
        runtime_ctx = await load_runtime_context()

        # ── 2. Load Paměťová mapa ───────────────────────────────────
        map_ctx = ""
        memory_map = None
        try:
            from app.agent.persistence import agent_store
            from app.agent.graph import memory_map_summary
            memory_map = await agent_store.get_or_create_memory_map()
            map_ctx = memory_map_summary(memory_map, max_tokens=2000)
        except Exception as e:
            logger.warning("SSE: failed to load memory map: %s", e)

        # ── 3. Route ────────────────────────────────────────────────
        route = route_chat_message(
            message=request.message,
            memory_map=memory_map,
            context_task_id=request.context_task_id,
            client_id=request.active_client_id,
            project_id=request.active_project_id,
        )
        logger.info("SSE: route=%s vertex=%s reason=%s",
                     route.action, route.vertex_id, route.reason)

        # ── 4a. Direct response (greeting) ──────────────────────────
        if route.action == "direct_response":
            system_prompt = await build_system_prompt(
                active_client_id=request.active_client_id,
                active_project_id=request.active_project_id,
                runtime_context=runtime_ctx,
                session_id=request.session_id,
            )
            messages = build_messages(system_prompt, context, None, request.message)
            try:
                resp = await call_llm(messages=messages, tier=ModelTier.LOCAL_COMPACT)
                text = resp.choices[0].message.content or ""
                if text.strip():
                    await save_assistant_message(
                        request.session_id, text, {"direct_answer": "true"},
                    )
                    async for event in stream_text(text):
                        yield event
                    yield ChatStreamEvent(type="done", metadata={
                        "direct_answer": True, "iterations": 0,
                    })
                    return
            except Exception as e:
                logger.warning("SSE: direct response failed (%s), falling through", e)

        # ── 4b. Build messages for agentic path ─────────────────────
        task_context_msg = None
        if request.context_task_id:
            task_context_msg = await load_task_context_message(request.context_task_id)

        system_prompt = await build_system_prompt(
            active_client_id=request.active_client_id,
            active_project_id=request.active_project_id,
            active_client_name=request.active_client_name,
            active_project_name=request.active_project_name,
            runtime_context=runtime_ctx,
            session_id=request.session_id,
        )
        messages = build_messages(system_prompt, context, task_context_msg, request.message)

        # Inject memory map summary
        if map_ctx:
            messages.insert(1, {
                "role": "system",
                "content": (
                    "## Paměťová mapa (current state)\n"
                    f"{map_ctx}\n\n"
                    "Use check_task_graph to inspect task details. "
                    "Use answer_blocked_vertex to respond to blocked questions."
                ),
            })

        # Inject route-specific hints
        if route.action == "answer_ask_user" and route.vertex_id:
            messages.append({
                "role": "system",
                "content": (
                    f"[ROUTE] Uživatel odpovídá na otázku z ASK_USER vertexu {route.vertex_id}. "
                    f"Zavolej answer_blocked_vertex s vertex_id={route.vertex_id}."
                ),
            })
        elif route.action == "resume_vertex" and route.vertex_id:
            messages.append({
                "role": "system",
                "content": (
                    f"[ROUTE] Existuje aktivní vertex {route.vertex_id} pro tento scope. "
                    f"Zvaž zda zpráva souvisí s probíhající prací."
                ),
            })

        yield ChatStreamEvent(type="thinking", content="Připravuji odpověď...")

        # ── 4c. Agentic loop (all tools) ────────────────────────────
        async for event in run_agentic_loop(
            request=request,
            messages=messages,
            selected_tools=CHAT_TOOLS,
            runtime_ctx=runtime_ctx,
            disconnect_event=disconnect_event,
            is_summarized=False,
            msg_len=len(request.message),
        ):
            yield event

    except Exception as e:
        logger.exception("SSE handler error: %s", e)
        import traceback
        tb = traceback.format_exception(type(e), e, e.__traceback__)
        yield ChatStreamEvent(
            type="error",
            content=str(e),
            metadata={
                "error": str(e),
                "errorType": type(e).__name__,
                "traceback": "".join(tb[-3:]),
            },
        )

    finally:
        # Record REQUEST vertex in Paměťová mapa
        try:
            from app.agent.persistence import agent_store
            from app.agent.graph import add_request_vertex
            master = agent_store.get_memory_map_cached()
            if master:
                add_request_vertex(
                    master,
                    message=request.message[:200],
                    response="(streamed)",
                    response_summary=request.message[:80],
                    client_id=request.active_client_id or "",
                    client_name=request.active_client_name or "",
                    project_id=request.active_project_id,
                    project_name=request.active_project_name or "",
                )
                agent_store.mark_dirty(master.task_id)
        except Exception as e:
            logger.debug("Failed to record vertex in memory map: %s", e)

        # Cleanup session auto-approvals
        try:
            from app.chat.handler_agentic import _session_auto_approvals
            _session_auto_approvals.pop(request.session_id, None)
        except Exception:
            pass
        try:
            from app.tools.kotlin_client import kotlin_client
            await kotlin_client.register_foreground_end()
        except Exception as e:
            logger.warning("Failed to register foreground end: %s", e)
