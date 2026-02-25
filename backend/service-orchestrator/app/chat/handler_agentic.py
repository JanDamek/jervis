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
from app.chat.handler_streaming import call_llm, stream_text, save_assistant_message
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
from app.llm.provider import clamp_tier, llm_provider
from app.models import ModelTier

logger = logging.getLogger(__name__)


def estimate_and_select_tier(messages: list[dict], tools: list[dict]) -> tuple[int, ModelTier]:
    """Estimate token count and select appropriate tier."""
    message_tokens = sum(estimate_tokens(str(m)) for m in messages)
    tools_tokens = sum(estimate_tokens(str(t)) for t in tools)
    output_tokens = 4096
    estimated = message_tokens + tools_tokens + output_tokens
    tier = llm_provider.escalation.select_local_tier(estimated)
    return estimated, clamp_tier(tier)


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
) -> AsyncIterator[ChatStreamEvent]:
    """Run the main agentic loop: LLM → extract tools → execute → iterate.

    Yields ChatStreamEvent objects for SSE streaming.
    """
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

        estimated, tier = estimate_and_select_tier(messages, selected_tools)
        logger.info("Chat: estimated_tokens=%d → tier=%s", estimated, tier.value)

        if iteration == 0 and estimated > 40_000:
            yield ChatStreamEvent(type="thinking", content="Dlouhá zpráva — zpracování potrvá déle...")

        response = await call_llm(messages=messages, tier=tier, tools=selected_tools)

        choice = response.choices[0]
        tool_calls, remaining_text = extract_tool_calls(choice.message)

        # No tool calls → final text response
        if not tool_calls:
            final_text = remaining_text or choice.message.content or ""
            logger.info("Chat: final answer after %d iterations (%d chars)", iteration + 1, len(final_text))

            async for event in stream_text(final_text):
                yield event

            await save_assistant_message(
                request.session_id, final_text,
                {
                    **({"used_tools": ",".join(used_tools)} if used_tools else {}),
                    **({"created_tasks": ",".join(str(t.get("title", "")) for t in created_tasks)} if created_tasks else {}),
                    **({"responded_tasks": ",".join(responded_tasks)} if responded_tasks else {}),
                    **({"summarized": "true", "original_length": str(msg_len)} if is_summarized else {}),
                },
            )

            yield ChatStreamEvent(type="done", metadata={
                "created_tasks": created_tasks, "responded_tasks": responded_tasks,
                "used_tools": used_tools, "iterations": iteration + 1,
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

            async for event in stream_text(final_text):
                yield event

            await save_assistant_message(
                request.session_id, final_text,
                {"drift_break": drift_reason, "used_tools": ",".join(used_tools)},
            )
            yield ChatStreamEvent(type="done", metadata={"drift_break": drift_reason, "iterations": iteration + 1})
            return

        # --- Execute tool calls ---
        logger.info("Chat: executing %d tool calls", len(tool_calls))

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
                    # Scope changed — inject permissions reset so LLM re-asks for consent
                    messages.append({
                        "role": "system",
                        "content": (
                            "Scope se změnil — všechna dříve udělená oprávnění pro write akce "
                            "(create_background_task, dispatch_coding_agent, store_knowledge, brain_create_issue) "
                            "jsou RESETOVÁNA. Při dalším použití write akce se znovu zeptej na souhlas."
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
                result = await execute_chat_tool(
                    tool_name, arguments,
                    effective_client_id, effective_project_id,
                )
                used_tools.append(tool_name)
                tool_summaries.append(f"{tool_name}: {result[:100]}")

                if tool_name == "create_background_task":
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
        final_resp = await call_llm(messages=messages, tier=clamp_tier(tier))
        final_text = final_resp.choices[0].message.content or "Omlouvám se, vyčerpal jsem limit operací."

        async for event in stream_text(final_text):
            yield event

        await save_assistant_message(
            request.session_id, final_text,
            {"max_iterations": "true", "used_tools": ",".join(used_tools)},
        )
        yield ChatStreamEvent(type="done", metadata={"max_iterations": True, "iterations": effective_max_iterations})
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
