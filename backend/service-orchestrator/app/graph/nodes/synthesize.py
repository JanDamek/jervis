"""Synthesize node — merge delegation outputs into final response.

Takes AgentOutput results from execute_delegation and:
1. Merges results into a coherent response
2. Performs RAG cross-check against KB (if needed)
3. Translates to response_language for final output
"""

from __future__ import annotations

import json
import logging

from app.models import AgentOutput, CodingTask
from app.graph.nodes._helpers import llm_with_cloud_fallback

logger = logging.getLogger(__name__)


async def synthesize(state: dict) -> dict:
    """Synthesize delegation results into a final coherent response.

    Input state:
        delegation_results, _delegation_outputs, task, response_language

    Output state:
        final_result
    """
    task = CodingTask(**state["task"])
    delegation_results = state.get("delegation_results", {})
    raw_outputs = state.get("_delegation_outputs", [])
    response_language = state.get("response_language", "en")

    if not delegation_results and not raw_outputs:
        logger.warning("Synthesize: no delegation results to merge")
        return {"final_result": "No results from delegations."}

    # Reconstruct AgentOutput objects from serialized dicts
    outputs: list[AgentOutput] = []
    for o in raw_outputs:
        try:
            outputs.append(AgentOutput(**o))
        except Exception as e:
            logger.warning("Failed to parse delegation output: %s", e)

    # If only one delegation with direct result, use it
    if len(outputs) == 1 and outputs[0].success:
        single = outputs[0]
        final_text = single.result

        # Translate if needed
        if response_language and response_language != "en":
            final_text = await _translate_result(
                state, final_text, response_language, task.query,
            )

        return {"final_result": final_text}

    # Multiple delegations or mixed results — synthesize via LLM
    results_block = _build_results_block(outputs, delegation_results)

    # RAG cross-check for verification requests
    verification_needed = any(o.needs_verification for o in outputs)
    kb_cross_check = ""
    if verification_needed:
        kb_cross_check = await _rag_cross_check(state, outputs)

    # Build synthesis prompt
    messages = [
        {
            "role": "system",
            "content": (
                "You are the Jervis orchestrator's synthesis module. "
                "Your task is to merge multiple agent results into a single, "
                "coherent response for the user.\n\n"
                "Rules:\n"
                "- Combine results logically, removing redundancy\n"
                "- If an agent failed, note it briefly but focus on successes\n"
                "- If KB cross-check found contradictions, mention them\n"
                "- Be concise but comprehensive\n"
                "- Output in English (will be translated later if needed)"
            ),
        },
        {
            "role": "user",
            "content": (
                f"Original query: {task.query}\n\n"
                f"Agent results:\n{results_block}\n\n"
                f"{f'KB cross-check results:{chr(10)}{kb_cross_check}' if kb_cross_check else ''}"
                f"\nSynthesize these results into a single coherent response."
            ),
        },
    ]

    try:
        response = await llm_with_cloud_fallback(
            state=state,
            messages=messages,
            task_type="summarization",
            max_tokens=4096,
        )
        synthesized = response.choices[0].message.content or ""
    except Exception as e:
        logger.warning("LLM synthesis failed, using fallback: %s", e)
        synthesized = _fallback_synthesis(outputs, delegation_results)

    # Translate to response language
    if response_language and response_language != "en":
        synthesized = await _translate_result(
            state, synthesized, response_language, task.query,
        )

    logger.info(
        "Synthesize complete: %d outputs merged, %d chars, lang=%s",
        len(outputs), len(synthesized), response_language,
    )

    return {"final_result": synthesized}


