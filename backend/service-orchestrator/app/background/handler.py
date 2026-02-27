"""Simplified background handler — agentic loop without streaming.

Replaces the old 14-node LangGraph orchestrator for background tasks.
5-phase flow (EPIC 2 enhanced):
1. Intake: Analyze task, select tools, build context
2. Plan: LLM analyzes task + guidelines → structured plan (EPIC 2-S4)
3. Execute: Agentic loop (LLM → tools → repeat, max 15 iterations)
4. Dispatch: If coding needed → K8s Job via dispatch_coding_agent
5. Finalize: Save result, notify Kotlin, log quality

No SSE streaming — background tasks push status via kotlin_client.
Model escalation: starts with dynamically selected tier based on context size, escalates on failure.

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
from app.config import settings, estimate_tokens
from app.graph.nodes._helpers import detect_tool_loop
from app.llm.provider import llm_provider, TIER_CONFIG, EscalationPolicy, clamp_tier
from app.models import ModelTier, OrchestrateRequest
from app.tools.executor import execute_tool, _TOOL_EXECUTION_TIMEOUT_S
from app.tools.kotlin_client import kotlin_client
from app.tools.ollama_parsing import extract_tool_calls

logger = logging.getLogger(__name__)

_escalation_policy = EscalationPolicy()

_MAX_ESCALATION_RETRIES = 3  # max times to retry with escalated model

# Background tasks can use higher tiers than chat (no VRAM contention with streaming)
_BG_MAX_TIER = ModelTier.LOCAL_XLARGE


def _estimate_and_select_tier(
    messages: list[dict], tools: list[dict],
) -> tuple[int, ModelTier]:
    """Estimate token count and select appropriate local tier for background.

    Same pattern as chat handler_agentic.estimate_and_select_tier but
    allows up to LOCAL_XLARGE (128k) since background tasks don't compete
    with streaming for GPU VRAM.
    """
    message_tokens = sum(estimate_tokens(str(m)) for m in messages)
    tools_tokens = sum(estimate_tokens(str(t)) for t in tools)
    output_tokens = settings.default_output_tokens
    estimated = message_tokens + tools_tokens + output_tokens
    tier = _escalation_policy.select_local_tier(estimated)
    # Clamp to BG max tier
    tier = clamp_tier(tier, max_tier=_BG_MAX_TIER)
    return estimated, tier


def _detect_context_overflow(response) -> bool:
    """Detect if Ollama returned a context overflow error as text.

    When num_ctx is exceeded, Ollama returns "Operation not allowed" as
    generated text (not as an exception). This function detects that pattern.
    """
    try:
        choice = response.choices[0]
        content = choice.message.content or ""
        # Check for known overflow patterns
        if "operation not allowed" in content.lower():
            # Verify via prompt_tokens if available
            usage = getattr(response, "usage", None)
            if usage:
                prompt_tokens = getattr(usage, "prompt_tokens", 0)
                if prompt_tokens > 0:
                    logger.warning(
                        "CONTEXT_OVERFLOW detected: prompt_tokens=%d, content contains 'Operation not allowed'",
                        prompt_tokens,
                    )
            return True
    except (AttributeError, IndexError):
        pass
    return False


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
    system_prompt = await _build_background_prompt(request)

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

    # --- Dynamic tier selection based on initial context size ---
    initial_estimated, initial_tier = _estimate_and_select_tier(messages, ALL_BACKGROUND_TOOLS)
    logger.info(
        "Background: initial context estimate=%d tokens → tier=%s",
        initial_estimated, initial_tier.value,
    )

    cloud_allowed = any([
        rules.auto_use_anthropic,
        rules.auto_use_openai,
        rules.auto_use_gemini,
    ])
    tracker = EscalationTracker(
        start_tier=initial_tier,
        cloud_allowed=cloud_allowed,
    )

    # --- EPIC 2-S4: Planning phase ---
    plan = None
    try:
        plan = await _run_planning_phase(
            messages=messages[:],  # Copy to avoid mutation
            tier=initial_tier,
            task_id=task_id,
            client_id=client_id,
            request=request,
        )
        if plan:
            # Inject plan into conversation context for agentic loop
            plan_text = _format_plan_for_context(plan)
            messages.append({
                "role": "system",
                "content": f"[Plan] Následuj tento plán:\n{plan_text}",
            })
            logger.info(
                "Background: planning phase completed, %d steps",
                len(plan.get("steps", [])),
            )
            await kotlin_client.report_progress(
                task_id=task_id, client_id=client_id,
                node="plan", message=f"Plan: {plan.get('summary', 'N/A')[:100]}",
            )
    except Exception as e:
        logger.warning("Planning phase failed (non-fatal): %s", e)

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

    while iteration < settings.background_max_iterations:
        iteration += 1
        logger.info(
            "Background: iteration %d/%d | tier=%s",
            iteration, settings.background_max_iterations, tracker.current_tier.value,
        )

        # Re-estimate context size and escalate tier if needed
        estimated_tokens, estimated_tier = _estimate_and_select_tier(messages, ALL_BACKGROUND_TOOLS)
        tier = tracker.current_tier
        tier_config = TIER_CONFIG.get(tier, {})
        current_num_ctx = tier_config.get("num_ctx", 8192)
        if estimated_tokens > current_num_ctx * 0.85:
            logger.info(
                "Background: context grew (%d tokens > %.0f%% of %d), escalating tier",
                estimated_tokens, 85, current_num_ctx,
            )
            if tracker.escalate():
                tier = tracker.current_tier
                tier_config = TIER_CONFIG.get(tier, {})

        try:
            response = await llm_provider.completion(
                messages=messages,
                tier=tier,
                max_tokens=settings.default_output_tokens,
                temperature=0.2,
                tools=ALL_BACKGROUND_TOOLS,
            )
        except Exception as e:
            err_msg = str(e).lower()
            if "cancelled" in err_msg or "client disconnected" in err_msg:
                logger.info(
                    "Background LLM call cancelled (preemption): %s (tier=%s)",
                    e, tier.value,
                )
                final_answer = "Background task aborted: preempted by foreground chat."
                break
            logger.warning("LLM call failed: %s (tier=%s)", e, tier.value)
            if tracker.escalate():
                continue
            final_answer = f"Background task failed: {e}"
            break

        # Detect context overflow (Ollama returns error as text, not exception)
        if _detect_context_overflow(response):
            logger.warning(
                "Background: context overflow detected (tier=%s), escalating",
                tier.value,
            )
            if tracker.escalate():
                continue
            final_answer = "Background task failed: LLM context overflow."
            break

        choice = response.choices[0]
        message_obj = choice.message

        # Check for tool calls (shared Ollama JSON workaround)
        tool_calls, _remaining = extract_tool_calls(message_obj)

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

        loop_break = False
        for tool_call in tool_calls:
            tool_name = tool_call.function.name
            try:
                arguments = json.loads(tool_call.function.arguments)
            except json.JSONDecodeError:
                arguments = {}
                tool_parse_failures += 1

            total_tool_calls += 1

            # Loop detection BEFORE execution — don't waste GPU time on duplicate calls
            loop_reason = detect_tool_loop(tool_call_history, tool_name, arguments)
            if loop_reason:
                logger.warning(
                    "Background: tool loop detected for %s — skipping execution, breaking iteration",
                    tool_name,
                )
                messages.append({
                    "role": "tool",
                    "tool_call_id": tool_call.id,
                    "name": tool_name,
                    "content": f"ERROR: {loop_reason}",
                })
                loop_break = True
                break

            # Semantic duplicate: same search tool called 3+ times (even with different args)
            search_tools = {"brain_search_issues", "kb_search", "web_search", "brain_search_pages"}
            if tool_name in search_tools:
                same_tool_count = sum(1 for name, _ in tool_call_history if name == tool_name)
                if same_tool_count >= 3:
                    logger.warning(
                        "Background: search tool '%s' called %d times — forcing conclusion",
                        tool_name, same_tool_count,
                    )
                    messages.append({
                        "role": "tool",
                        "tool_call_id": tool_call.id,
                        "name": tool_name,
                        "content": (
                            f"ERROR: STOP: {tool_name} already called {same_tool_count} times. "
                            "Use the results you already have. Provide final result NOW."
                        ),
                    })
                    loop_break = True
                    break

            try:
                if tool_name == "dispatch_coding_agent":
                    # EPIC 2-S5: Enhanced dispatch with guidelines, plan, review checklist
                    effective_client = arguments.get("client_id") or client_id
                    effective_project = arguments.get("project_id") or project_id
                    if not effective_client or not effective_project:
                        result = "Chyba: client_id a project_id jsou povinné pro dispatch coding agenta."
                    else:
                        # Build enhanced dispatch context
                        guidelines_text = None
                        review_checklist = None
                        try:
                            from app.context.guidelines_resolver import (
                                resolve_guidelines,
                                format_guidelines_for_coding_agent,
                            )
                            gl = await resolve_guidelines(effective_client, effective_project)
                            guidelines_text = format_guidelines_for_coding_agent(gl) or None
                            review = gl.get("review", {})
                            checklist_items = review.get("checklistItems", [])
                            if checklist_items:
                                review_checklist = [
                                    item.get("label", "") for item in checklist_items
                                    if item.get("enabled", True)
                                ]
                        except Exception as e:
                            logger.debug("Guidelines for coding dispatch: %s", e)

                        dispatch_result = await asyncio.wait_for(
                            kotlin_client.dispatch_coding_agent(
                                task_description=arguments.get("task_description", ""),
                                client_id=effective_client,
                                project_id=effective_project,
                                plan=plan,
                                guidelines_text=guidelines_text,
                                review_checklist=review_checklist,
                            ),
                            timeout=_TOOL_EXECUTION_TIMEOUT_S,
                        )
                        result = f"Coding agent dispatched: {dispatch_result}"
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

        # If tool loop or search saturation detected, stop the outer iteration loop
        if loop_break:
            logger.info("Background: breaking iteration loop due to tool loop/saturation")
            break

    # --- EPIC 3: Code Review Phase (after coding agent dispatch) ---
    # E3-S2: Review with re-dispatch loop (max 2 rounds on REQUEST_CHANGES)
    review_report = None
    coding_dispatched = any(
        r.get("agent_type") == "claude" and r.get("success")
        for r in step_results
    )
    _MAX_REVIEW_ROUNDS = 2
    review_round = 0

    if coding_dispatched and rules and rules.require_review:
        while review_round < _MAX_REVIEW_ROUNDS:
            review_round += 1
            try:
                await kotlin_client.report_progress(
                    task_id=task_id, client_id=client_id,
                    node="review",
                    message=f"Running code review (round {review_round})...",
                )
                review_report = await _run_code_review(
                    task_description=request.query,
                    step_results=step_results,
                    client_id=client_id,
                    project_id=project_id,
                )
                if not review_report:
                    break

                from app.review.review_engine import ReviewVerdict, format_review_report
                review_text = format_review_report(review_report)
                logger.info(
                    "REVIEW_COMPLETE | task_id=%s | round=%d | verdict=%s | score=%d",
                    task_id, review_round, review_report.verdict.value, review_report.score,
                )

                if review_report.verdict == ReviewVerdict.APPROVE:
                    # Review passed — inject result and continue to finalize
                    messages.append({
                        "role": "system",
                        "content": f"[Code Review Result — APPROVED]\n{review_text}",
                    })
                    break

                elif review_report.verdict == ReviewVerdict.REQUEST_CHANGES:
                    if review_round < _MAX_REVIEW_ROUNDS:
                        # E3-S2: Re-dispatch coding agent with review feedback
                        logger.info(
                            "REVIEW_RE_DISPATCH | task_id=%s | round=%d",
                            task_id, review_round,
                        )
                        await kotlin_client.report_progress(
                            task_id=task_id, client_id=client_id,
                            node="review",
                            message=f"Review requested changes — re-dispatching coding agent...",
                        )
                        # Inject review feedback into context for next agentic iteration
                        messages.append({
                            "role": "system",
                            "content": (
                                f"[Code Review Feedback — Round {review_round}]\n"
                                f"Verdict: REQUEST_CHANGES\n{review_text}\n\n"
                                "Fix the issues listed above and re-dispatch the coding agent."
                            ),
                        })
                        # Run a short agentic loop to re-dispatch with feedback
                        re_dispatch_done = False
                        for _ in range(3):
                            try:
                                resp = await llm_provider.completion(
                                    messages=messages,
                                    tier=tracker.current_tier,
                                    max_tokens=settings.default_output_tokens,
                                    temperature=0.2,
                                    tools=ALL_BACKGROUND_TOOLS,
                                )
                                choice = resp.choices[0]
                                tool_calls, _ = extract_tool_calls(choice.message)
                                if not tool_calls:
                                    break
                                messages.append(choice.message.model_dump())
                                for tc in tool_calls:
                                    tn = tc.function.name
                                    try:
                                        args = json.loads(tc.function.arguments)
                                    except json.JSONDecodeError:
                                        args = {}
                                    if tn == "dispatch_coding_agent":
                                        re_dispatch_done = True
                                    res = await asyncio.wait_for(
                                        execute_tool(tn, args, client_id, project_id, "BACKGROUND"),
                                        timeout=_TOOL_EXECUTION_TIMEOUT_S,
                                    )
                                    messages.append({
                                        "role": "tool", "tool_call_id": tc.id,
                                        "name": tn, "content": res,
                                    })
                                    step_results.append({
                                        "step_index": total_tool_calls,
                                        "success": "error" not in res.lower(),
                                        "summary": res[:200],
                                        "agent_type": "claude" if tn == "dispatch_coding_agent" else None,
                                    })
                                    total_tool_calls += 1
                                if re_dispatch_done:
                                    break
                            except Exception as e:
                                logger.warning("Re-dispatch iteration failed: %s", e)
                                break
                        # Continue loop → next review round
                        continue
                    else:
                        # Max rounds reached — escalate
                        final_answer = (
                            f"Code review requested changes after {review_round} rounds "
                            f"(score: {review_report.score}/100).\n\n{review_text}"
                        )
                        break

                elif review_report.verdict == ReviewVerdict.REJECT:
                    # E3-S2: Escalate REJECT to user
                    final_answer = (
                        f"Code review REJECTED (score: {review_report.score}/100).\n\n"
                        f"{review_text}"
                    )
                    break

            except Exception as e:
                logger.warning("Code review phase failed (non-fatal): %s", e)
                break

    # --- Finalize ---
    if not final_answer and iteration >= settings.background_max_iterations:
        # Force a final answer
        try:
            final_messages = messages + [{
                "role": "system",
                "content": "Provide your final summary now. Do not call more tools.",
            }]
            final_tokens = sum(estimate_tokens(str(m)) for m in final_messages) + settings.default_output_tokens
            final_resp = await llm_provider.completion(
                messages=final_messages,
                tier=tracker.current_tier,
                max_tokens=settings.default_output_tokens,
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
            conversation_id=task_id,
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

    # EPIC 9-S4: Log completed action to KB action memory
    try:
        from app.memory.action_log import log_action
        action_type = "CODING_DISPATCH" if coding_dispatched else "BACKGROUND_TASK"
        await log_action(
            action=action_type,
            description=request.task_description[:200],
            result="SUCCESS" if success else "FAILED",
            client_id=request.client_id,
            project_id=request.project_id,
            related_task_id=task_id,
        )
    except Exception:
        pass  # Non-critical

    return {
        "success": success,
        "summary": final_answer,
        "artifacts": [],
        "step_results": step_results,
        "branch": None,
        "escalation_path": tracker.history_str,
        "plan": plan,  # EPIC 2: Include plan in result for audit trail
        "review": review_report.__dict__ if review_report else None,  # EPIC 3
    }


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

async def _build_background_prompt(request: OrchestrateRequest) -> str:
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

    # Add guidelines via proper resolver (cached, non-blocking)
    try:
        from app.context.guidelines_resolver import resolve_guidelines, format_guidelines_for_prompt
        guidelines = await resolve_guidelines(request.client_id, request.project_id)
        gl_text = format_guidelines_for_prompt(guidelines)
        if gl_text:
            parts.append(f"\n{gl_text}")
    except Exception as e:
        logger.debug("Guidelines injection failed for background task: %s", e)

    return "\n".join(parts)


# ---------------------------------------------------------------------------
# EPIC 2-S4: Planning phase
# ---------------------------------------------------------------------------

_PLANNING_PROMPT = """Analyze this task and create a structured execution plan.

