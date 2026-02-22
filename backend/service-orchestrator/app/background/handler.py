"""Simplified background handler — agentic loop without streaming.

Replaces the old 14-node LangGraph orchestrator for background tasks.
Simple 4-phase flow:
1. Intake: Analyze task, select tools, build context
2. Execute: Agentic loop (LLM → tools → repeat, max 15 iterations)
3. Dispatch: If coding needed → K8s Job via dispatch_coding_agent
4. Finalize: Save result, notify Kotlin, log quality

No SSE streaming — background tasks push status via kotlin_client.
Model escalation: starts with LOCAL_FAST, escalates on failure.

Cloud safety: ONLY escalates to cloud if project rules allow it.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid

from app.background.escalation import EscalationTracker, needs_escalation
from app.background.tools import ALL_BACKGROUND_TOOLS
from app.chat.context import chat_context_assembler
from app.llm.provider import llm_provider, TIER_CONFIG
from app.models import ModelTier, OrchestrateRequest, ProjectRules
from app.tools.executor import execute_tool, _TOOL_EXECUTION_TIMEOUT_S
from app.tools.kotlin_client import kotlin_client

logger = logging.getLogger(__name__)

_MAX_ITERATIONS = 15
_MAX_ESCALATION_RETRIES = 3  # max times to retry with escalated model


async def handle_background(request: OrchestrateRequest) -> dict:
    """Handle a background task: analyze → execute → finalize.

    This is the simplified replacement for the old 14-node LangGraph.

    Args:
        request: OrchestrateRequest from Kotlin BackgroundEngine.

    Returns:
        dict with {success, summary, artifacts, step_results, branch}
    """
    start_time = time.time()
    task_id = request.task_id
    client_id = request.client_id
    project_id = request.project_id
    rules = request.rules

    logger.info(
        "BACKGROUND_START | task_id=%s | client=%s | project=%s | query=%s",
        task_id, client_id, project_id, request.query[:100],
    )

    # Report progress: intake
    await kotlin_client.report_progress(
        task_id=task_id, client_id=client_id,
        node="intake", message="Analyzing task...",
    )

    # --- Build system prompt for background ---
    system_prompt = _build_background_prompt(request)

    # --- Load conversation context if available ---
    messages: list[dict] = [
        {"role": "system", "content": system_prompt},
    ]

    if request.chat_history:
        # Add summaries
        for block in (request.chat_history.summary_blocks or []):
            messages.append({
                "role": "system",
                "content": f"[Chat context] {block.summary}",
            })
        # Add recent messages
        for msg in (request.chat_history.recent_messages or []):
            messages.append({
                "role": msg.role.lower(),
                "content": msg.content,
            })

    messages.append({"role": "user", "content": request.query})

    # --- Escalation tracker ---
    cloud_allowed = any([
        rules.auto_use_anthropic,
        rules.auto_use_openai,
        rules.auto_use_gemini,
    ])
    tracker = EscalationTracker(
        start_tier=ModelTier.LOCAL_FAST,
        cloud_allowed=cloud_allowed,
    )

    # --- Agentic loop ---
    await kotlin_client.report_progress(
        task_id=task_id, client_id=client_id,
        node="execute", message="Executing task...",
    )

    iteration = 0
    total_tool_calls = 0
    tool_parse_failures = 0
    tool_call_history: list[tuple[str, str]] = []
    final_answer = ""
    step_results: list[dict] = []

    while iteration < _MAX_ITERATIONS:
        iteration += 1
        logger.info(
            "Background: iteration %d/%d | tier=%s",
            iteration, _MAX_ITERATIONS, tracker.current_tier.value,
        )

        # Estimate tokens
        message_chars = sum(len(str(m)) for m in messages)
        estimated_tokens = (message_chars // 4) + 3500 + 4096

        # Select tier-appropriate model
        tier = tracker.current_tier
        tier_config = TIER_CONFIG.get(tier, {})

        try:
            response = await llm_provider.completion(
                messages=messages,
                tier=tier,
                max_tokens=4096,
                temperature=0.2,
                tools=ALL_BACKGROUND_TOOLS,
                extra_headers={"X-Priority": "NORMAL"},
            )
        except Exception as e:
            logger.warning("LLM call failed: %s (tier=%s)", e, tier.value)
            if tracker.escalate():
                continue
            final_answer = f"Background task failed: {e}"
            break

        choice = response.choices[0]
        message_obj = choice.message

        # Check for tool calls
        tool_calls = getattr(message_obj, "tool_calls", None)

        # Ollama JSON workaround
        if not tool_calls and message_obj.content:
            tool_calls = _parse_ollama_tool_calls(message_obj)

        if not tool_calls:
            # Final answer
            answer = message_obj.content or ""

            # Escalation check
            if needs_escalation(answer, tool_parse_failures=tool_parse_failures, iteration=iteration):
                if tracker.escalate():
                    messages.append({
                        "role": "system",
                        "content": "Previous attempt was insufficient. Try again with more detail.",
                    })
                    continue

            final_answer = answer
            logger.info(
                "Background: final answer after %d iterations (%d chars)",
                iteration, len(answer),
            )
            break

        # Execute tool calls
        logger.info("Background: executing %d tool calls", len(tool_calls))
        messages.append(message_obj.model_dump())

        for tool_call in tool_calls:
            tool_name = tool_call.function.name
            try:
                arguments = json.loads(tool_call.function.arguments)
            except json.JSONDecodeError:
                arguments = {}
                tool_parse_failures += 1

            total_tool_calls += 1

            try:
                if tool_name == "dispatch_coding_agent":
                    # Special handling: dispatch via chat tool handler
                    from app.chat.handler import _execute_chat_tool
                    result = await asyncio.wait_for(
                        _execute_chat_tool(tool_name, arguments, client_id, project_id),
                        timeout=_TOOL_EXECUTION_TIMEOUT_S,
                    )
                    step_results.append({
                        "step_index": total_tool_calls,
                        "success": "dispatched" in result.lower(),
                        "summary": result,
                        "agent_type": "claude",
                    })
                else:
                    result = await asyncio.wait_for(
                        execute_tool(
                            tool_name=tool_name,
                            arguments=arguments,
                            client_id=client_id,
                            project_id=project_id,
                            processing_mode="BACKGROUND",
                        ),
                        timeout=_TOOL_EXECUTION_TIMEOUT_S,
                    )
            except asyncio.TimeoutError:
                result = f"Error: Tool '{tool_name}' timed out after {_TOOL_EXECUTION_TIMEOUT_S}s."
            except Exception as e:
                result = f"Error executing {tool_name}: {e}"

            messages.append({
                "role": "tool",
                "tool_call_id": tool_call.id,
                "name": tool_name,
                "content": result,
            })

            # Loop detection
            call_key = (tool_name, json.dumps(arguments, sort_keys=True))
            tool_call_history.append(call_key)
            if tool_call_history.count(call_key) >= 2:
                logger.warning("Background: tool loop detected: %s", tool_name)
                messages.append({
                    "role": "system",
                    "content": f"STOP: {tool_name} called repeatedly. Provide final result.",
                })
                break

    # --- Finalize ---
    if not final_answer and iteration >= _MAX_ITERATIONS:
        # Force a final answer
        try:
            final_messages = messages + [{
                "role": "system",
                "content": "Provide your final summary now. Do not call more tools.",
            }]
            final_tokens = (sum(len(str(m)) for m in final_messages) // 4) + 4096
            final_resp = await llm_provider.completion(
                messages=final_messages,
                tier=tracker.current_tier,
                max_tokens=4096,
                temperature=0.2,
            )
            final_answer = final_resp.choices[0].message.content or ""
        except Exception as e:
            final_answer = f"Background task completed with errors: {e}"

    # Quality check
    failed_steps = sum(1 for r in step_results if not r.get("success", True))
    if failed_steps > 0:
        logger.warning(
            "BACKGROUND_QUALITY | %d/%d steps failed | escalation=%s",
            failed_steps, len(step_results), tracker.history_str,
        )

    # Save result to MongoDB
    try:
        seq = await chat_context_assembler.get_next_sequence(task_id)
        await chat_context_assembler.save_message(
            task_id=task_id,
            role="ASSISTANT",
            content=final_answer,
            correlation_id=f"bg-{uuid.uuid4().hex[:8]}",
            sequence=seq,
        )
    except Exception as e:
        logger.warning("Failed to save background result: %s", e)

    elapsed = time.time() - start_time
    success = bool(final_answer) and failed_steps == 0
    logger.info(
        "BACKGROUND_DONE | task_id=%s | success=%s | iterations=%d | tools=%d | "
        "escalation=%s | %.1fs",
        task_id, success, iteration, total_tool_calls, tracker.history_str, elapsed,
    )

    return {
        "success": success,
        "summary": final_answer,
        "artifacts": [],
        "step_results": step_results,
        "branch": None,
        "escalation_path": tracker.history_str,
    }


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _build_background_prompt(request: OrchestrateRequest) -> str:
    """Build system prompt for background tasks."""
    parts = [
        "Jsi Jervis, AI asistent pracující na pozadí bez přímé interakce s uživatelem.",
        "",
        "PRAVIDLA:",
        "• Proveď úkol autonomně — nepoužívej ask_user (uživatel není online)",
        "• Použij dostupné nástroje k analýze a řešení",
        "• Pokud úkol vyžaduje kódování → dispatch_coding_agent",
        "• Pokud potřebuješ data → kb_search, web_search, read_file",
        "• Pokud potřebuješ vytvořit Jira issue → brain_create_issue",
        "• Na konci SHRŇ co jsi udělal ve stručném souhrnu",
        "",
        "ZÁKAZY:",
        "• NIKDY nevolej ask_user — běžíš na pozadí",
        "• NIKDY neříkej co bys udělal — UDĚLEJ to",
        "• NIKDY nehádej — pokud nemáš data, vyhledej je",
    ]

    # Add environment context
    if request.environment:
        parts.append(f"\nEnvironment: {json.dumps(request.environment, default=str)[:500]}")

    # Add project rules
    if request.rules:
        rules_info = []
        if request.rules.require_review:
            rules_info.append("Code review required")
        if request.rules.require_tests:
            rules_info.append("Tests required")
        if rules_info:
            parts.append(f"\nProject rules: {', '.join(rules_info)}")

    return "\n".join(parts)


def _parse_ollama_tool_calls(message):
    """Parse Ollama JSON tool_calls from content (same as chat handler)."""
    try:
        content_json = json.loads(message.content.strip())
        if isinstance(content_json, dict) and "tool_calls" in content_json:
            raw_calls = content_json["tool_calls"]
            if not isinstance(raw_calls, list):
                return None

            class _TC:
                def __init__(self, tc):
                    if not isinstance(tc, dict):
                        raise ValueError("not dict")
                    self.id = tc.get("id", f"call_{uuid.uuid4().hex[:8]}")
                    self.type = tc.get("type", "function")
                    func = tc.get("function")
                    if not isinstance(func, dict) or "name" not in func:
                        raise ValueError("invalid function")

                    class _F:
                        def __init__(self, f):
                            self.name = f["name"]
                            args = f.get("arguments", {})
                            self.arguments = json.dumps(args) if isinstance(args, dict) else str(args)

                    self.function = _F(func)

            validated = []
            for tc in raw_calls:
                try:
                    validated.append(_TC(tc))
                except (ValueError, KeyError, TypeError):
                    pass

            if validated:
                message.content = None
                return validated
    except (json.JSONDecodeError, KeyError, TypeError):
        pass
    return None
