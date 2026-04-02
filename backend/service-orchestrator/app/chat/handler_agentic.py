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
from app.chat.hallucination_guard import needs_verification_retry, is_empty_promise, claims_no_web_access
from app.chat.handler_fact_check import run_fact_check, fact_check_metadata, confidence_badge
from app.chat.handler_streaming import call_llm, stream_text, save_assistant_message
from app.chat.source_attribution import SourceTracker
from app.chat.topic_tracker import detect_topics, update_conversation_topics, topic_metadata
from app.llm.router_client import route_request
from app.chat.handler_tools import (
    _GRAPH_TOOLS,
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
# Global auto-approved actions (persistent — loaded from MongoDB on startup)
_global_auto_approvals: set[str] = set()
_approvals_loaded: bool = False


async def _ensure_approvals_loaded():
    """Load persistent approval rules from MongoDB (once)."""
    global _approvals_loaded
    if _approvals_loaded:
        return
    _approvals_loaded = True
    try:
        from app.agent.persistence import agent_store
        coll = await agent_store._ensure_collection()
        db = coll.database
        rules_coll = db["approval_rules"]
        async for doc in rules_coll.find({"enabled": True}):
            _global_auto_approvals.add(doc["action"])
        if _global_auto_approvals:
            logger.info("Loaded %d persistent approval rules: %s",
                        len(_global_auto_approvals), _global_auto_approvals)
    except Exception as e:
        logger.warning("Failed to load approval rules from DB: %s", e)


async def _persist_approval_rule(action: str):
    """Save an auto-approval rule to MongoDB."""
    try:
        from app.agent.persistence import agent_store
        coll = await agent_store._ensure_collection()
        db = coll.database
        rules_coll = db["approval_rules"]
        await rules_coll.update_one(
            {"action": action},
            {"$set": {"action": action, "enabled": True, "updated_at": __import__("datetime").datetime.now(__import__("datetime").timezone.utc)}},
            upsert=True,
        )
        _global_auto_approvals.add(action)
        logger.info("Persisted approval rule: %s", action)
    except Exception as e:
        logger.warning("Failed to persist approval rule %s: %s", action, e)


async def _persist_pending_approval(
    session_id: str, action: str, tool_name: str, arguments: dict,
    preview: str, client_id: str | None, project_id: str | None,
    group_id: str | None,
) -> str:
    """Persist pending approval to MongoDB so it survives orchestrator restart."""
    from datetime import datetime, timezone
    approval_id = str(ObjectId())
    try:
        from app.agent.persistence import agent_store
        coll = await agent_store._ensure_collection()
        db = coll.database
        await db["pending_approvals"].insert_one({
            "_id": approval_id,
            "session_id": session_id,
            "action": action,
            "tool_name": tool_name,
            "arguments": arguments,
            "preview": preview[:500],
            "client_id": client_id,
            "project_id": project_id,
            "group_id": group_id,
            "status": "pending",
            "created_at": datetime.now(timezone.utc),
        })
    except Exception as e:
        logger.warning("Failed to persist pending approval: %s", e)
    return approval_id


async def _resolve_pending_approval(approval_id: str, approved: bool) -> None:
    """Mark a pending approval as resolved in MongoDB."""
    try:
        from app.agent.persistence import agent_store
        coll = await agent_store._ensure_collection()
        db = coll.database
        await db["pending_approvals"].update_one(
            {"_id": approval_id},
            {"$set": {"status": "approved" if approved else "denied",
                      "resolved_at": __import__("datetime").datetime.now(__import__("datetime").timezone.utc)}},
        )
    except Exception as e:
        logger.warning("Failed to resolve pending approval %s: %s", approval_id, e)


async def recover_pending_approvals() -> list[dict]:
    """Find pending approvals from before a restart and auto-execute them.

    Called on first chat request after restart. Finds approvals that were pending
    when orchestrator crashed, checks if they're auto-approvable, and executes them.
    Returns list of recovered tool executions for user notification.
    """
    recovered = []
    try:
        from app.agent.persistence import agent_store
        coll = await agent_store._ensure_collection()
        db = coll.database
        approvals_coll = db["pending_approvals"]

        # Find stale pending approvals (older than 30s = orchestrator restarted)
        from datetime import datetime, timezone, timedelta
        cutoff = datetime.now(timezone.utc) - timedelta(seconds=30)
        stale = await approvals_coll.find({
            "status": "pending",
            "created_at": {"$lt": cutoff},
        }).to_list(10)

        for doc in stale:
            action = doc.get("action", "")
            tool_name = doc.get("tool_name", "")
            arguments = doc.get("arguments", {})

            # Only auto-recover if action is in global auto-approvals
            await _ensure_approvals_loaded()
            if action in _global_auto_approvals:
                logger.info("APPROVAL_RECOVERY: auto-executing %s (tool=%s, stale approval %s)",
                            action, tool_name, doc["_id"])
                try:
                    from app.tools.executor import execute_tool
                    result = await execute_tool(
                        tool_name=tool_name,
                        arguments=arguments,
                        client_id=doc.get("client_id", ""),
                        project_id=doc.get("project_id"),
                        processing_mode="FOREGROUND",
                        skip_approval=True,
                        group_id=doc.get("group_id"),
                    )
                    await approvals_coll.update_one(
                        {"_id": doc["_id"]},
                        {"$set": {"status": "recovered", "result": str(result)[:500]}},
                    )
                    recovered.append({"tool": tool_name, "action": action, "result": result[:200]})
                    logger.info("APPROVAL_RECOVERY: %s completed successfully", tool_name)
                except Exception as e:
                    logger.warning("APPROVAL_RECOVERY: %s failed: %s", tool_name, e)
                    await approvals_coll.update_one(
                        {"_id": doc["_id"]},
                        {"$set": {"status": "recovery_failed", "error": str(e)[:500]}},
                    )
            else:
                # Not auto-approvable — mark as expired, user will need to retry
                logger.info("APPROVAL_RECOVERY: expiring non-auto-approvable %s (tool=%s)", action, tool_name)
                await approvals_coll.update_one(
                    {"_id": doc["_id"]},
                    {"$set": {"status": "expired"}},
                )

    except Exception as e:
        logger.warning("APPROVAL_RECOVERY failed: %s", e)
    return recovered


def resolve_pending_approval(session_id: str, approved: bool, always: bool = False, action: str | None = None):
    """Called from /chat/approve endpoint to resolve a pending approval."""
    future = _pending_approvals.get(session_id)
    if future and not future.done():
        if always and action:
            _global_auto_approvals.add(action)
            # Persist in background (fire-and-forget)
            import asyncio
            asyncio.ensure_future(_persist_approval_rule(action))
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
    # Safety limit — NOT a target. Actual termination is by stagnation/loop detection.
    if max_iterations_override is not None:
        effective_max_iterations = max_iterations_override
    else:
        effective_max_iterations = settings.chat_max_iterations  # safety ceiling only

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
    effective_group_id = getattr(request, "active_group_id", None)

    # Scope-aware save helper — all assistant messages carry client/project context
    async def _save_msg(content: str, meta: dict | None = None, compress: bool = True) -> None:
        await save_assistant_message(
            request.session_id, content, meta, compress=compress,
            client_id=effective_client_id, project_id=effective_project_id,
            group_id=effective_group_id,
        )
    stagnation_counter = 0  # Consecutive iterations without new unique tool calls
    last_unique_tool_count = 0
    _guard_fallback_text: str | None = None  # First unverified answer saved for fallback
    _skip_models: list[str] = []  # Models that failed hallucination guard → skip in routing
    _guard_retries: int = 0  # How many times hallucination guard triggered clean retry
    _guard_failed_models: list[dict] = []  # Track failed models for UI notification

    # Load persistent approval rules (once, lazy) + recover stale approvals from before restart
    await _ensure_approvals_loaded()
    recovered = await recover_pending_approvals()
    if recovered:
        recovery_msg = "Dokončeny přerušené operace z předchozí session:\n" + "\n".join(
            f"- {r['tool']}: {r['result'][:100]}" for r in recovered
        )
        yield ChatStreamEvent(type="tool_result", content=recovery_msg)
        logger.info("APPROVAL_RECOVERY: notified user about %d recovered operations", len(recovered))

    for iteration in range(effective_max_iterations):
        # Check disconnect between iterations
        if disconnect_event and disconnect_event.is_set():
            logger.info("Chat: stopped by disconnect after %d iterations", iteration)
            partial = _build_interrupted_content(tool_summaries)
            interrupted_meta = {"interrupted": "true"}
            if request.context_task_id:
                interrupted_meta["contextTaskId"] = request.context_task_id
            if partial:
                await _save_msg(partial, interrupted_meta, compress=False)
            yield ChatStreamEvent(type="done", metadata={"interrupted": True, **({"contextTaskId": request.context_task_id} if request.context_task_id else {})})
            return

        logger.info("Chat: iteration %d/%d", iteration + 1, effective_max_iterations)

        tier = ModelTier.LOCAL_STANDARD  # Fixed 48k
        estimated = _estimate_tokens_total(messages, selected_tools)

        # Capability-based routing: ask router for decision
        # Detect image attachments → require "vision" capability
        max_tier = getattr(request, "max_openrouter_tier", "NONE")
        has_images = any(
            isinstance(m.get("content"), list) and any(
                p.get("type") == "image_url" for p in m["content"] if isinstance(p, dict)
            )
            for m in messages if isinstance(m, dict)
        )
        route_capability = "visual" if has_images else "chat"
        route = await route_request(
            capability=route_capability,
            max_tier=max_tier,
            estimated_tokens=estimated,
            skip_models=_skip_models or None,
            require_tools=bool(selected_tools),
        )
        logger.info("Chat: estimated_tokens=%d → tier=%s, route=%s/%s (max_tier=%s)",
                     estimated, tier.value, route.target, route.model or tier.value, max_tier)

        # Preempt background only on first iteration AND only when tier=NONE (local GPU only).
        # FREE/PAID/PREMIUM tiers use OpenRouter — no GPU contention with background tasks.
        if iteration == 0 and max_tier == "NONE":
            try:
                from app.tools.kotlin_client import kotlin_client
                await kotlin_client.register_foreground_start()
            except Exception as e:
                logger.warning("Failed to register foreground start: %s", e)

        # For FREE models: inject tool-forcing reminder as last system message
        # Small models ignore system prompt at 29k context — this reminder is close to the end
        call_messages = messages
        if max_tier in ("FREE", "NONE") and iteration == 0 and selected_tools:
            tool_reminder = {
                "role": "system",
                "content": (
                    "REMINDER: You MUST call tools before answering. "
                    "Use kb_search to check existing data. "
                    "Use store_knowledge to save new facts from the user. "
                    "Do NOT answer from memory — call a tool first."
                ),
            }
            call_messages = messages + [tool_reminder]

        response = await call_llm(
            messages=call_messages, tier=tier, tools=selected_tools, route=route,
            max_tier=max_tier, estimated_tokens=estimated,
        )

        choice = response.choices[0]
        tool_calls, remaining_text = extract_tool_calls(choice.message)

        # No tool calls → final text response
        if not tool_calls:
            import re as _re
            raw_final = remaining_text or choice.message.content or ""
            # Strip any residual <tool_call> XML that extract_tool_calls may have missed
            final_text = _re.sub(r'<tool_call>.*?</tool_call>', '', raw_final, flags=_re.DOTALL).strip()
            if not final_text:
                final_text = ""  # Will trigger hallucination guard or empty response handling
            logger.info("Chat: final answer after %d iterations (%d chars)", iteration + 1, len(final_text))

            # Pre-processing hallucination guard: if model answered without ANY tool calls
            # and the response contains URLs or real-world entities, force a retry
            # so the model actually verifies claims via web_search/web_fetch.
            # Hallucination guard: clean retry with model fallback.
            # Each failing model gets skipped, next model in queue gets the
            # original clean prompt. Max retries = number of models in queue.
            if not used_tools:
                retry_reason = needs_verification_retry(final_text)
                promise_detected = is_empty_promise(final_text)
                no_access_claim = claims_no_web_access(final_text)

                if (retry_reason or promise_detected or no_access_claim) and _guard_retries < 1:
                    _guard_retries += 1
                    _reason = retry_reason or ("no_web_access_claim" if no_access_claim else "empty_promise")
                    logger.warning("HALLUCINATION_GUARD | retry %d: %s — skipping model, clean retry",
                                   _guard_retries, _reason)
                    # Save first real answer for fallback
                    if retry_reason and _guard_retries == 1 and len(final_text) > 200:
                        _guard_fallback_text = final_text

                    # Report current model as "failed" so router picks next model in queue
                    if route and route.model:
                        _failed_model = route.model
                        if _failed_model not in _skip_models:
                            _skip_models.append(_failed_model)
                        _guard_failed_models.append({
                            "model": _failed_model,
                            "reason": _reason,
                            "retry": _guard_retries,
                        })
                        from app.llm.router_client import report_model_error
                        await report_model_error(
                            _failed_model,
                            f"Hallucination guard: model refused to use tools ({_reason})",
                        )
                        logger.info("HALLUCINATION_GUARD | reported %s as failed, skip_models=%s → next model gets clean prompt",
                                    route.model, _skip_models)

                    # Clean retry: do NOT append failed response to messages.
                    logger.info("HALLUCINATION_GUARD | clean retry — discarding failed response (%d chars), "
                                "sending original messages to next model", len(final_text))
                    continue  # retry — router will pick next model via skip_models
                elif (retry_reason or promise_detected) and _guard_retries >= 1:
                    # Already retried once — send with warning, don't loop
                    logger.warning("HALLUCINATION_GUARD | sending unverified after %d retries", _guard_retries)
                    if _guard_fallback_text:
                        final_text = (
                            "⚠️ Následující informace nejsou ověřené vyhledáváním:\n\n"
                            + _guard_fallback_text
                        )
                    # else keep whatever model returned

            # Fact-check + topic tracking — parallel
            fc_result, topics = await asyncio.gather(
                run_fact_check(final_text, effective_client_id, effective_project_id,
                               web_evidence=source_tracker.web_evidence_text),
                detect_topics(request.message, final_text, used_tools),
            )
            await update_conversation_topics(request.session_id, topics)

            # Save to DB BEFORE streaming so messages survive window switch
            await _save_msg(
                final_text,
                {
                    **({"used_tools": ",".join(used_tools)} if used_tools else {}),
                    **({"created_tasks": ",".join(str(t.get("title", "")) for t in created_tasks)} if created_tasks else {}),
                    **({"responded_tasks": ",".join(responded_tasks)} if responded_tasks else {}),
                    **({"summarized": "true", "original_length": str(msg_len)} if is_summarized else {}),
                    **({"contextTaskId": request.context_task_id} if request.context_task_id else {}),
                    **fact_check_metadata(fc_result),
                    **topic_metadata(topics),
                    **source_tracker.build_metadata(),
                },
            )

            async for event in stream_text(final_text):
                yield event

            # Extract coding_agent_task_id for inline log streaming in UI bubble
            _ca_tid = next((t["coding_agent_task_id"] for t in created_tasks if "coding_agent_task_id" in t), None)
            yield ChatStreamEvent(type="done", metadata={
                "created_tasks": created_tasks, "responded_tasks": responded_tasks,
                "used_tools": used_tools, "iterations": iteration + 1,
                **({"contextTaskId": request.context_task_id} if request.context_task_id else {}),
                **({"coding_agent_task_id": _ca_tid} if _ca_tid else {}),
                **({"guard_failed_models": _guard_failed_models} if _guard_failed_models else {}),
                **({"model": route.model} if route and route.model else {}),
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
                "content": (
                    f"STOP — {drift_reason}.\n\n"
                    "RULES FOR YOUR FINAL RESPONSE:\n"
                    "1. Answer ONLY based on data from tool results above (web_search, web_fetch, kb_search).\n"
                    "2. NEVER fabricate missing details (addresses, prices, ratings, phone numbers) from your own knowledge.\n"
                    "3. If you don't have verified data for an entity from tool results → write 'not found' or omit it.\n"
                    "4. Cite source (URL from web_search result) for each claim.\n"
                    "5. Prefer 3 verified results over 10 unverified ones.\n"
                    "6. Do NOT call any more tools. Respond with text only.\n"
                    "7. Respond in the SAME LANGUAGE as the user's message."
                ),
            })
            break_response = await call_llm(messages=messages, tier=tier, route=route,
                                              max_tier=max_tier, estimated_tokens=estimated)
            raw_text = break_response.choices[0].message.content or ""

            # Strip any raw XML tool_call tags that FREE models sometimes generate in text
            import re as _re
            final_text = _re.sub(r'<tool_call>.*?</tool_call>', '', raw_text, flags=_re.DOTALL).strip()
            if not final_text or len(final_text) < 10:
                # Model failed to produce useful response — synthesize from tool results
                final_text = _synthesize_from_tool_results(tool_summaries, request.message)

            # Fact-check + topic tracking — parallel
            fc_result, drift_topics = await asyncio.gather(
                run_fact_check(final_text, effective_client_id, effective_project_id,
                               web_evidence=source_tracker.web_evidence_text),
                detect_topics(request.message, final_text, used_tools),
            )
            await update_conversation_topics(request.session_id, drift_topics)

            # Save to DB BEFORE streaming so messages survive window switch
            await _save_msg(
                final_text,
                {"drift_break": drift_reason, "used_tools": ",".join(used_tools),
                 **({"contextTaskId": request.context_task_id} if request.context_task_id else {}),
                 **fact_check_metadata(fc_result), **topic_metadata(drift_topics),
                 **source_tracker.build_metadata()},
            )

            async for event in stream_text(final_text):
                yield event

            _ca_tid2 = next((t["coding_agent_task_id"] for t in created_tasks if "coding_agent_task_id" in t), None)
            yield ChatStreamEvent(type="done", metadata={
                "drift_break": drift_reason, "iterations": iteration + 1,
                **({"contextTaskId": request.context_task_id} if request.context_task_id else {}),
                **({"coding_agent_task_id": _ca_tid2} if _ca_tid2 else {}),
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
                    await _save_msg(partial, {"interrupted": "true"}, compress=False)
                yield ChatStreamEvent(type="done", metadata={"interrupted": True})
                return

            tool_name = tool_call.function.name
            try:
                arguments = json.loads(tool_call.function.arguments)
            except json.JSONDecodeError:
                logger.warning("Chat: malformed tool arguments for %s: %s",
                               tool_name, tool_call.function.arguments[:200])
                # Provide error response so assistant message with tool_calls stays valid
                messages.append({
                    "role": "tool", "tool_call_id": tool_call.id,
                    "content": f"Error: arguments for {tool_name} are not valid JSON. Fix format and try again.",
                })
                continue

            logger.info("Chat: calling tool %s with args: %s", tool_name, str(arguments)[:200])

            # Tool result cache — return cached result for duplicate read-only calls
            cache_key = f"{tool_name}:{tool_call.function.arguments}"
            _WRITE_TOOLS = {"create_background_task", "dispatch_thinking_graph", "respond_to_user_task", "dispatch_coding_agent", "store_knowledge", "switch_context", *_GRAPH_TOOLS}
            cached_result = tool_result_cache.get(cache_key) if tool_name not in _WRITE_TOOLS else None
            if cached_result is not None:
                logger.info("Chat: cache hit for %s (skipping execution)", tool_name)
                result = f"[Cached — tento tool se stejnými argumenty už byl volán] {cached_result}"
                used_tools.append(tool_name)
                tool_summaries.append(f"{tool_name}: (cached)")
                yield ChatStreamEvent(type="tool_result", content=result, metadata={"tool": tool_name, "cached": True})
                messages.append({"role": "tool", "tool_call_id": tool_call.id, "content": result})
                continue

            yield ChatStreamEvent(type="thinking", content=describe_tool_call(tool_name, arguments))
            yield ChatStreamEvent(type="tool_call", content=tool_name, metadata={"tool": tool_name, "args": arguments})

            # request_tools: dynamically expand tool set
            if tool_name == "request_tools":
                from app.chat.tools import ToolCategory, TOOL_CATEGORIES, TOOL_CATEGORY_DESCRIPTIONS
                category_str = arguments.get("category", "")
                try:
                    category = ToolCategory(category_str)
                except ValueError:
                    result = f"Neznámá kategorie '{category_str}'. Dostupné: {', '.join(c.value for c in ToolCategory)}"
                    messages.append({"role": "tool", "tool_call_id": tool_call.id, "content": result})
                    used_tools.append(tool_name)
                    continue

                new_tools = TOOL_CATEGORIES[category]
                # Add new tools to selected_tools (avoid duplicates)
                existing_names = {t["function"]["name"] for t in selected_tools}
                added_names = []
                for tool_def in new_tools:
                    tname = tool_def["function"]["name"]
                    if tname not in existing_names:
                        selected_tools.append(tool_def)
                        existing_names.add(tname)
                        added_names.append(tname)

                desc = TOOL_CATEGORY_DESCRIPTIONS.get(category, category_str)
                if added_names:
                    result = (
                        f"Kategorie '{desc}' načtena. Nové nástroje: {', '.join(added_names)}. "
                        "Můžeš je teď používat."
                    )
                    logger.info("Chat: request_tools(%s) → added %d tools: %s",
                                category_str, len(added_names), ", ".join(added_names))
                else:
                    result = f"Kategorie '{desc}' — všechny nástroje už jsou načtené."

                used_tools.append(tool_name)
                tool_summaries.append(f"request_tools: {category_str} → +{len(added_names)} tools")
                yield ChatStreamEvent(type="tool_result", content=result, metadata={"tool": tool_name})
                messages.append({"role": "tool", "tool_call_id": tool_call.id, "content": result})
                continue

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
                        await _save_msg(boundary_text, {"scope_boundary": "true"}, compress=False)
                    except Exception as e:
                        logger.warning("Failed to save scope boundary message: %s", e)

                    # Scope changed — inject permissions reset so LLM re-asks for consent
                    messages.append({
                        "role": "system",
                        "content": (
                            f"Scope se změnil na: {resolved.get('client_name', '')} / {resolved.get('project_name', '')}. "
                            "Všechna dříve udělená oprávnění pro write akce "
                            "(create_background_task, dispatch_thinking_graph, dispatch_coding_agent, store_knowledge) "
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
                        session_id=request.session_id,
                    )
                except ApprovalRequiredInterrupt as approval_exc:
                    # Check persistent auto-approvals (global, survives restarts)
                    await _ensure_approvals_loaded()
                    if approval_exc.action in _global_auto_approvals:
                        logger.info("Chat: auto-approved %s (persistent rule)", approval_exc.action)
                        result = await execute_chat_tool(
                            tool_name, arguments,
                            effective_client_id, effective_project_id,
                            group_id=getattr(request, "active_group_id", None),
                            session_id=request.session_id,
                        )
                    else:
                        # Persist pending approval to MongoDB (survives restart)
                        approval_id = await _persist_pending_approval(
                            session_id=request.session_id,
                            action=approval_exc.action,
                            tool_name=tool_name,
                            arguments=arguments,
                            preview=approval_exc.preview,
                            client_id=effective_client_id,
                            project_id=effective_project_id,
                            group_id=getattr(request, "active_group_id", None),
                        )

                        # Emit approval request and wait for user response
                        logger.info("Chat: approval required for %s (id=%s), emitting SSE event",
                                    approval_exc.action, approval_id)
                        yield ChatStreamEvent(
                            type="approval_request",
                            content=approval_exc.preview,
                            metadata={
                                "action": approval_exc.action,
                                "tool": tool_name,
                                "args": str(arguments)[:500],
                                "approval_id": approval_id,
                            },
                        )

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
                            # Mark approval as resolved in MongoDB
                            await _resolve_pending_approval(approval_id, approved=True)
                        else:
                            result = f"Akce {tool_name} byla zamítnuta uživatelem."
                            logger.info("Chat: user denied %s", approval_exc.action)
                            await _resolve_pending_approval(approval_id, approved=False)

                used_tools.append(tool_name)
                tool_summaries.append(f"{tool_name}: {result}")
                logger.info("Chat: tool %s result (%d chars): %s", tool_name, len(result), result[:200])

                # EPIC 14-S2: Track KB sources for attribution
                source_tracker.add_tool_result(tool_name, result)

                # Cache read-only tool results for deduplication
                if tool_name not in _WRITE_TOOLS:
                    tool_result_cache[cache_key] = result

                if tool_name in ("create_background_task", "dispatch_thinking_graph"):
                    created_tasks.append(arguments)
                if tool_name == "dispatch_coding_agent" and "taskId" in result:
                    # Extract taskId from result string for inline log streaming
                    import re as _re
                    _task_id_match = _re.search(r"'taskId':\s*'([0-9a-fA-F]{24})'", result)
                    if _task_id_match:
                        created_tasks.append({"coding_agent_task_id": _task_id_match.group(1)})
                if tool_name == "respond_to_user_task":
                    responded_tasks.append(arguments.get("task_id", ""))

                # Emit thinking graph update for UI panel
                if tool_name in _GRAPH_TOOLS:
                    from app.chat.thinking_graph import get_active_graph
                    graph = await get_active_graph(request.session_id)
                    if graph:
                        yield ChatStreamEvent(
                            type="thinking_graph_update",
                            content=graph.model_dump_json(),
                            metadata={
                                "graph_id": graph.id,
                                "title": graph.vertices.get(graph.root_vertex_id, None)
                                    and graph.vertices[graph.root_vertex_id].title or "Mapa",
                                "vertex_count": str(len(graph.vertices)),
                            },
                        )

                # Scope is tracked ONLY via explicit switch_context tool —
                # do NOT infer scope changes from tool arguments (client_id/project_id)
                # as LLM may pass wrong IDs causing unwanted UI scope switches.

            yield ChatStreamEvent(type="tool_result", content=result, metadata={"tool": tool_name})
            messages.append({"role": "tool", "tool_call_id": tool_call.id, "content": result})

            # Enriched thinking — short summary of tool result for UI
            result_preview = result[:120].replace("\n", " ").strip()
            if result_preview:
                yield ChatStreamEvent(type="thinking", content=f"{tool_name} → {result_preview}")

        # --- Stagnation tracking ---
        current_unique = len(set(tool_call_history))
        if current_unique > last_unique_tool_count:
            stagnation_counter = 0
            last_unique_tool_count = current_unique
        else:
            stagnation_counter += 1

        # Focus reminder — only on stagnation or near safety limit
        remaining_iters = effective_max_iterations - iteration - 1
        if stagnation_counter >= 3:
            messages.append({
                "role": "system",
                "content": (
                    f'[FOCUS] Původní otázka: "{request.message[:200]}"\n'
                    "Tvé poslední tool calls nepřinesly nové informace. "
                    "Pokud máš dost dat, ODPOVĚZ. Pokud ne, použij JINÝ tool nebo jiné argumenty."
                ),
            })
        elif stagnation_counter >= 5:
            # Hard stagnation — strip tools
            logger.warning("Chat: stagnation detected (%d iterations without progress), forcing response", stagnation_counter)
            selected_tools = []
            messages.append({
                "role": "system",
                "content": (
                    f'[FOCUS] Původní otázka: "{request.message[:200]}"\n'
                    "Shrň vše co jsi zjistil a ODPOVĚZ. Nevolej další tools."
                ),
            })
        elif remaining_iters <= 3:
            messages.append({
                "role": "system",
                "content": (
                    f'[FOCUS] Původní otázka: "{request.message[:200]}"\n'
                    f"Blížíš se k bezpečnostnímu limitu. Shrň co víš a ODPOVĚZ."
                ),
            })
        else:
            messages.append({
                "role": "system",
                "content": f'[FOCUS] Původní otázka: "{request.message[:200]}"',
            })
        yield ChatStreamEvent(type="thinking", content="Analyzuji výsledky...")

    # Max iterations reached — force response
    logger.warning("Chat: max iterations (%d) reached, forcing response", effective_max_iterations)
    messages.append({
        "role": "system",
        "content": (
            "You have reached the maximum number of iterations. "
            "Respond to the user with whatever information you have gathered so far. "
            "Do NOT call any more tools. Respond with text only. "
            "Respond in the SAME LANGUAGE as the user's message."
        ),
    })
    try:
        final_resp = await call_llm(messages=messages, tier=tier, route=route,
                                       max_tier=max_tier, estimated_tokens=estimated)
        raw_text = final_resp.choices[0].message.content or ""
        import re as _re
        final_text = _re.sub(r'<tool_call>.*?</tool_call>', '', raw_text, flags=_re.DOTALL).strip()
        if not final_text or len(final_text) < 10:
            final_text = _synthesize_from_tool_results(tool_summaries, request.message)

        # EPIC 14-S1 + EPIC 9-S1: Fact-check + topic detection in parallel
        fc_result, max_iter_topics = await asyncio.gather(
            run_fact_check(final_text, effective_client_id, effective_project_id),
            detect_topics(request.message, final_text, used_tools),
        )
        await update_conversation_topics(request.session_id, max_iter_topics)

        # Save to DB BEFORE streaming so messages survive window switch
        await _save_msg(
            final_text,
            {"max_iterations": "true", "used_tools": ",".join(used_tools),
             **fact_check_metadata(fc_result), **topic_metadata(max_iter_topics),
             **source_tracker.build_metadata()},
        )

        async for event in stream_text(final_text):
            yield event

        _ca_tid3 = next((t["coding_agent_task_id"] for t in created_tasks if "coding_agent_task_id" in t), None)
        yield ChatStreamEvent(type="done", metadata={
            "max_iterations": True, "iterations": effective_max_iterations,
            **({"coding_agent_task_id": _ca_tid3} if _ca_tid3 else {}),
            **confidence_badge(fc_result), **topic_metadata(max_iter_topics),
            **source_tracker.build_done_metadata(),
        })
    except Exception as e:
        logger.error("Chat: failed to generate max-iterations response: %s", e)
        import traceback
        tb = traceback.format_exception(type(e), e, e.__traceback__)
        yield ChatStreamEvent(
            type="error",
            content=f"Vyčerpán limit operací: {e}",
            metadata={
                "error": str(e),
                "errorType": type(e).__name__,
                "traceback": "".join(tb[-3:]),
            },
        )


def _build_interrupted_content(tool_summaries: list[str]) -> str | None:
    """Build partial content for interrupted chat (stop/disconnect)."""
    if not tool_summaries:
        return None
    return (
        f"[Přerušeno po {len(tool_summaries)} operacích]\n"
        + "\n".join(f"- {s}" for s in tool_summaries)
    )


def _synthesize_from_tool_results(tool_summaries: list[str], original_question: str) -> str:
    """Synthesize a response from tool results when LLM fails to produce one.

    Used as fallback when drift/max-iter forcing produces empty or XML-only output.
    Compiles tool summaries into a readable answer.
    Note: hardcoded Czech because this is a last-resort fallback for Czech-speaking users.
    """
    if not tool_summaries:
        return f"Nepodařilo se najít odpověď na: {original_question[:100]}"

    parts = [f"Výsledky hledání k '{original_question[:80]}':\n"]
    for summary in tool_summaries[-5:]:
        parts.append(f"• {summary}")
    return "\n".join(parts)