def _build_results_block(
    outputs: list[AgentOutput],
    delegation_results: dict,
) -> str:
    """Build a text block from all delegation results.

    Agent outputs are NOT truncated — agents follow a communication protocol
    that produces compact but complete responses. The orchestrator needs
    full content to synthesize correctly.
    """
    parts: list[str] = []

    for output in outputs:
        status = "SUCCESS" if output.success else "FAILED"
        confidence = f"(confidence: {output.confidence:.1f})"

        part = f"### {output.agent_name} [{status}] {confidence}\n"
        part += output.result if output.result else "(no result)"

        if output.changed_files:
            part += f"\nChanged files: {', '.join(output.changed_files)}"
        if output.artifacts:
            part += f"\nArtifacts: {', '.join(output.artifacts)}"

        parts.append(part)

    return "\n\n".join(parts)


async def _rag_cross_check(state: dict, outputs: list[AgentOutput]) -> str:
    """Cross-check agent claims against KB for verification.

    Extracts key claims from outputs, searches KB for contradictions.
    Full KB results are passed to the LLM — no truncation of substance.
    """
    try:
        from app.tools.executor import execute_tool

        client_id = state.get("task", {}).get("client_id", "")
        project_id = state.get("task", {}).get("project_id")

        # Collect full results that need verification
        verification_items: list[tuple[str, str]] = []  # (search_query, full_result)
        for output in outputs:
            if output.needs_verification and output.result:
                # Use first 200 chars as search query (KB search input)
                # but keep full result for context
                search_query = output.result[:200]
                verification_items.append((search_query, output.result))

        if not verification_items:
            return ""

        # Search KB for each claim — max 3 cross-checks
        results: list[str] = []
        for search_query, full_result in verification_items[:3]:
            kb_result = await execute_tool(
                tool_name="kb_search",
                arguments={"query": search_query, "max_results": 3},
                client_id=client_id,
                project_id=project_id,
            )
            if kb_result and "No results" not in kb_result:
                # Full agent result + full KB result — no truncation
                results.append(
                    f"Agent claim:\n{full_result}\n\n"
                    f"KB cross-check result:\n{kb_result}"
                )

        return "\n\n---\n\n".join(results) if results else ""

    except Exception as e:
        logger.debug("RAG cross-check failed: %s", e)
        return ""


async def _translate_result(
    state: dict,
    text: str,
    target_lang: str,
    original_query: str,
) -> str:
    """Translate the final result to the response language."""
    if not text or target_lang == "en":
        return text

    # Language names for the prompt
    lang_names = {
        "cs": "Czech", "sk": "Slovak", "de": "German",
        "es": "Spanish", "fr": "French", "pl": "Polish",
        "it": "Italian", "pt": "Portuguese", "ru": "Russian",
        "ja": "Japanese", "ko": "Korean", "zh": "Chinese",
    }
    lang_name = lang_names.get(target_lang, target_lang)

    messages = [
        {
            "role": "system",
            "content": (
                f"Translate the following text to {lang_name}. "
                f"Maintain the same tone, structure, and technical terminology. "
                f"Do NOT add any commentary — output ONLY the translation."
            ),
        },
        {"role": "user", "content": text},
    ]

    try:
        response = await llm_with_cloud_fallback(
            state=state,
            messages=messages,
            task_type="summarization",
            max_tokens=4096,
        )
        translated = response.choices[0].message.content or text
        return translated
    except Exception as e:
        logger.warning("Translation to %s failed: %s", target_lang, e)
        return text


def _fallback_synthesis(
    outputs: list[AgentOutput],
    delegation_results: dict,
) -> str:
    """Fallback synthesis without LLM — structured summary."""
    parts: list[str] = []
    successful = sum(1 for o in outputs if o.success)
    total = len(outputs)

    parts.append(f"Completed {successful}/{total} delegations.")

    for output in outputs:
        status = "✓" if output.success else "✗"
        result_text = output.result if output.result else "No result"
        parts.append(f"{status} {output.agent_name}: {result_text}")

    all_files = []
    for o in outputs:
        all_files.extend(o.changed_files)

    if all_files:
        parts.append(f"\nChanged files: {', '.join(set(all_files))}")

    return "\n".join(parts)
