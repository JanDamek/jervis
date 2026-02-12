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

_MAX_TOOL_ITERATIONS = 25


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
                "You are Jervis, an AI assistant with access to tools. You MUST gather ALL information BEFORE answering.\n\n"
                "## MANDATORY APPROACH (4-step process):\n\n"
                "**1. UNDERSTAND**: Read the user's question carefully. Check Conversation History for context.\n\n"
                "**2. GATHER**: Use tools to collect ALL relevant information:\n"
                "   **Knowledge Base Tools:**\n"
                "   - get_kb_stats() — Start HERE to see what data exists\n"
                "   - get_indexed_items() — What has been indexed (commits, issues, etc.)\n"
                "   - get_repository_info() — Repository structure, branches, tech stack\n"
                "   - list_project_files() — What files exist in the project\n"
                "   - kb_search(query) — Search for specific content\n"
                "   - web_search(query) — External/current information\n\n"
                "   **Direct Workspace Tools (use when KB is empty/incomplete):**\n"
                "   - list_files(path) — List files/directories in workspace\n"
                "   - read_file(file_path, max_lines) — Read file contents\n"
                "   - find_files(pattern) — Find files by glob pattern (*.py, **/*.kt)\n"
                "   - grep_files(pattern, file_pattern) — Search for text in files\n"
                "   - file_info(path) — Get file metadata (size, modified time)\n\n"
                "   **Git Workspace Tools:**\n"
                "   - git_status() — Modified files, staged changes, current branch\n"
                "   - git_log(limit, branch) — Commit history\n"
                "   - git_diff(commit1, commit2, file_path) — Show differences\n"
                "   - git_show(commit) — Commit details\n"
                "   - git_blame(file_path) — File authorship info\n\n"
                "   **Command Execution:**\n"
                "   - execute_command(command, timeout) — Run safe shell commands (ls, grep, make, npm, pytest, etc.)\n\n"
                "**3. ANALYZE**: Identify gaps:\n"
                "   - Do you have enough information to answer accurately?\n"
                "   - Are there contradictions or ambiguities?\n"
                "   - Is the user's question unclear?\n\n"
                "**4. RESPOND**:\n"
                "   - If you have complete info → Answer with facts from tools\n"
                "   - If there's ambiguity/conflict → Ask user for clarification\n"
                "   - If info is missing → Say 'I don't have this data in KB' (don't guess)\n\n"
                "## CRITICAL RULES:\n"
                "- NEVER answer about code/project from general knowledge\n"
                "- ALWAYS start with get_kb_stats() for project questions\n"
                "- If KB is empty → use workspace tools (list_files, read_file, git_status, etc.)\n"
                "- Use MULTIPLE tools to cross-verify information\n"
                "- For file contents → try kb_search first, fallback to read_file if not found\n"
                "- For git history → try get_recent_commits first, fallback to git_log if empty\n"
                "- Workspace tools work even when KB has no data (during learning phase)\n"
                "- Only ask user when you have conflicting data or genuine uncertainty\n\n"
                "## EXAMPLE:\n"
                "User: 'v čem je aplikace napsaná?'\n"
                "YOU:\n"
                "  1. get_repository_info() → see tech stack\n"
                "  2. list_project_files() → check file extensions\n"
                "  3. Analyze: Both show Kotlin/Python\n"
                "  4. Answer: 'Aplikace je napsaná v Kotlinu (backend) a Pythonu (ML služby). Mám seznam všech souborů.'\n\n"
                "Respond in Czech. Gather EVERYTHING, then answer."
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
        logger.debug("Respond: messages count=%d, total_len=%d", len(messages), sum(len(str(m)) for m in messages))

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

        # Check for tool calls
        tool_calls = getattr(message, "tool_calls", None)
        if not tool_calls or choice.finish_reason == "stop":
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
    final_response = await llm_with_cloud_fallback(
        state={**state, "allow_cloud_prompt": allow_cloud_prompt},
        messages=messages + [{
            "role": "system",
            "content": "Provide your final answer now based on the information gathered. Do not call more tools."
        }],
        task_type="conversational",
        max_tokens=4096,
    )
    answer = final_response.choices[0].message.content or ""
    return {"final_result": answer}
