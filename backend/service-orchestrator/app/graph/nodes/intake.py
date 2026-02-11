"""Intake node — intent detection, classification, mandatory clarification.

Replaces the old `clarify` node. Now classifies into 4 task categories
and detects task_action for SINGLE_TASK.
"""

from __future__ import annotations

import json
import logging

from langgraph.types import interrupt

from app.kb.prefetch import fetch_project_context
from app.models import CodingTask, ClarificationQuestion
from app.graph.nodes._helpers import (
    detect_cloud_prompt,
    llm_with_cloud_fallback,
    parse_json_response,
)

logger = logging.getLogger(__name__)


async def intake(state: dict) -> dict:
    """Detect intent, classify task, extract references.

    MANDATORY CLARIFICATION: if LLM is not sure about the goal → interrupt() immediately.
    The agent MUST ask, never guess.

    Returns:
        task_category: advice / single_task / epic / generative
        task_action: respond / code / tracker_ops / mixed (for SINGLE_TASK)
        external_refs: ["UFO-24", "https://..."]
        task_complexity: simple / medium / complex / critical
    """
    task = CodingTask(**state["task"])

    # 1. Fetch project context from KB (best-effort)
    try:
        project_context = await fetch_project_context(
            client_id=task.client_id,
            project_id=task.project_id,
            task_description=task.query,
        )
    except Exception as e:
        logger.warning(
            "KB project context fetch failed for task %s (continuing without): %s: %s",
            task.id, type(e).__name__, e,
        )
        project_context = ""

    # 2. Build environment summary if available
    env_summary = ""
    env_data = state.get("environment")
    if env_data:
        env_summary = f"\nEnvironment: {json.dumps(env_data, default=str)[:500]}"

    # 3. Detect cloud prompt for downstream nodes
    allow_cloud_prompt = detect_cloud_prompt(task.query)

    # 4. Build context section — project/client names (from top-level state) + KB context
    context_section = ""
    identity_parts = []
    client_name = state.get("client_name")
    project_name = state.get("project_name")
    if client_name:
        identity_parts.append(f"Client: {client_name}")
    if project_name:
        identity_parts.append(f"Project: {project_name}")
    if identity_parts:
        context_section += "\n\n## Task Context:\n" + "\n".join(identity_parts)
    if project_context:
        context_section += f"\n\n## Existing Project Context (from KB):\n{project_context[:3000]}"

    # 5. Recent conversation context (last 5 messages for classification)
    chat_history = state.get("chat_history")
    if chat_history and chat_history.get("recent_messages"):
        recent = chat_history["recent_messages"][-5:]
        history_lines = []
        for m in recent:
            label = {"user": "Uživatel", "assistant": "Jervis"}.get(m["role"], m["role"])
            history_lines.append(f"[{label}]: {m['content'][:200]}")
        context_section += "\n\n## Recent Conversation:\n" + "\n".join(history_lines)

    # 6. LLM structured output for classification
    messages = [
        {
            "role": "system",
            "content": (
                "You are a task analysis and routing agent. Your job is to:\n"
                "1. Classify the task into one of 4 categories\n"
                "2. For SINGLE_TASK, determine what action is needed\n"
                "3. Extract external references (ticket IDs, URLs)\n"
                "4. Assess complexity\n"
                "5. Determine if the goal is clear enough to proceed\n\n"
                "Task categories:\n"
                "- ADVICE: Questions, analysis, summaries, writing responses, meeting summaries, "
                "knowledge queries — anything answered directly by LLM + KB\n"
                "- SINGLE_TASK: A single concrete task — may be coding, managerial, analytical, "
                "or planning. Key: ONE identifiable goal.\n"
                "- EPIC: Take an entire epic/group of tasks and execute them in batches\n"
                "- GENERATIVE: Design new epics/tasks/stories from scratch, then execute\n\n"
                "Task actions (for SINGLE_TASK only):\n"
                "- RESPOND: Answer/analysis — no code changes, no tracker updates\n"
                "- CODE: Requires a coding agent to modify code\n"
                "- TRACKER_OPS: Create/update issues in bug tracker\n"
                "- MIXED: Combination of above (e.g., analyze then code then update tracker)\n\n"
                "MANDATORY CLARIFICATION RULE:\n"
                "If the goal is ambiguous, unclear, or could mean multiple things → "
                "set goal_clear=false and provide clarification questions. "
                "The agent MUST ask, NEVER guess.\n"
                "IMPORTANT: The client and project are already known from the task context. "
                "NEVER ask which project, team, or client this is about — that is already provided.\n\n"
                "Respond with JSON:\n"
                "{\n"
                '  "task_category": "advice" | "single_task" | "epic" | "generative",\n'
                '  "task_action": "respond" | "code" | "tracker_ops" | "mixed",\n'
                '  "external_refs": ["UFO-24", "https://..."],\n'
                '  "complexity": "simple" | "medium" | "complex" | "critical",\n'
                '  "goal_clear": true/false,\n'
                '  "clarification_questions": [\n'
                '    {"id": "q1", "question": "...", "options": ["opt1", "opt2"], "required": true}\n'
                "  ],\n"
                '  "reasoning": "brief explanation"\n'
                "}"
            ),
        },
        {
            "role": "user",
            "content": (
                f"Task: {task.query}"
                f"{context_section}"
                f"{env_summary}"
            ),
        },
    ]

    response = await llm_with_cloud_fallback(
        state={**state, "allow_cloud_prompt": allow_cloud_prompt},
        messages=messages, task_type="classification", max_tokens=4096,
    )
    content = response.choices[0].message.content

    # Parse LLM response
    parsed = parse_json_response(content)
    task_category = parsed.get("task_category", "advice")
    task_action = parsed.get("task_action", "respond")
    external_refs = parsed.get("external_refs", [])
    complexity = parsed.get("complexity", "medium")
    goal_clear = parsed.get("goal_clear", True)
    questions_raw = parsed.get("clarification_questions", [])

    logger.info(
        "Intake: category=%s action=%s complexity=%s goal_clear=%s refs=%s kb=%d chars",
        task_category, task_action, complexity, goal_clear,
        external_refs, len(project_context),
    )

    result: dict = {
        "project_context": project_context,
        "task_complexity": complexity,
        "task_category": task_category,
        "task_action": task_action,
        "external_refs": external_refs,
        "allow_cloud_prompt": allow_cloud_prompt,
        "needs_clarification": not goal_clear,
    }

    # MANDATORY CLARIFICATION: if goal is not clear → interrupt immediately
    if not goal_clear and questions_raw:
        questions = [
            ClarificationQuestion(
                id=q.get("id", f"q{i+1}"),
                question=q.get("question", ""),
                options=q.get("options", []),
                required=q.get("required", True),
            )
            for i, q in enumerate(questions_raw)
            if q.get("question")
        ]

        result["clarification_questions"] = [q.model_dump() for q in questions]

        # Format questions for display
        description_lines = ["Potřebuji upřesnění před zahájením:"]
        for q in questions:
            opts = f" ({', '.join(q.options)})" if q.options else ""
            description_lines.append(f"- {q.question}{opts}")

        # Interrupt — graph pauses, Kotlin picks up via SSE/polling
        clarification_response = interrupt({
            "type": "clarification",
            "action": "clarify",
            "description": "\n".join(description_lines),
            "questions": [q.model_dump() for q in questions],
            "task_id": task.id,
        })

        # After resume: store user's answers
        result["clarification_response"] = clarification_response
        logger.info("Intake: resumed with user clarification")

    return result
