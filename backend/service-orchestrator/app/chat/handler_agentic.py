"""Agentic loop for chat handler.

Responsibilities:
- Run the main agentic loop (LLM → tools → iterate)
- Tool execution within the loop (with scope_change events)
- Focus reminders between iterations

Drift detection: app.chat.drift (shared with handler_decompose).
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import AsyncIterator

from bson import ObjectId

from app.chat.drift import detect_drift
from app.chat.handler_fact_check import run_fact_check, fact_check_metadata, confidence_badge
from app.chat.handler_streaming import call_llm, stream_text, save_assistant_message
from app.chat.source_attribution import SourceTracker
from app.chat.topic_tracker import detect_topics, update_conversation_topics, topic_metadata
from app.llm.router_client import route_request
from app.chat.handler_tools import (
    extract_tool_calls,
    describe_tool_call,
    execute_chat_tool,
    resolve_switch_context,
    resolve_client_name,
    resolve_project_name,
    resolve_client_projects_json,
)
from app.chat.models import ChatRequest, ChatStreamEvent
from app.chat.system_prompt import RuntimeContext
from app.chat.tools import TOOL_DOMAINS
from app.config import settings, estimate_tokens
from app.llm.provider import llm_provider
from app.models import ModelTier
from app.tools.executor import ApprovalRequiredInterrupt

logger = logging.getLogger(__name__)

# Pending approval futures: session_id → asyncio.Future
_pending_approvals: dict[str, asyncio.Future] = {}
# Session-level auto-approved actions (from "approve always"): session_id → set of action names
_session_auto_approvals: dict[str, set[str]] = {}


def resolve_pending_approval(session_id: str, approved: bool, always: bool = False, action: str | None = None):
    """Called from /chat/approve endpoint to resolve a pending approval."""
    future = _pending_approvals.get(session_id)
    if future and not future.done():
        if always and action:
            auto = _session_auto_approvals.setdefault(session_id, set())
            auto.add(action)
        future.set_result({"approved": approved, "always": always})
    else:
        logger.warning("No pending approval for session %s", session_id)


def _estimate_tokens_total(messages: list[dict], tools: list[dict]) -> int:
    """Estimate total token count for routing decisions."""
    message_tokens = sum(estimate_tokens(str(m)) for m in messages)
    tools_tokens = sum(estimate_tokens(str(t)) for t in tools)
    return message_tokens + tools_tokens + settings.default_output_tokens


# ---------------------------------------------------------------------------
# Main agentic loop
# ---------------------------------------------------------------------------


async def run_agentic_loop(
    request: ChatRequest,
    messages: list[dict],
    selected_tools: list[dict],
    runtime_ctx: RuntimeContext,
    disconnect_event: asyncio.Event | None,
    is_summarized: bool,
    msg_len: int,
    max_iterations_override: int | None = None,
    use_case_override: str | None = None,
) -> AsyncIterator[ChatStreamEvent]:
    """Run the main agentic loop: LLM → extract tools → execute → iterate.

    Yields ChatStreamEvent objects for SSE streaming.

    Args:
        max_iterations_override: Override max iterations (from intent router).
        use_case_override: Override use_case for model routing (e.g., "chat_cloud").
    """
    if max_iterations_override is not None:
        effective_max_iterations = max_iterations_override
    else:
        effective_max_iterations = settings.chat_max_iterations_long if msg_len > settings.decompose_threshold else settings.chat_max_iterations

    created_tasks: list[dict] = []
    responded_tasks: list[str] = []
    used_tools: list[str] = []
    tool_summaries: list[str] = []
    last_tool_sig: str | None = None
    consecutive_same = 0
    domain_history: list[set[str]] = []
    distinct_tools_used: set[str] = set()
    tool_call_history: list[tuple[str, str]] = []
    tool_result_cache: dict[str, str] = {}  # "tool_name:args_json" → result
    source_tracker = SourceTracker()  # EPIC 14-S2: Track KB sources for attribution
    effective_client_id = request.active_client_id
    effective_project_id = request.active_project_id

    for iteration in range(effective_max_iterations):
        # Check disconnect between iterations
        if disconnect_event and disconnect_event.is_set():
            logger.info("Chat: stopped by disconnect after %d iterations", iteration)
            partial = _build_interrupted_content(tool_summaries)
            if partial:
                await save_assistant_message(request.session_id, partial, {"interrupted": "true"}, compress=False)
            yield ChatStreamEvent(type="done", metadata={"interrupted": True})
            return

        logger.info("Chat: iteration %d/%d", iteration + 1, effective_max_iterations)

        tier = ModelTier.LOCAL_STANDARD  # Fixed 48k
        estimated = _estimate_tokens_total(messages, selected_tools)

        # Capability-based routing: ask router for decision
        max_tier = getattr(request, "max_openrouter_tier", "NONE")
        route = await route_request(
            capability="chat",
            max_tier=max_tier,
            estimated_tokens=estimated,
        )
        logger.info("Chat: estimated_tokens=%d → tier=%s, route=%s/%s (max_tier=%s)",
                     estimated, tier.value, route.target, route.model or tier.value, max_tier)

        # Preempt background only on first iteration AND only when using local GPU
        if iteration == 0 and route.target == "local":
            try:
                from app.tools.kotlin_client import kotlin_client
                await kotlin_client.register_foreground_start()
            except Exception as e:
                logger.warning("Failed to register foreground start: %s", e)

        response = await call_llm(messages=messages, tier=tier, tools=selected_tools, route=route)

        choice = response.choices[0]
        tool_calls, remaining_text = extract_tool_calls(choice.message)

        # No tool calls → final text response
        if not tool_calls:
            final_text = remaining_text or choice.message.content or ""
            logger.info("Chat: final answer after %d iterations (%d chars)", iteration + 1, len(final_text))

            # EPIC 14-S1: Fact-check post-processing
            fc_result = await run_fact_check(final_text, effective_client_id, effective_project_id)

            # EPIC 9-S1: Topic tracking
            topics = await detect_topics(request.message, final_text, used_tools)
            await update_conversation_topics(request.session_id, topics)

            # Save to DB BEFORE streaming so messages survive window switch
            await save_assistant_message(
                request.session_id, final_text,
                {
                    **({"used_tools": ",".join(used_tools)} if used_tools else {}),
                    **({"created_tasks": ",".join(str(t.get("title", "")) for t in created_tasks)} if created_tasks else {}),
                    **({"responded_tasks": ",".join(responded_tasks)} if responded_tasks else {}),
                    **({"summarized": "true", "original_length": str(msg_len)} if is_summarized else {}),
                    **fact_check_metadata(fc_result),
                    **topic_metadata(topics),
                    **source_tracker.build_metadata(),
                },
            )

            async for event in stream_text(final_text):
                yield event

            yield ChatStreamEvent(type="done", metadata={
                "created_tasks": created_tasks, "responded_tasks": responded_tasks,
                "used_tools": used_tools, "iterations": iteration + 1,
                **confidence_badge(fc_result),
                **topic_metadata(topics),
                **source_tracker.build_done_metadata(),
            })
            return

        # --- Drift detection ---
        tool_sig = "|".join(f"{tc.function.name}:{tc.function.arguments}" for tc in tool_calls)
        if tool_sig == last_tool_sig:
            consecutive_same += 1
        else:
            consecutive_same = 1
            last_tool_sig = tool_sig

        iter_domains = set()
        for tc in tool_calls:
            domain = TOOL_DOMAINS.get(tc.function.name, "unknown")
            iter_domains.add(domain)
            distinct_tools_used.add(tc.function.name)
            tool_call_history.append((tc.function.name, tc.function.arguments))
        domain_history.append(iter_domains)

        drift_reason = detect_drift(
            consecutive_same, domain_history, distinct_tools_used, iteration,
            tool_call_history=tool_call_history,
        )

        if drift_reason:
            logger.warning("Chat: drift detected (%s), forcing response", drift_reason)
            messages.append({
                "role": "system",
                "content": f"STOP — {drift_reason}. Odpověz uživateli s tím co víš. Nevolej žádné další tools.",
            })
            break_response = await call_llm(messages=messages, tier=tier)
            final_text = break_response.choices[0].message.content or "Nemám dostatek informací pro odpověď."

            # EPIC 14-S1: Fact-check post-processing
            fc_result = await run_fact_check(final_text, effective_client_id, effective_project_id)

            # EPIC 9-S1: Topic tracking
            drift_topics = await detect_topics(request.message, final_text, used_tools)
            await update_conversation_topics(request.session_id, drift_topics)

            # Save to DB BEFORE streaming so messages survive window switch
            await save_assistant_message(
                request.session_id, final_text,
                {"drift_break": drift_reason, "used_tools": ",".join(used_tools),
                 **fact_check_metadata(fc_result), **topic_metadata(drift_topics),
                 **source_tracker.build_metadata()},
            )

            async for event in stream_text(final_text):
                yield event

            yield ChatStreamEvent(type="done", metadata={
                "drift_break": drift_reason, "iterations": iteration + 1,
                **confidence_badge(fc_result), **topic_metadata(drift_topics),
                **source_tracker.build_done_metadata(),
            })
            return

        # --- Execute tool calls ---
        tool_names = [tc.function.name for tc in tool_calls]
        logger.info("Chat: executing %d tool calls: %s", len(tool_calls), ", ".join(tool_names))
        if remaining_text:
            logger.info("Chat: LLM reasoning: %s", remaining_text[:300])

        assistant_msg = {"role": "assistant", "content": remaining_text or None, "tool_calls": []}
        for tc in tool_calls:
            assistant_msg["tool_calls"].append({
                "id": tc.id, "type": "function",
                "function": {"name": tc.function.name, "arguments": tc.function.arguments},
            })
        messages.append(assistant_msg)

        for tool_call in tool_calls:
            # Disconnect check inside tool execution
            if disconnect_event and disconnect_event.is_set():
                logger.info("Chat: disconnect during tool execution (iter %d)", iteration)
                partial = _build_interrupted_content(tool_summaries)
                if partial:
                    await save_assistant_message(request.session_id, partial, {"interrupted": "true"}, compress=False)
                yield ChatStreamEvent(type="done", metadata={"interrupted": True})
                return

            tool_name = tool_call.function.name
            try:
                arguments = json.loads(tool_call.function.arguments)
            except json.JSONDecodeError:
                logger.warning("Chat: malformed tool arguments for %s: %s",
                               tool_name, tool_call.function.arguments[:200])
                messages.append({
                    "role": "tool", "tool_call_id": tool_call.id,
                    "content": f"Chyba: argumenty pro {tool_name} nejsou platný JSON. Oprav formát a zkus znovu.",
                })
                continue

            logger.info("Chat: calling tool %s with args: %s", tool_name, str(arguments)[:200])

            # Tool result cache — return cached result for duplicate read-only calls
            cache_key = f"{tool_name}:{tool_call.function.arguments}"
            _WRITE_TOOLS = {"create_background_task", "create_work_plan", "respond_to_user_task", "dispatch_coding_agent", "store_knowledge", "switch_context"}
            cached_result = tool_result_cache.get(cache_key) if tool_name not in _WRITE_TOOLS else None
            if cached_result is not None:
                logger.info("Chat: cache hit for %s (skipping execution)", tool_name)
                result = f"[Cached — tento tool se stejnými argumenty už byl volán] {cached_result}"
                used_tools.append(tool_name)
                tool_summaries.append(f"{tool_name}: (cached)")
                yield ChatStreamEvent(type="tool_result", content=result[:500], metadata={"tool": tool_name, "cached": True})
                messages.append({"role": "tool", "tool_call_id": tool_call.id, "content": result})
                continue

            yield ChatStreamEvent(type="thinking", content=describe_tool_call(tool_name, arguments))
            yield ChatStreamEvent(type="tool_call", content=tool_name, metadata={"tool": tool_name, "args": arguments})

            # switch_context: resolve names → IDs, emit scope_change
            if tool_name == "switch_context":
                resolved = resolve_switch_context(arguments, runtime_ctx)
                result = resolved["message"]
                used_tools.append(tool_name)
                tool_summaries.append(f"switch_context: {result[:100]}")

                if resolved.get("client_id"):
                    effective_client_id = resolved["client_id"]
                    effective_project_id = resolved.get("project_id")

                    # Save project boundary to conversation history so summaries
                    # and context assembly can distinguish project contexts
                    boundary_text = (
                        f"[KONTEXT PŘEPNUT] Klient: {resolved.get('client_name', effective_client_id)}"
                        + (f", Projekt: {resolved.get('project_name', effective_project_id)}" if effective_project_id else "")
                        + ". Předchozí kontext uzavřen — následující zprávy patří k novému projektu."
                    )
                    try:
                        await save_assistant_message(request.session_id, boundary_text, {"scope_boundary": "true"}, compress=False)
                    except Exception as e:
                        logger.warning("Failed to save scope boundary message: %s", e)

                    # Scope changed — inject permissions reset so LLM re-asks for consent
                    messages.append({
                        "role": "system",
                        "content": (
                            f"Scope se změnil na: {resolved.get('client_name', '')} / {resolved.get('project_name', '')}. "
                            "Všechna dříve udělená oprávnění pro write akce "
                            "(create_background_task, create_work_plan, dispatch_coding_agent, store_knowledge) "
                            "jsou RESETOVÁNA. Při dalším použití write akce se znovu zeptej na souhlas. "
                            "DŮLEŽITÉ: Informace z předchozího projektu NEPOUŽÍVEJ pro aktuální projekt."
                        ),
                    })
                    yield ChatStreamEvent(
                        type="scope_change",
                        metadata={
                            "clientId": resolved["client_id"],
                            "clientName": resolved.get("client_name", ""),
                            "projectId": resolved.get("project_id", ""),
                            "projectName": resolved.get("project_name", ""),
                            "projects": resolve_client_projects_json(resolved["client_id"], runtime_ctx),
                        },
                    )
            else:
                try:
                    result = await execute_chat_tool(
                        tool_name, arguments,
                        effective_client_id, effective_project_id,
                        group_id=getattr(request, "active_group_id", None),
                    )
                except ApprovalRequiredInterrupt as approval_exc:
                    # Check session-level auto-approvals first
                    auto_approved_actions = _session_auto_approvals.get(request.session_id, set())
                    if approval_exc.action in auto_approved_actions:
                        logger.info("Chat: auto-approved %s (session rule)", approval_exc.action)
                        result = await execute_chat_tool(
                            tool_name, arguments,
                            effective_client_id, effective_project_id,
                            group_id=getattr(request, "active_group_id", None),
                        )
                    else:
                        # Emit approval request and wait for user response
                        logger.info("Chat: approval required for %s, emitting SSE event", approval_exc.action)
                        yield ChatStreamEvent(
                            type="approval_request",
                            content=approval_exc.preview,
                            metadata={
                                "action": approval_exc.action,
                                "tool": tool_name,
                                "args": str(arguments)[:500],
                            },
                        )
                        logger.info("Chat: approval_request SSE event yielded, now waiting for user response")

                        # Create future and wait for /chat/approve response
                        loop = asyncio.get_event_loop()
                        future = loop.create_future()
                        _pending_approvals[request.session_id] = future
                        try:
                            approval_result = await asyncio.wait_for(future, timeout=300)
                        except asyncio.TimeoutError:
                            approval_result = {"approved": False}
                            logger.warning("Chat: approval timed out for %s", approval_exc.action)
                        finally:
                            _pending_approvals.pop(request.session_id, None)

                        if approval_result.get("approved"):
                            logger.info("Chat: user approved %s (always=%s)", approval_exc.action, approval_result.get("always"))
                            # Re-execute with approval bypass
                            from app.tools.executor import execute_tool
                            result = await execute_tool(
                                tool_name=tool_name,
                                arguments=arguments,
                                client_id=effective_client_id or "",
                                project_id=effective_project_id,
                                processing_mode="FOREGROUND",
                                skip_approval=True,
                                group_id=getattr(request, "active_group_id", None),
                            )
                        else:
                            result = f"Akce {tool_name} byla zamítnuta uživatelem."
                            logger.info("Chat: user denied %s", approval_exc.action)

                used_tools.append(tool_name)
                tool_summaries.append(f"{tool_name}: {result[:100]}")
                logger.info("Chat: tool %s result (%d chars): %s", tool_name, len(result), result[:200])

                # EPIC 14-S2: Track KB sources for attribution
                source_tracker.add_tool_result(tool_name, result)

                # Cache read-only tool results for deduplication
                if tool_name not in _WRITE_TOOLS:
                    tool_result_cache[cache_key] = result

                if tool_name in ("create_background_task", "create_work_plan"):
                    created_tasks.append(arguments)
                if tool_name == "respond_to_user_task":
                    responded_tasks.append(arguments.get("task_id", ""))

                # Track scope from tool arguments
                tool_client = arguments.get("client_id")
                tool_project = arguments.get("project_id")
                if tool_client and tool_client != effective_client_id:
                    effective_client_id = tool_client
                    effective_project_id = tool_project
                    yield ChatStreamEvent(
                        type="scope_change",
                        metadata={
                            "clientId": tool_client,
                            "clientName": resolve_client_name(tool_client, runtime_ctx) or "",
                            "projectId": tool_project or "",
                            "projectName": resolve_project_name(tool_client, tool_project, runtime_ctx) or "",
                            "projects": resolve_client_projects_json(tool_client, runtime_ctx),
                        },
                    )
                elif tool_project and tool_project != effective_project_id:
                    effective_project_id = tool_project
                    yield ChatStreamEvent(
                        type="scope_change",
                        metadata={
                            "clientId": effective_client_id or "",
                            "clientName": resolve_client_name(effective_client_id, runtime_ctx) or "",
                            "projectId": tool_project,
                            "projectName": resolve_project_name(effective_client_id, tool_project, runtime_ctx) or "",
                            "projects": resolve_client_projects_json(effective_client_id, runtime_ctx),
                        },
                    )

            yield ChatStreamEvent(type="tool_result", content=result[:500], metadata={"tool": tool_name})
            messages.append({"role": "tool", "tool_call_id": tool_call.id, "content": result})

        # Focus reminder
        remaining_iters = effective_max_iterations - iteration - 1
        messages.append({
            "role": "system",
            "content": (
                f'[FOCUS] Původní otázka: "{request.message[:200]}"\n'
                f"Zbývá {remaining_iters} iterací. Pokud máš dost info, ODPOVĚZ."
            ),
        })
        yield ChatStreamEvent(type="thinking", content="Analyzuji výsledky...")

    # Max iterations reached — force response
    logger.warning("Chat: max iterations (%d) reached, forcing response", effective_max_iterations)
    messages.append({
        "role": "system",
        "content": "Dosáhl jsi maximálního počtu iterací. Odpověz uživateli s tím co víš. Nevolej žádné tools.",
    })
    try:
        final_resp = await call_llm(messages=messages, tier=tier)
        final_text = final_resp.choices[0].message.content or "Omlouvám se, vyčerpal jsem limit operací."

        # EPIC 14-S1: Fact-check post-processing
        fc_result = await run_fact_check(final_text, effective_client_id, effective_project_id)

        # EPIC 9-S1: Topic tracking
        max_iter_topics = await detect_topics(request.message, final_text, used_tools)
        await update_conversation_topics(request.session_id, max_iter_topics)

        # Save to DB BEFORE streaming so messages survive window switch
        await save_assistant_message(
            request.session_id, final_text,
            {"max_iterations": "true", "used_tools": ",".join(used_tools),
             **fact_check_metadata(fc_result), **topic_metadata(max_iter_topics),
             **source_tracker.build_metadata()},
        )

        async for event in stream_text(final_text):
            yield event

        yield ChatStreamEvent(type="done", metadata={
            "max_iterations": True, "iterations": effective_max_iterations,
            **confidence_badge(fc_result), **topic_metadata(max_iter_topics),
            **source_tracker.build_done_metadata(),
        })
    except Exception as e:
        logger.error("Chat: failed to generate max-iterations response: %s", e)
        yield ChatStreamEvent(type="error", content="Vyčerpán limit operací.")


def _build_interrupted_content(tool_summaries: list[str]) -> str | None:
    """Build partial content for interrupted chat (stop/disconnect)."""
    if not tool_summaries:
        return None
    return (
        f"[Přerušeno po {len(tool_summaries)} operacích]\n"
        + "\n".join(f"- {s}" for s in tool_summaries)
    )