Output JSON with these fields:
{
  "summary": "One-line description of what needs to be done",
  "steps": [
    {"description": "Step 1 description", "tool": "tool_name_or_null"},
    {"description": "Step 2 description", "tool": "tool_name_or_null"}
  ],
  "affected_files": ["file1.kt", "file2.py"],
  "risk_level": "LOW|MEDIUM|HIGH",
  "estimated_iterations": 5
}

Rules:
- Keep steps concrete and actionable
- Identify which tools to use for each step
- List files that will likely be modified
- Assess risk: HIGH if touches production config, security, or >5 files
- Be concise — max 8 steps"""


async def _run_planning_phase(
    messages: list[dict],
    tier: ModelTier,
    task_id: str,
    client_id: str,
    request: OrchestrateRequest,
) -> dict | None:
    """Run LLM planning phase before agentic loop.

    Produces a structured plan that guides the execution phase.
    Returns None if planning fails or is not applicable.
    """
    # Add planning instruction
    plan_messages = messages + [
        {"role": "system", "content": _PLANNING_PROMPT},
    ]

    try:
        response = await llm_provider.completion(
            messages=plan_messages,
            tier=tier,
            max_tokens=1024,
            temperature=0.1,
        )
        content = response.choices[0].message.content or ""

        # Try to parse JSON from response
        plan = _extract_json_from_response(content)
        if plan and "steps" in plan:
            return plan

        logger.debug("Planning phase did not produce valid JSON plan")
        return None

    except Exception as e:
        logger.warning("Planning phase LLM call failed: %s", e)
        return None


def _extract_json_from_response(content: str) -> dict | None:
    """Extract JSON object from LLM response (may be wrapped in markdown)."""
    import re

    # Try direct parse
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        pass

    # Try extracting from ```json ... ``` block
    match = re.search(r"```(?:json)?\s*\n?(.*?)\n?\s*```", content, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError:
            pass

    # Try finding first { ... } block
    start = content.find("{")
    end = content.rfind("}")
    if start >= 0 and end > start:
        try:
            return json.loads(content[start : end + 1])
        except json.JSONDecodeError:
            pass

    return None


def _format_plan_for_context(plan: dict) -> str:
    """Format a plan dict into human-readable text for injection into context."""
    lines = []
    if plan.get("summary"):
        lines.append(f"Cíl: {plan['summary']}")

    steps = plan.get("steps", [])
    if steps:
        lines.append("Kroky:")
        for i, step in enumerate(steps, 1):
            desc = step.get("description", "?")
            tool = step.get("tool")
            tool_hint = f" (→ {tool})" if tool else ""
            lines.append(f"  {i}. {desc}{tool_hint}")

    files = plan.get("affected_files", [])
    if files:
        lines.append(f"Soubory: {', '.join(files)}")

    risk = plan.get("risk_level")
    if risk:
        lines.append(f"Riziko: {risk}")

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# EPIC 3: Code Review Phase
# ---------------------------------------------------------------------------

async def _run_code_review(
    task_description: str,
    step_results: list[dict],
    client_id: str,
    project_id: str | None,
) -> "ReviewReport | None":
    """Run code review on coding agent output.

    Loads guidelines, runs static analysis + LLM review, returns ReviewReport.
    Returns None if no diff is available or review is not applicable.
    """
    from app.review.review_engine import (
        run_static_analysis,
        run_llm_review,
        ReviewReport,
    )

    # Extract diff/changed files from step results
    diff = ""
    changed_files: list[str] = []
    for step in step_results:
        summary = step.get("summary", "")
        if "dispatch" in summary.lower() or step.get("agent_type") == "claude":
            # The coding agent result typically contains a summary
            diff += summary + "\n"

    if not diff.strip():
        logger.debug("No diff available for code review")
        return None

    # Load guidelines for review rules
    guidelines: dict = {}
    try:
        from app.context.guidelines_resolver import resolve_guidelines
        guidelines = await resolve_guidelines(client_id, project_id)
    except Exception as e:
        logger.debug("Failed to load guidelines for review: %s", e)

    # Phase 1: Static analysis
    static_passed, static_issues = run_static_analysis(diff, changed_files, guidelines)
    logger.info(
        "REVIEW_STATIC | passed=%s | issues=%d",
        static_passed, len(static_issues),
    )

    # Phase 2: LLM review
    report = await run_llm_review(
        diff=diff,
        task_description=task_description,
        guidelines=guidelines,
        static_issues=static_issues,
    )

    return report

