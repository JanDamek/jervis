"""Synthesize node — merge agent outputs into a coherent final response.

After all delegations complete, this node:
1. Merges results from all agents
2. Optionally cross-checks against KB (RAG verification)
3. Translates the response to the detected language if needed
4. Sets final_result for the finalize node
"""

from __future__ import annotations

import logging

from app.context.retention_policy import extract_kb_facts, should_persist_to_kb
from app.context.summarizer import summarize_agent_output
from app.graph.nodes._helpers import llm_with_cloud_fallback
from app.models import AgentOutput, CodingTask, ExecutionPlan
from app.tools.kotlin_client import kotlin_client

logger = logging.getLogger(__name__)


async def synthesize(state: dict) -> dict:
    """Merge delegation results into a final response.

    State reads:
        task, execution_plan, delegation_results, response_language,
        delegation_states, completed_delegations
    State writes:
        final_result, artifacts, kb_facts_to_persist
    """
    task = CodingTask(**state["task"])
    delegation_results = state.get("delegation_results", {})
    response_language = state.get("response_language", "en")
    plan_data = state.get("execution_plan", {})

    await _report(task, "Synthesizing results…", 92)

    if not delegation_results:
        return {
            "final_result": "No delegation results to synthesize.",
        }

    # --- Collect all results ---
    result_parts: list[str] = []
    all_artifacts: list[str] = list(state.get("artifacts", []))
    all_changed_files: list[str] = []
    any_failed = False

    for did, result_text in delegation_results.items():
        ds = state.get("delegation_states", {}).get(did, {})
        agent_name = ds.get("agent_name", "unknown") if isinstance(ds, dict) else "unknown"
        status = ds.get("status", "unknown") if isinstance(ds, dict) else "unknown"

        if status == "failed":
            any_failed = True

        result_parts.append(f"### Agent: {agent_name}\n{result_text}")

    combined = "\n\n".join(result_parts)

    # --- Single agent shortcut ---
    if len(delegation_results) == 1:
        # For single-agent results, pass through without re-synthesis
        single_result = next(iter(delegation_results.values()))
        # Extract just the RESULT line if using protocol format
        final_text = _extract_result(single_result)

        if response_language != "en":
            final_text = await _translate(final_text, response_language, state)

        await _report(task, "Done", 95)
        return {
            "final_result": final_text,
            "artifacts": all_artifacts,
        }

    # --- Multi-agent synthesis via LLM ---
    system_prompt = (
        "You are the Orchestrator Synthesizer. Multiple specialist agents have "
        "produced results for a task. Merge their outputs into a single, coherent, "
        "well-structured response.\n\n"
        "Rules:\n"
        "- Combine complementary information, don't just concatenate\n"
        "- Highlight any contradictions between agents\n"
        "- If any agent failed, acknowledge it and explain impact\n"
        "- Be concise but complete\n"
        "- Respond in English (translation happens separately)"
    )

    user_prompt = (
        f"## Original Task\n{task.query}\n\n"
        f"## Agent Results\n{combined}\n\n"
        "Synthesize these results into a coherent final response."
    )

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]

    try:
        response = await llm_with_cloud_fallback(
            state=state,
            messages=messages,
            task_type="synthesis",
            max_tokens=4096,
        )
        synthesized = response.choices[0].message.content or combined
    except Exception as exc:
        logger.warning("Synthesis LLM call failed: %s — using raw concatenation", exc)
        synthesized = combined

    # --- Translate if needed ---
    if response_language != "en":
        synthesized = await _translate(synthesized, response_language, state)

    # --- Extract KB-worthy facts for persistence ---
    kb_facts: list[dict] = []
    for did, result_text in delegation_results.items():
        ds = state.get("delegation_states", {}).get(did, {})
        agent_name = ds.get("agent_name", "unknown") if isinstance(ds, dict) else "unknown"
        # Create a lightweight AgentOutput for retention policy
        output = AgentOutput(
            delegation_id=did,
            agent_name=agent_name,
            success=ds.get("status") == "completed" if isinstance(ds, dict) else False,
            result=result_text,
        )
        if should_persist_to_kb(output):
            kb_facts.extend(extract_kb_facts(output))

    await _report(task, "Synthesis complete", 95)

    result: dict = {
        "final_result": synthesized,
        "artifacts": all_artifacts,
    }
    if kb_facts:
        result["_kb_facts_to_persist"] = kb_facts

    return result


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _extract_result(protocol_text: str) -> str:
    """Extract the RESULT section from protocol-formatted agent output."""
    lines = protocol_text.split("\n")
    result_lines: list[str] = []
    in_result = False

    for line in lines:
        if line.startswith("RESULT: "):
            in_result = True
            result_lines.append(line[8:])
        elif in_result and line.startswith(("CHANGED_FILES:", "ARTIFACTS:", "NEEDS_VERIFICATION:", "CONFIDENCE:", "STATUS:")):
            in_result = False
        elif in_result:
            result_lines.append(line)

    if result_lines:
        return "\n".join(result_lines)

    # Fallback: return the whole text without STATUS/CONFIDENCE lines
    return "\n".join(
        line for line in lines
        if not line.startswith(("STATUS:", "CONFIDENCE:", "NEEDS_VERIFICATION:"))
    ).strip()


async def _translate(text: str, target_lang: str, state: dict) -> str:
    """Translate text to the target language using LLM."""
    lang_names = {
        "cs": "Czech", "en": "English", "de": "German",
        "es": "Spanish", "fr": "French", "sk": "Slovak",
        "pl": "Polish", "it": "Italian", "pt": "Portuguese",
    }
    lang_name = lang_names.get(target_lang, target_lang)

    messages = [
        {
            "role": "system",
            "content": (
                f"Translate the following text to {lang_name}. "
                "Preserve all formatting, technical terms, and code blocks. "
                "Output ONLY the translated text, nothing else."
            ),
        },
        {"role": "user", "content": text},
    ]

    try:
        response = await llm_with_cloud_fallback(
            state=state,
            messages=messages,
            task_type="translation",
            max_tokens=4096,
        )
        return response.choices[0].message.content or text
    except Exception as exc:
        logger.warning("Translation to %s failed: %s", target_lang, exc)
        return text


async def _report(task: CodingTask, message: str, percent: int) -> None:
    """Send progress to Kotlin server."""
    try:
        await kotlin_client.report_progress(
            task_id=task.id,
            client_id=task.client_id,
            node="synthesize",
            message=message,
            percent=percent,
        )
    except Exception:
        pass
