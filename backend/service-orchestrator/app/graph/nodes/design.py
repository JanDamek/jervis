"""GENERATIVE design node — generate epic + task structure from scratch.

Handles GENERATIVE task category: user describes a high-level goal,
LLM generates an epic with tasks + acceptance criteria, then flows
into the EPIC execution pipeline after user approval.

Phase 3 implementation.
"""

from __future__ import annotations

import json
import logging

from langgraph.types import interrupt

from app.models import CodingTask, Goal, Complexity
from app.graph.nodes._helpers import (
    llm_with_cloud_fallback,
    parse_json_response,
)

logger = logging.getLogger(__name__)


async def design(state: dict) -> dict:
    """Generate epic + task structure from high-level description with tool support.

    Steps:
    1. Gather tech stack and architecture info using tools
    2. LLM generates structured epic: goals, acceptance criteria, dependencies
    3. interrupt() for user approval of generated structure
    4. After approval → flows into plan_epic execution

    The user provides a vague/ambitious goal and the orchestrator designs
    the full implementation structure before executing anything.
    """
    task = CodingTask(**state["task"])
    evidence = state.get("evidence_pack", {})

    # Extract IDs for tool execution
    client_id = state.get("client_id", "")
    project_id = state.get("project_id")

    # Import tool definitions and executor
    from app.tools.definitions import (
        TOOL_GET_TECHNOLOGY_STACK,
        TOOL_GET_REPOSITORY_STRUCTURE,
        TOOL_JOERN_QUICK_SCAN,
    )
    from app.tools.executor import execute_tool
    import json

    DESIGN_TOOLS = [
        TOOL_GET_TECHNOLOGY_STACK,
        TOOL_GET_REPOSITORY_STRUCTURE,
        TOOL_JOERN_QUICK_SCAN,  # For understanding existing architecture
    ]

    # Build context from evidence pack
    kb_context = ""
    if evidence:
        kb_results = evidence.get("kb_results", [])
        if kb_results:
            kb_context = "\n".join(
                r.get("content", "")[:500] for r in kb_results[:5]
            )

    messages = [
        {
            "role": "system",
            "content": (
                "You are a software architect and project planner with access to tools. "
                "You MUST understand the existing architecture BEFORE designing new features.\n\n"
                "## MANDATORY APPROACH:\n"
                "1. **UNDERSTAND**: Use tools to learn about the project:\n"
                "   - get_technology_stack() — Know tech constraints and frameworks\n"
                "   - get_repository_structure() — Understand project layout\n"
                "   - joern_quick_scan('callgraph') — See existing architecture patterns\n\n"
                "2. **DESIGN**: Create a complete implementation plan:\n"
                "   - Design features that match existing tech stack\n"
                "   - Follow established architectural patterns\n"
                "   - Each goal should be independently executable and testable\n"
                "   - Order goals by dependencies (prerequisite goals first)\n\n"
                "3. **RESPOND** with JSON:\n"
                "{\n"
                '  "epic_title": "Short title for the epic",\n'
                '  "epic_description": "2-3 sentence overview",\n'
                '  "goals": [\n'
                "    {\n"
                '      "id": "g1",\n'
                '      "title": "Goal title",\n'
                '      "description": "What needs to be done",\n'
                '      "acceptance_criteria": ["criterion 1", "criterion 2"],\n'
                '      "complexity": "simple|medium|complex",\n'
                '      "dependencies": []\n'
                "    }\n"
                "  ]\n"
                "}\n\n"
                "CRITICAL: Understand existing architecture with tools FIRST, then design."
            ),
        },
        {
            "role": "user",
            "content": (
                f"Goal: {task.query}\n\n"
                f"{'Project context:\\n' + kb_context if kb_context else ''}"
            ),
        },
    ]

    # Agentic loop (max 3 iterations for design)
    _MAX_DESIGN_ITERATIONS = 3
    iteration = 0

    while iteration < _MAX_DESIGN_ITERATIONS:
        iteration += 1
        logger.info("Design: iteration %d/%d", iteration, _MAX_DESIGN_ITERATIONS)

        response = await llm_with_cloud_fallback(
            state=state, messages=messages, task_type="planning",
            max_tokens=8192, tools=DESIGN_TOOLS,
        )

        choice = response.choices[0]
        message = choice.message

        # Check for tool calls
        tool_calls = getattr(message, "tool_calls", None)
        if not tool_calls or choice.finish_reason == "stop":
            # No more tool calls → final design
            parsed = parse_json_response(message.content or "")
            break

        # Execute tool calls
        logger.info("Design: executing %d tool calls", len(tool_calls))
        messages.append(message.model_dump())

        for tool_call in tool_calls:
            tool_name = tool_call.function.name
            try:
                arguments = json.loads(tool_call.function.arguments)
            except json.JSONDecodeError:
                arguments = {}

            logger.info("Design: calling tool %s", tool_name)

            result = await execute_tool(
                tool_name=tool_name,
                arguments=arguments,
                client_id=client_id,
                project_id=project_id,
            )

            messages.append({
                "role": "tool",
                "tool_call_id": tool_call.id,
                "name": tool_name,
                "content": result,
            })

    else:
        # Max iterations reached → force final design
        logger.warning("Design: max iterations (%d) reached, forcing design", _MAX_DESIGN_ITERATIONS)
        final_response = await llm_with_cloud_fallback(
            state=state,
            messages=messages + [{
                "role": "system",
                "content": "Provide your final design now. Do not call more tools."
            }],
            task_type="planning",
            max_tokens=8192,
        )
        parsed = parse_json_response(final_response.choices[0].message.content or "")

    epic_title = parsed.get("epic_title", "Generated Epic")
    epic_description = parsed.get("epic_description", task.query)
    raw_goals = parsed.get("goals", [])

    goals = []
    for g in raw_goals:
        try:
            goals.append(Goal(
                id=g.get("id", f"g{len(goals)}"),
                title=g.get("title", ""),
                description=g.get("description", ""),
                complexity=Complexity(g.get("complexity", "medium")),
                dependencies=g.get("dependencies", []),
            ))
        except Exception as e:
            logger.warning("Skipping invalid generated goal: %s (%s)", g, e)

    if not goals:
        goals = [Goal(
            id="g1", title="Execute goal",
            description=task.query, complexity=Complexity.MEDIUM,
        )]

    # Build human-readable summary for approval
    goal_summary = "\n".join(
        f"  {g.id}. {g.title} [{g.complexity.value}]"
        + (f" (depends on: {', '.join(g.dependencies)})" if g.dependencies else "")
        for g in goals
    )

    approval = interrupt({
        "type": "approval_request",
        "action": "generative_design",
        "description": (
            f"Generated plan: {epic_title}\n"
            f"{epic_description}\n\n"
            f"Goals ({len(goals)}):\n{goal_summary}"
        ),
        "task_id": task.id,
        "epic_title": epic_title,
        "goals_count": len(goals),
    })

    if not approval.get("approved", False):
        return {
            "error": "Generated plan rejected by user",
            "final_result": "Generovaný plán zamítnut uživatelem.",
        }

    logger.info("Generative design approved: %s (%d goals)", epic_title, len(goals))

    # Set goals for the epic execution pipeline
    return {
        "goals": [g.model_dump() for g in goals],
        "current_goal_index": 0,
    }
