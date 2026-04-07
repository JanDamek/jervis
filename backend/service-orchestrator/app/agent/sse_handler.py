"""SSE handler — foreground chat entry point via Paměťový graf.

Thin entry-point that routes chat messages through the memory graph:
1. Load context + Paměťový graf
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
from app.chat.tools import CHAT_INITIAL_TOOLS
from app.models import ModelTier

logger = logging.getLogger(__name__)


async def handle_chat_sse(
    request: ChatRequest,
    disconnect_event: asyncio.Event | None = None,
) -> AsyncIterator[ChatStreamEvent]:
    """Route a chat message through Paměťový graf and stream response.

    Replaces the old chat/handler.py with simpler routing:
    - No intent classification (all tools always available)
    - No long message decomposition (agent handles itself)
    - Vertex recorded in memory graph for every interaction
    """
    _response_chunks: list[str] = []
    _trace_parts: list[str] = []
    _live_vertex_id: str | None = None
    try:
        # ── 1. Load context ──────────────────────────────────────────
        # FREE tier: 24k context budget (was 10k — too small, dropped older
        # messages after just 2 exchanges). With memory ~2k + summaries cap 30%
        # this leaves ~15k for verbatim recent messages = ~10-15 exchanges.
        max_tier = getattr(request, "max_openrouter_tier", "FREE")
        context_budget = 24_000 if max_tier in ("FREE", "NONE") else None  # None = default
        # Exclude the just-saved current user message — Kotlin persists it
        # before forwarding to Python, so loading it would cause build_messages
        # to duplicate the current turn.
        current_seq = getattr(request, "message_sequence", None)
        context = await chat_context_assembler.assemble_context(
            conversation_id=request.session_id,
            exclude_sequence=current_seq,
            **({"context_budget": context_budget} if context_budget else {}),
        )
        runtime_ctx = await load_runtime_context(
            query=request.message,
            client_id=request.active_client_id or "",
            project_id=request.active_project_id,
            group_id=getattr(request, "active_group_id", None),
        )

        # User timezone from client device (fallback to last known from server)
        user_timezone = request.client_timezone
        if not user_timezone:
            from app.tools.kotlin_client import kotlin_client
            user_timezone = await kotlin_client.get_user_timezone()

        # ── 2. Load Paměťový graf ────────────────────────────────────
        map_ctx = ""
        memory_graph = None
        try:
            from app.agent.persistence import agent_store
            from app.agent.graph import memory_graph_summary
            memory_graph = await agent_store.get_or_create_memory_graph()
            active_cid = request.active_client_id or ""
            if not active_cid:
                logger.warning("SSE: no active_client_id — memory graph summary will be empty")
            map_ctx = memory_graph_summary(
                memory_graph, max_tokens=2000,
                client_id=active_cid,
                project_id=request.active_project_id or "",
            )
        except Exception as e:
            logger.warning("SSE: failed to load memory graph: %s", e)

        # ── 3. Route ────────────────────────────────────────────────
        route = route_chat_message(
            message=request.message,
            memory_graph=memory_graph,
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
                user_timezone=user_timezone,
            )
            messages = await build_messages(system_prompt, context, None, request.message,
                                           attachments=request.attachments or None,
                                           client_id=request.active_client_id,
                                           project_id=request.active_project_id)
            try:
                # Route greeting via OpenRouter when client has cloud tier
                greeting_max_tier = getattr(request, "max_openrouter_tier", "NONE") or "NONE"
                greeting_route = None
                greeting_tier = ModelTier.LOCAL_COMPACT
                if greeting_max_tier != "NONE":
                    from app.llm.router_client import route_request as _route_req
                    greeting_route = await _route_req(
                        capability="chat", max_tier=greeting_max_tier, estimated_tokens=2000,
                    )
                resp = await call_llm(
                    messages=messages, tier=greeting_tier,
                    route=greeting_route, max_tier=greeting_max_tier,
                )
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

        max_tier = getattr(request, "max_openrouter_tier", "FREE")
        system_prompt = await build_system_prompt(
            active_client_id=request.active_client_id,
            active_project_id=request.active_project_id,
            active_client_name=request.active_client_name,
            active_project_name=request.active_project_name,
            runtime_context=runtime_ctx,
            session_id=request.session_id,
            user_timezone=user_timezone,
            model_tier=max_tier,
        )
        messages = await build_messages(system_prompt, context, task_context_msg, request.message,
                                        attachments=request.attachments or None,
                                        client_id=request.active_client_id,
                                        project_id=request.active_project_id)

        # Inject memory graph summary
        if map_ctx:
            messages.insert(1, {
                "role": "system",
                "content": (
                    "## Paměťový graf (current state)\n"
                    f"{map_ctx}\n\n"
                    "Use check_task_graph to inspect task details. "
                    "Use answer_blocked_vertex to respond to blocked questions."
                ),
            })

        # Inject multi-context hints (detect mentions of multiple clients/projects)
        try:
            from app.chat.multi_context import detect_multi_context
            mc_result = detect_multi_context(
                message=request.message,
                clients_projects=runtime_ctx.clients_projects,
                active_client_id=request.active_client_id,
                active_project_id=request.active_project_id,
            )
            if mc_result.has_multiple_contexts and mc_result.hint_message:
                messages.append({
                    "role": "system",
                    "content": mc_result.hint_message,
                })
        except Exception as e:
            logger.debug("SSE: multi-context detection failed (non-blocking): %s", e)

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

        # ── Emit Thought Map context to UI ──────────────────────────
        if runtime_ctx.thought_context and runtime_ctx.activated_thought_ids:
            yield ChatStreamEvent(
                type="thought_context",
                content=runtime_ctx.thought_context,
                metadata={
                    "activated_thought_ids": ",".join(runtime_ctx.activated_thought_ids),
                    "activated_edge_ids": ",".join(runtime_ctx.activated_edge_ids),
                },
            )

        yield ChatStreamEvent(type="thinking", content="Připravuji odpověď...")

        # ── 4b'. Create RUNNING vertex in memory graph immediately ──
        if memory_graph and request.active_client_id:
            try:
                from app.agent.graph import add_request_vertex
                from app.agent.models import VertexStatus
                _live_vertex = add_request_vertex(
                    memory_graph,
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
                agent_store.mark_dirty(memory_graph.task_id)
                from app.tools.kotlin_client import kotlin_client
                await kotlin_client.notify_memory_graph_changed()
            except Exception as e:
                logger.debug("SSE: failed to create live vertex: %s", e)

        # ── 4c. Try foreground graph decomposition ────────────────
        _memory_graph_id = memory_graph.task_id if memory_graph else None
        _used_graph_decomposition = False

        try:
            from app.chat.chat_decomposer import detect_and_decompose
            # Build brief conversation summary for decomposer context
            _conv_summary = ""
            if context.messages:
                # Take last 3 messages as brief context (assistant + user messages)
                _recent = [m for m in context.messages[-6:] if m.get("role") in ("user", "assistant")]
                _conv_summary = "\n".join(
                    f"[{m['role']}]: {str(m.get('content', ''))[:300]}"
                    for m in _recent[-4:]
                )
            decomp = await detect_and_decompose(
                user_message=request.message,
                conversation_summary=_conv_summary,
                client_id=request.active_client_id or "",
                project_id=request.active_project_id or "",
                max_openrouter_tier=getattr(request, "max_openrouter_tier", "NONE") or "NONE",
            )
            if decomp.should_decompose and decomp.graph:
                _used_graph_decomposition = True
                logger.info(
                    "SSE: using graph decomposition (%d subtasks): %s",
                    len(decomp.subtasks or []), decomp.reason,
                )
                yield ChatStreamEvent(
                    type="thinking",
                    content=f"Rozděluji na {len(decomp.subtasks or [])} paralelních úkolů...",
                )
                async for event in _run_foreground_graph(
                    decomp.graph, request, _response_chunks, _trace_parts,
                    _memory_graph_id, _live_vertex_id,
                ):
                    yield event
        except Exception as e:
            logger.warning("SSE: graph decomposition failed (%s), falling through to agentic loop", e)
            _used_graph_decomposition = False

        # ── 4d. Agentic loop (fallback or default) ────────────────
        if not _used_graph_decomposition:
            async for event in run_agentic_loop(
                request=request,
                messages=messages,
                selected_tools=list(CHAT_INITIAL_TOOLS),
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
                # Inject memory graph + vertex IDs into done event for UI
                if event.type == "done":
                    if _memory_graph_id:
                        event.metadata["memory_graph_id"] = _memory_graph_id
                    if _live_vertex_id:
                        event.metadata["memory_graph_vertex_id"] = _live_vertex_id
                    # Signal to UI: only show inline map if background tasks were dispatched
                    _bg_tools_check = {"create_background_task", "dispatch_coding_agent"}
                    if any(any(t in p for t in _bg_tools_check) for p in _trace_parts if p.startswith("[tool]")):
                        event.metadata["dispatched_tasks"] = "true"
                    # Post-response Thought Map update (fire-and-forget)
                    if runtime_ctx.activated_thought_ids or runtime_ctx.activated_edge_ids:
                        import asyncio as _aio
                        from app.kb.thought_update import reinforce_activated_thoughts, extract_and_store_response_thoughts
                        full_response = "".join(_response_chunks)
                        _aio.create_task(reinforce_activated_thoughts(
                            runtime_ctx.activated_thought_ids, runtime_ctx.activated_edge_ids,
                        ))
                        if full_response:
                            _aio.create_task(extract_and_store_response_thoughts(
                                full_response,
                                client_id=request.active_client_id or "",
                                project_id=request.active_project_id,
                                group_id=getattr(request, "active_group_id", None),
                            ))
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
        # Update REQUEST vertex in Paměťový graf with final state
        try:
            from app.agent.persistence import agent_store
            from app.agent.models import VertexStatus
            from datetime import datetime, timezone
            master = agent_store.get_memory_graph_cached()
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
                    v.result = _full_record or "(no response)"
                    v.result_summary = _full_response or request.message
                    v.status = _vertex_status
                    if _vertex_status != VertexStatus.RUNNING:
                        v.completed_at = datetime.now(timezone.utc).isoformat()
                elif request.active_client_id:
                    from app.agent.graph import add_request_vertex
                    add_request_vertex(
                        master,
                        message=request.message,
                        response=_full_record or "(no response)",
                        response_summary=_full_response or request.message,
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
                    await kotlin_client.notify_memory_graph_changed()
                except Exception:
                    pass
        except Exception as e:
            logger.debug("Failed to record vertex in memory graph: %s", e)

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


# ---------------------------------------------------------------------------
# Foreground graph execution — runs decomposed graph and streams results
# ---------------------------------------------------------------------------


async def _run_foreground_graph(
    graph: "AgentGraph",
    request: ChatRequest,
    response_chunks: list[str],
    trace_parts: list[str],
    memory_graph_id: str | None,
    live_vertex_id: str | None,
) -> AsyncIterator[ChatStreamEvent]:
    """Execute a decomposed graph in foreground and stream the final result.

    Runs the LangGraph agent runner with the pre-built graph, then streams
    the synthesized result back to the user via SSE events.

    Each vertex runs with minimal context (~5k tokens) instead of the full
    conversation history (54k+ tokens), making GPU inference much faster.
    """
    from app.agent.langgraph_runner import (
        _get_compiled_graph,
        node_dispatch_vertex,
        node_select_next,
        node_synthesize,
        GraphAgentState,
    )
    from app.agent.models import AgentGraph, GraphStatus, VertexStatus
    from app.agent.persistence import agent_store

    # Cache graph in store for vertex executor access
    agent_store.cache_subgraph(graph)
    agent_store.mark_dirty(graph.task_id)

    # Build rules with max_openrouter_tier from request
    max_tier = getattr(request, "max_openrouter_tier", "NONE") or "NONE"

    # Simple loop: select_next → dispatch → repeat → synthesize
    # We don't use the full LangGraph compiled graph here to avoid
    # checkpointer overhead for ephemeral foreground graphs.
    state: GraphAgentState = {
        "task": {
            "id": graph.task_id,
            "query": graph.vertices[graph.root_vertex_id].input_request,
            "client_id": request.active_client_id or "",
            "project_id": request.active_project_id or "",
            "client_name": request.active_client_name or "",
            "project_name": request.active_project_name or "",
            "message": request.message,
            "workspace_path": "",
        },
        "rules": {
            "max_openrouter_tier": max_tier,
        },
        "task_graph": graph.model_dump(),
        "current_vertex_id": None,
        "ready_vertex_ids": [],
        "graph_error": None,
        "final_result": None,
        "response_language": "cs",
        "allow_cloud_prompt": False,
        "processing_mode": "FOREGROUND",
    }

    max_rounds = 20  # Safety ceiling
    for round_num in range(max_rounds):
        # Select next ready vertices
        select_result = await node_select_next(state)
        state.update(select_result)

        ready_ids = state.get("ready_vertex_ids", [])
        if not ready_ids:
            break

        # Report progress
        ready_titles = []
        g = AgentGraph(**state["task_graph"])
        for vid in ready_ids:
            v = g.vertices.get(vid)
            if v:
                ready_titles.append(v.title)

        yield ChatStreamEvent(
            type="thinking",
            content=f"Zpracovávám: {', '.join(ready_titles[:5])}{'...' if len(ready_titles) > 5 else ''}",
        )
        trace_parts.append(f"[graph] round {round_num + 1}: {len(ready_ids)} vertices: {', '.join(ready_titles[:5])}")

        # Dispatch all ready vertices (parallel execution)
        dispatch_result = await node_dispatch_vertex(state)
        state.update(dispatch_result)

        # Report completed vertices
        g = AgentGraph(**state["task_graph"])
        for vid in ready_ids:
            v = g.vertices.get(vid)
            if v and v.status == VertexStatus.COMPLETED:
                summary = (v.result_summary or "")[:100]
                trace_parts.append(f"[graph:done] {v.title}: {summary}")
            elif v and v.status == VertexStatus.FAILED:
                trace_parts.append(f"[graph:fail] {v.title}: {v.error or 'unknown'}")

    # Synthesize final result
    synth_result = await node_synthesize(state)
    state.update(synth_result)

    final_text = state.get("final_result", "")
    if final_text:
        response_chunks.append(final_text)
        # Save to conversation history
        from app.chat.handler_streaming import save_assistant_message
        await save_assistant_message(
            request.session_id, final_text,
            {"graph_decomposed": "true", "vertices": str(len(graph.vertices))},
        )
        # Stream the result
        from app.chat.handler_streaming import stream_text
        async for event in stream_text(final_text):
            yield event

    # Stats
    g = AgentGraph(**state["task_graph"])
    completed = sum(1 for v in g.vertices.values() if v.status == VertexStatus.COMPLETED)
    failed = sum(1 for v in g.vertices.values() if v.status == VertexStatus.FAILED)
    logger.info(
        "FOREGROUND_GRAPH_DONE | task=%s | vertices=%d | completed=%d | failed=%d | result_len=%d",
        graph.task_id, len(g.vertices), completed, failed, len(final_text),
    )
    trace_parts.append(f"[graph] done: {completed}/{len(g.vertices)} completed, {failed} failed")

    yield ChatStreamEvent(
        type="done",
        metadata={
            "graph_decomposed": True,
            "vertices": len(g.vertices),
            "completed": completed,
            "failed": failed,
            **({"memory_graph_id": memory_graph_id} if memory_graph_id else {}),
            **({"memory_graph_vertex_id": live_vertex_id} if live_vertex_id else {}),
        },
    )
