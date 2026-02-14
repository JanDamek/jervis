"""Respond node — ADVICE + SINGLE_TASK/respond answers.

Generates answers using LLM + tools (web search, KB search) in an agentic loop.
Max 5 iterations to prevent infinite loops.
"""

from __future__ import annotations

import json
import logging

from app.models import CodingTask
from app.graph.nodes._helpers import llm_with_cloud_fallback, is_error_message
from app.tools.definitions import ALL_RESPOND_TOOLS_FULL
from app.tools.executor import execute_tool

logger = logging.getLogger(__name__)

_MAX_TOOL_ITERATIONS = 8  # Increased from 5 to give agent more room for complex queries


async def respond(state: dict) -> dict:
    """Answer analytical/advisory queries directly using LLM + KB context.

    Used for:
    - ADVICE tasks (meeting summaries, knowledge queries, planning advice)
    - SINGLE_TASK with action=respond (analysis, recommendations)
    """
    task = CodingTask(**state["task"])
    project_context = state.get("project_context", "")
    clarification = state.get("clarification_response")
    evidence = state.get("evidence_pack", {})

    # Build context
    context_parts: list[str] = []

    # Task identity — client/project names from top-level state
    client_name = state.get("client_name")
    project_name = state.get("project_name")
    identity_parts = []
    if client_name:
        identity_parts.append(f"Client: {client_name}")
    if project_name:
        identity_parts.append(f"Project: {project_name}")
    if identity_parts:
        context_parts.append("## Task Context\n" + "\n".join(identity_parts))

    if project_context:
        context_parts.append(f"## Project Context (from Knowledge Base)\n{project_context[:4000]}")

    # Evidence pack KB results
    if evidence:
        kb_results = evidence.get("kb_results", [])
        for kr in kb_results:
            content = kr.get("content", "")
            if content:
                context_parts.append(f"## Knowledge Base\n{content[:4000]}")

        tracker_artifacts = evidence.get("tracker_artifacts", [])
        if tracker_artifacts:
            context_parts.append("## Referenced Items")
            for ta in tracker_artifacts:
                ref = ta.get("ref", "?")
                content = ta.get("content", "")[:1000]
                context_parts.append(f"### {ref}\n{content}")

    # Chat history — full conversation context (summaries + recent)
    chat_history = state.get("chat_history")
    if chat_history:
        history_parts = []
        # Summary blocks (older compressed history)
        for block in (chat_history.get("summary_blocks") or []):
            prefix = "[CHECKPOINT] " if block.get("is_checkpoint") else ""
            history_parts.append(
                f"{prefix}Messages {block['sequence_range']}: {block['summary']}"
            )
        # Recent messages (verbatim) — FILTER OUT ERROR MESSAGES
        for msg in (chat_history.get("recent_messages") or []):
            content = msg.get("content", "")
            # Skip error messages (JSON error objects or error text)
            if is_error_message(content):
                logger.debug("Filtering out error message from chat history: %s", content[:100])
                continue
            label = {"user": "Uživatel", "assistant": "Jervis"}.get(msg["role"], msg["role"])
            history_parts.append(f"{label}: {content}")
        if history_parts:
            context_parts.append("## Conversation History\n" + "\n\n".join(history_parts))

    if clarification:
        context_parts.append(
            f"## User Clarification\n{json.dumps(clarification, default=str, indent=2)}"
        )

    env_data = state.get("environment")
    if env_data:
        context_parts.append(f"## Environment\n{json.dumps(env_data, default=str)[:500]}")

    context_block = "\n\n".join(context_parts) if context_parts else ""

    messages = [
        {
            "role": "system",
            "content": (
                "Jsi Jervis, AI asistent s přístupem k databázi znalostí projektu.\n\n"
                "POSTUP:\n"
                "1. Použij dostupné nástroje k načtení dat\n"
                "2. Analyzuj získané informace\n"
                "3. Odpověz konkrétně s fakty z nástrojů\n\n"
                "PRAVIDLA PRO ČTENÍ:\n"
                "• Vždy NEJDŘÍV načti data nástroji (get_kb_stats, kb_search, read_file, atd.)\n"
                "• NIKDY neodpovídej z obecných znalostí — pouze z dat získaných nástroji\n"
                "• NIKDY neříkej 'potřebuji přístup' — přístup už máš přes dostupné nástroje\n"
                "• Pokud KB neobsahuje data → použij workspace nástroje (list_files, read_file, grep_files)\n\n"
                "PRAVIDLA PRO UČENÍ (CRITICAL!):\n"
                "• Když ti uživatel řekne NOVOU informaci → VŽDY ji ulož pomocí store_knowledge\n"
                "• Vzory učení: 'X je Y', 'X znamená Y', 'zapamatuj si', 'pro příště', 'to je'\n"
                "• Příklady: 'BMS je Brokerage Management System', 'projekt používá Python 3.11'\n"
                "• Po ÚSPĚŠNÉM uložení (✓ Knowledge stored) → IHNED dej finální odpověď\n"
                "• POTVRĎ co jsi se naučil a řekni že to bude dostupné pro budoucí dotazy\n"
                "• NIKDY nevoláš další tools po store_knowledge — jenom odpověz uživateli\n"
                "• NIKDY neříkej jen 'ok' — vždy AKTIVNĚ ulož nové informace do KB\n\n"
                "Odpovídej česky. Buď konkrétní a faktický."
            ),
        },
        {
            "role": "user",
            "content": (
                f"{task.query}"
                f"\n\n{context_block}" if context_block else task.query
            ),
        },
    ]

    # Extract IDs for tool execution
    client_id = state.get("client_id", "")
    project_id = state.get("project_id")
    allow_cloud_prompt = state.get("allow_cloud_prompt", False)

    # Agentic tool-use loop (max 25 iterations)
    iteration = 0
    while iteration < _MAX_TOOL_ITERATIONS:
        iteration += 1
        logger.info("Respond: iteration %d/%d", iteration, _MAX_TOOL_ITERATIONS)

        # Estimate context tokens (rough: 1 token ≈ 4 chars)
        # Need to account for: messages + tools + output space
        message_chars = sum(len(str(m)) for m in messages)
        message_tokens = message_chars // 4

        # Tools add ~2500 tokens (23 tools with descriptions)
        tools_tokens = 2500  # ALL_RESPOND_TOOLS_FULL is always passed

        # Reserve space for output
        output_tokens = 4096

        # Total context needed
        estimated_tokens = message_tokens + tools_tokens + output_tokens
        logger.debug("Respond: messages=%d tokens, tools=%d tokens, output=%d tokens, total=%d tokens",
                     message_tokens, tools_tokens, output_tokens, estimated_tokens)

        # Log full messages array structure
        for i, msg in enumerate(messages):
            role = msg.get("role", "?")
            content_preview = str(msg.get("content", ""))[:200]
            has_tool_calls = "tool_calls" in msg
            has_tool_call_id = "tool_call_id" in msg
            logger.debug(
                "Respond: messages[%d] role=%s, content_len=%d, preview=%s, has_tool_calls=%s, has_tool_call_id=%s",
                i, role, len(str(msg.get("content", ""))), content_preview, has_tool_calls, has_tool_call_id
            )

        response = await llm_with_cloud_fallback(
            state={**state, "allow_cloud_prompt": allow_cloud_prompt},
            messages=messages,
            task_type="conversational",
            context_tokens=estimated_tokens,  # Dynamic tier selection based on conversation length
            max_tokens=4096,
            tools=ALL_RESPOND_TOOLS_FULL,  # Enable all tools (KB, web, git, filesystem)
        )

        choice = response.choices[0]
        message = choice.message

        # DEBUG: Log full response details
        logger.info(
            "Respond: LLM response - finish_reason=%s, has_content=%s, content_len=%d, has_tool_calls=%s",
            choice.finish_reason,
            bool(message.content),
            len(message.content or ""),
            bool(getattr(message, "tool_calls", None)),
        )
        if message.content:
            logger.info("Respond: message.content = %s", message.content[:500])

        # Check for tool calls (OpenAI format)
        tool_calls = getattr(message, "tool_calls", None)

        # WORKAROUND: Ollama qwen3-coder-tool:30b doesn't support native tool_calls
        # It outputs JSON in content instead: {"tool_calls": [...]}
        # Parse and convert to proper format
        if not tool_calls and message.content:
            try:
                content_json = json.loads(message.content.strip())
                if isinstance(content_json, dict) and "tool_calls" in content_json:
                    logger.info("Respond: parsing tool_calls from JSON content (Ollama workaround)")
                    # Convert JSON tool calls to OpenAI-style objects
                    class ToolCall:
                        def __init__(self, tc_dict):
                            self.id = tc_dict.get("id", "")
                            self.type = tc_dict.get("type", "function")
                            class Function:
                                def __init__(self, f_dict):
                                    self.name = f_dict.get("name", "")
                                    self.arguments = json.dumps(f_dict.get("arguments", {}))
                            self.function = Function(tc_dict.get("function", {}))

                    tool_calls = [ToolCall(tc) for tc in content_json["tool_calls"]]
                    message.content = None  # Clear content since we extracted tool calls
                    logger.info("Respond: extracted %d tool calls from JSON", len(tool_calls))
            except (json.JSONDecodeError, KeyError, TypeError) as e:
                logger.debug("Respond: content is not JSON tool_calls format: %s", e)

        # Check if we have tool calls (including parsed from JSON)
        # Only stop if there are truly no tool calls (ignore finish_reason when tools were parsed)
        if not tool_calls:
            # No more tool calls → final answer
            answer = message.content or ""
            logger.info("Respond: final answer after %d iterations (%d chars)", iteration, len(answer))
            return {"final_result": answer}

        # Execute tool calls
        logger.info("Respond: executing %d tool calls", len(tool_calls))
        messages.append(message.model_dump())  # Add assistant message with tool_calls

        for tool_call in tool_calls:
            tool_name = tool_call.function.name
            try:
                arguments = json.loads(tool_call.function.arguments)
            except json.JSONDecodeError:
                arguments = {}

            logger.info("Respond: calling tool %s with args: %s", tool_name, arguments)

            # Execute tool (never raises)
            result = await execute_tool(
                tool_name=tool_name,
                arguments=arguments,
                client_id=client_id,
                project_id=project_id,
            )

            logger.info("Respond: tool %s returned %d chars", tool_name, len(result))
            logger.debug("Respond: tool %s full result: %s", tool_name, result)

            # Add tool result to messages
            tool_result_msg = {
                "role": "tool",
                "tool_call_id": tool_call.id,
                "name": tool_name,
                "content": result,
            }
            messages.append(tool_result_msg)
            logger.debug("Respond: appended tool result message: %s", tool_result_msg)
        # Continue loop - will call LLM again with tool results

    # Max iterations reached → return best effort answer
    logger.warning("Respond: max iterations (%d) reached, forcing final answer", _MAX_TOOL_ITERATIONS)
    final_messages = messages + [{
        "role": "system",
        "content": "Provide your final answer now based on the information gathered. Do not call more tools."
    }]
    # Estimate: messages + no tools + output space
    final_tokens = (sum(len(str(m)) for m in final_messages) // 4) + 4096
    final_response = await llm_with_cloud_fallback(
        state={**state, "allow_cloud_prompt": allow_cloud_prompt},
        messages=final_messages,
        task_type="conversational",
        context_tokens=final_tokens,
        max_tokens=4096,
    )
    answer = final_response.choices[0].message.content or ""
    return {"final_result": answer}
