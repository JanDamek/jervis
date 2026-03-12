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
    _response_chunks: list[str] = []
    _trace_parts: list[str] = []
    _live_vertex_id: str | None = None
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
            active_cid = request.active_client_id or ""
            if not active_cid:
                logger.warning("SSE: no active_client_id — memory map summary will be empty")
            map_ctx = memory_map_summary(
                memory_map, max_tokens=2000,
                client_id=active_cid,
                project_id=request.active_project_id or "",
            )
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
            messages = build_messages(system_prompt, context, None, request.message,
                                     attachments=request.attachments or None)
            try:
                resp = await call_llm(messages=messages, tier=ModelTier.LOCAL_COMPACT)
                text = resp.choices[0].message.content or ""
                if text.strip():
                    _response_chunks.append(text)
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
        messages = build_messages(system_prompt, context, task_context_msg, request.message,
                                  attachments=request.attachments or None)

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

        # ── 4b'. Create RUNNING vertex in memory map immediately ───
        if memory_map and request.active_client_id:
            try:
                from app.agent.graph import add_request_vertex
                from app.agent.models import VertexStatus
                _live_vertex = add_request_vertex(
                    memory_map,
                    message=request.message[:200],
                    response="",
                    response_summary="Zpracovávám…",
                    client_id=request.active_client_id,
                    client_name=request.active_client_name or "",
                    group_id=request.active_group_id,
                    group_name=request.active_group_name or "",
                    project_id=request.active_project_id,
                    project_name=request.active_project_name or "",
                    status=VertexStatus.RUNNING,
                )
                _live_vertex_id = _live_vertex.id
                agent_store.mark_dirty(memory_map.task_id)
                from app.tools.kotlin_client import kotlin_client
                await kotlin_client.notify_memory_map_changed()
            except Exception as e:
                logger.debug("SSE: failed to create live vertex: %s", e)

        # ── 4c. Agentic loop (all tools) ────────────────────────────
        _memory_map_id = memory_map.task_id if memory_map else None
        async for event in run_agentic_loop(
            request=request,
            messages=messages,
            selected_tools=CHAT_TOOLS,
            runtime_ctx=runtime_ctx,
            disconnect_event=disconnect_event,
            is_summarized=False,
            msg_len=len(request.message),
        ):
            if event.type in ("content", "token") and event.content:
                _response_chunks.append(event.content)
            elif event.type == "thinking" and event.content:
                _trace_parts.append(f"[thinking] {event.content}")
            elif event.type == "tool_call" and event.content:
                args_str = event.metadata.get("args", "")
                _trace_parts.append(f"[tool] {event.content}({str(args_str)[:100]})")
            elif event.type == "tool_result" and event.content:
                tool = event.metadata.get("tool", "?")
                _trace_parts.append(f"[result:{tool}] {event.content[:150]}")
            # Inject memory map + vertex IDs into done event for UI
            if event.type == "done":
                if _memory_map_id:
                    event.metadata["memory_map_id"] = _memory_map_id
                if _live_vertex_id:
                    event.metadata["memory_map_vertex_id"] = _live_vertex_id
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
        # Update REQUEST vertex in Paměťová mapa with final state
        try:
            from app.agent.persistence import agent_store
            from app.agent.models import VertexStatus
            from datetime import datetime, timezone
            master = agent_store.get_memory_map_cached()
            if master:
                _full_response = "".join(_response_chunks) if _response_chunks else ""
                _trace_str = "\n".join(_trace_parts) if _trace_parts else ""
                _full_record = (_trace_str + "\n---\n" + _full_response) if _trace_str else _full_response

                # Determine vertex status from trace analysis
                _has_errors = any(
                    ("Error:" in p or "Chyba:" in p or "error:" in p)
                    for p in _trace_parts if p.startswith("[result:")
                )
                _bg_tools = {"create_background_task", "dispatch_coding_agent"}
                _has_bg_dispatch = any(
                    any(t in p for t in _bg_tools)
                    for p in _trace_parts if p.startswith("[tool]")
                ) and not _has_errors

                if _has_errors:
                    _vertex_status = VertexStatus.FAILED
                elif _has_bg_dispatch:
                    _vertex_status = VertexStatus.RUNNING
                else:
                    _vertex_status = VertexStatus.COMPLETED

                # Update existing live vertex if created, otherwise create new
                if _live_vertex_id and _live_vertex_id in master.vertices:
                    v = master.vertices[_live_vertex_id]
                    v.result = _full_record[:2000] if _full_record else "(no response)"
                    v.result_summary = _full_response[:120] if _full_response else request.message[:80]
                    v.status = _vertex_status
                    if _vertex_status != VertexStatus.RUNNING:
                        v.completed_at = datetime.now(timezone.utc).isoformat()
                elif request.active_client_id:
                    from app.agent.graph import add_request_vertex
                    add_request_vertex(
                        master,
                        message=request.message[:200],
                        response=_full_record[:2000] if _full_record else "(no response)",
                        response_summary=_full_response[:120] if _full_response else request.message[:80],
                        client_id=request.active_client_id,
                        client_name=request.active_client_name or "",
                        group_id=request.active_group_id,
                        group_name=request.active_group_name or "",
                        project_id=request.active_project_id,
                        project_name=request.active_project_name or "",
                        status=_vertex_status,
                    )
                else:
                    logger.warning("SSE: skipping fallback vertex — no active_client_id")
                agent_store.mark_dirty(master.task_id)
                from app.tools.kotlin_client import kotlin_client
                try:
                    await kotlin_client.notify_memory_map_changed()
                except Exception:
                    pass
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
