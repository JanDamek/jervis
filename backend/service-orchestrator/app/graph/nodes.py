"""LangGraph node functions for the orchestrator workflow.

Each function takes OrchestratorState and returns a partial state update.
Orchestrator is the brain – agent is just the executor.

Flow:
    clarify → decompose → select_goal → plan_steps → execute_step → evaluate
                  ↑                                                      │
                  └──── advance_goal ←── (more goals) ──────── next_step ┤
                                          ↓ (all done)                   │
                                   git_operations → report → END   advance_step
                                                                         │
                                                                  execute_step ←┘
"""

from __future__ import annotations

import fnmatch
import json
import logging
import re
from typing import Any

from langgraph.types import interrupt

from app.agents.job_runner import job_runner
from app.agents.workspace_manager import workspace_manager
from app.config import settings
from app.kb.prefetch import fetch_project_context
from app.llm.provider import llm_provider
from app.models import (
    AgentType,
    ClarificationQuestion,
    Complexity,
    CodingStep,
    CodingTask,
    Evaluation,
    Goal,
    GoalSummary,
    ModelTier,
    ProjectRules,
    StepResult,
)

logger = logging.getLogger(__name__)


# --- Agent selection logic ---

def select_agent(complexity: Complexity, preference: str = "auto") -> AgentType:
    """Select coding agent based on task complexity.

    Escalation path: Aider → Claude → OpenHands → Junie
    """
    if preference != "auto":
        return AgentType(preference)

    match complexity:
        case Complexity.SIMPLE:
            return AgentType.AIDER
        case Complexity.MEDIUM:
            return AgentType.CLAUDE
        case Complexity.COMPLEX:
            return AgentType.OPENHANDS
        case Complexity.CRITICAL:
            return AgentType.JUNIE
    return AgentType.CLAUDE


# --- Cloud escalation helpers ---

_CLOUD_KEYWORDS = [
    "use cloud", "použi cloud", "použij cloud",
    "cloud model", "cloud modely",
    "použij anthropic", "použi anthropic",
    "použij openai", "použi openai",
]


def _detect_cloud_prompt(query: str) -> bool:
    """Detect if user explicitly requested cloud model usage."""
    return any(kw in query.lower() for kw in _CLOUD_KEYWORDS)


def _auto_providers(rules: ProjectRules) -> set[str]:
    """Build set of auto-enabled cloud providers from project rules."""
    providers = set()
    if rules.auto_use_anthropic:
        providers.add("anthropic")
    if rules.auto_use_openai:
        providers.add("openai")
    if rules.auto_use_gemini:
        providers.add("gemini")
    return providers


async def _llm_with_cloud_fallback(
    state: dict,
    messages: list,
    context_tokens: int = 0,
    task_type: str = "general",
    max_tokens: int = 8192,
    temperature: float = 0.1,
    tools: list | None = None,
) -> object:
    """Call LLM: local first, cloud fallback with policy checks."""
    rules = ProjectRules(**state["rules"])
    task = CodingTask(**state["task"])
    allow_cloud_prompt = state.get("allow_cloud_prompt", False)
    escalation = llm_provider.escalation

    auto = _auto_providers(rules)
    if allow_cloud_prompt:
        auto = auto | escalation.get_available_providers()

    # Pre-flight: context too large for local?
    if context_tokens > 49_000:
        return await _escalate_to_cloud(
            task, auto, escalation, context_tokens, task_type,
            messages, max_tokens, temperature, tools,
            reason="Context příliš velký pro lokální model (>49k tokenů)",
        )

    # Try local
    local_tier = escalation.select_local_tier(context_tokens)
    try:
        response = await llm_provider.completion(
            messages=messages, tier=local_tier,
            max_tokens=max_tokens, temperature=temperature, tools=tools,
        )
        content = response.choices[0].message.content
        if not content or not content.strip():
            raise ValueError("Empty response from local model")
        return response
    except Exception as e:
        logger.warning("Local LLM failed (tier=%s): %s", local_tier.value, e)
        return await _escalate_to_cloud(
            task, auto, escalation, context_tokens, task_type,
            messages, max_tokens, temperature, tools,
            reason=f"Lokální model selhal: {str(e)[:200]}",
        )


async def _escalate_to_cloud(
    task: CodingTask,
    auto_providers: set[str],
    escalation,
    context_tokens: int,
    task_type: str,
    messages: list,
    max_tokens: int,
    temperature: float,
    tools: list | None,
    reason: str,
) -> object:
    """Escalate to cloud with auto/interrupt logic."""

    # 1. Try auto-enabled providers
    auto_tier = escalation.suggest_cloud_tier(context_tokens, auto_providers, task_type)
    if auto_tier:
        logger.info("Auto-escalating to %s (auto_providers=%s)", auto_tier.value, auto_providers)
        return await llm_provider.completion(
            messages=messages, tier=auto_tier,
            max_tokens=max_tokens, temperature=temperature, tools=tools,
        )

    # 2. Check if any provider is available at all (has API key)
    available_tier = escalation.best_available_cloud_tier(context_tokens, task_type)
    if not available_tier:
        raise RuntimeError(
            f"{reason}. Žádný cloud provider není nakonfigurován (chybí API klíče)."
        )

    # 3. Ask user via interrupt
    tier_label = {
        ModelTier.CLOUD_REASONING: "Anthropic Claude (reasoning)",
        ModelTier.CLOUD_CODING: "OpenAI GPT-4o (code)",
        ModelTier.CLOUD_LARGE_CONTEXT: "Google Gemini (large context)",
        ModelTier.CLOUD_PREMIUM: "Anthropic Opus (premium)",
    }.get(available_tier, available_tier.value)

    approval = interrupt({
        "type": "approval_request",
        "action": "cloud_model",
        "description": f"{reason}\nPovolit použití cloud modelu {tier_label}?",
        "task_id": task.id,
        "cloud_tier": available_tier.value,
    })

    if not approval.get("approved", False):
        raise RuntimeError(f"{reason}. Uživatel zamítl eskalaci na cloud.")

    logger.info("Cloud escalation approved by user → %s", available_tier.value)
    return await llm_provider.completion(
        messages=messages, tier=available_tier,
        max_tokens=max_tokens, temperature=temperature, tools=tools,
    )


# --- Node: clarify ---

async def clarify(state: dict) -> dict:
    """Fetch project context from KB, detect complexity, optionally ask user.

    This node runs BEFORE decompose. It:
    1. Queries KB for project structure, architecture, and conventions
    2. Asks LLM to assess complexity and whether clarification is needed
    3. If clarification needed → interrupt() to ask user questions
    4. If not → passes through with project_context and task_complexity set

    Simple tasks ("fix login bug") pass through without interruption.
    Complex/greenfield tasks ("implement KMP library app") trigger questions.
    """
    task = CodingTask(**state["task"])

    # 1. Fetch project context from KB (code graph, architecture, conventions)
    try:
        project_context = await fetch_project_context(
            client_id=task.client_id,
            project_id=task.project_id,
            task_description=task.query,
        )
    except Exception as e:
        logger.error("KB project context fetch failed for task %s: %s", task.id, e)
        return {"error": f"Knowledge base project context fetch failed: {e}"}

    # 2. Build environment summary if available
    env_summary = ""
    env_data = state.get("environment")
    if env_data:
        env_summary = f"\nEnvironment: {json.dumps(env_data, default=str)[:500]}"

    # 3. Detect cloud prompt for downstream nodes
    allow_cloud_prompt = _detect_cloud_prompt(task.query)

    # 4. Ask LLM to assess complexity and whether clarification is needed
    context_section = ""
    if project_context:
        # Truncate to avoid overflowing context window
        context_section = f"\n\n## Existing Project Context (from KB):\n{project_context[:3000]}"

    messages = [
        {
            "role": "system",
            "content": (
                "You are a task analysis agent. Your job is to:\n"
                "1. Assess the complexity of the user's request (simple/medium/complex/critical)\n"
                "2. Determine if clarification questions are needed before planning\n"
                "3. If needed, generate concise clarification questions\n\n"
                "Rules:\n"
                "- Simple, focused tasks (fix a bug, add a field, rename) → NO clarification needed\n"
                "- Broad, multi-component, or greenfield tasks → generate 2-5 questions\n"
                "- Questions should resolve genuine ambiguity, not ask obvious things\n"
                "- Each question should have suggested options when possible\n"
                "- The task will be executed by coding agents (Claude CLI, Aider) — "
                "they handle the actual code. You are planning the work, not writing code.\n\n"
                "Respond with JSON:\n"
                "{\n"
                '  "needs_clarification": true/false,\n'
                '  "complexity": "simple" | "medium" | "complex" | "critical",\n'
                '  "reasoning": "brief explanation of complexity assessment",\n'
                '  "questions": [\n'
                '    {"id": "q1", "question": "...", "options": ["opt1", "opt2"], "required": true}\n'
                "  ]\n"
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

    response = await _llm_with_cloud_fallback(
        state={**state, "allow_cloud_prompt": allow_cloud_prompt},
        messages=messages, task_type="clarification", max_tokens=4096,
    )
    content = response.choices[0].message.content

    # Parse LLM response
    parsed = _parse_json_response(content)
    needs_clarification = parsed.get("needs_clarification", False)
    complexity = parsed.get("complexity", "medium")
    questions_raw = parsed.get("questions", [])

    logger.info(
        "Clarify: complexity=%s needs_clarification=%s questions=%d kb_context=%d chars",
        complexity, needs_clarification, len(questions_raw), len(project_context),
    )

    result: dict = {
        "project_context": project_context,
        "task_complexity": complexity,
        "allow_cloud_prompt": allow_cloud_prompt,
    }

    if not needs_clarification or not questions_raw:
        # Simple task — pass through without interruption
        return result

    # Complex task — build questions and interrupt for user input
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
    description_lines = ["Clarification needed before planning:"]
    for q in questions:
        opts = f" ({', '.join(q.options)})" if q.options else ""
        description_lines.append(f"- {q.question}{opts}")

    # Interrupt — graph pauses here, Kotlin picks up via SSE/polling
    # Resume value will contain user's answers
    clarification_response = interrupt({
        "type": "clarification",
        "action": "clarify",
        "description": "\n".join(description_lines),
        "questions": [q.model_dump() for q in questions],
        "task_id": task.id,
    })

    # After resume: store user's answers
    result["clarification_response"] = clarification_response
    logger.info("Clarify: resumed with user response")

    return result


# --- Node: decompose ---

async def decompose(state: dict) -> dict:
    """Decompose user query into goals using LLM.

    Context-aware: uses project_context (from KB), task_complexity (from clarify),
    clarification_response (user answers), and environment to produce
    well-ordered goals with dependency declarations.
    """
    task = CodingTask(**state["task"])

    # Use auto-detected complexity from clarify node (or default to MEDIUM)
    raw_complexity = state.get("task_complexity", "medium")
    try:
        complexity = Complexity(raw_complexity)
    except ValueError:
        complexity = Complexity.MEDIUM

    # Build context sections for the prompt
    context_parts: list[str] = []

    # Project context from KB (clarify node populated this)
    project_context = state.get("project_context", "")
    if project_context:
        # Truncate to keep prompt manageable
        context_parts.append(
            f"## Existing Project Context\n{project_context[:3000]}"
        )

    # Clarification answers from user
    clarification = state.get("clarification_response")
    if clarification:
        context_parts.append(
            f"## User Clarification Answers\n{json.dumps(clarification, default=str, indent=2)}"
        )

    # Environment context
    env_data = state.get("environment")
    if env_data:
        context_parts.append(
            f"## Environment\n{json.dumps(env_data, default=str)[:500]}"
        )

    context_block = "\n\n".join(context_parts)

    messages = [
        {
            "role": "system",
            "content": (
                "You are a task decomposition agent. Break down the user's request "
                "into concrete, implementable goals.\n\n"
                "Rules:\n"
                "- Each goal will be executed by a coding agent (Claude CLI or Aider) — "
                "make goals concrete and implementable, not abstract\n"
                "- Order goals by dependency: foundational setup first, then features, "
                "then integration/testing\n"
                "- Use the dependencies field to declare prerequisite goal IDs\n"
                "- Simple tasks may have just 1 goal; complex tasks can have 5-10+\n"
                "- Each goal should be independently testable when its dependencies are met\n"
                "- Assign realistic complexity per goal (simple/medium/complex/critical)\n\n"
                "Respond with JSON:\n"
                "{\n"
                '  "goals": [\n'
                '    {\n'
                '      "id": "g1",\n'
                '      "title": "Short descriptive title",\n'
                '      "description": "Detailed instructions for the coding agent",\n'
                '      "complexity": "simple|medium|complex|critical",\n'
                '      "dependencies": []  // IDs of goals that must complete first\n'
                "    }\n"
                "  ]\n"
                "}"
            ),
        },
        {
            "role": "user",
            "content": (
                f"Task: {task.query}"
                + (f"\n\n{context_block}" if context_block else "")
            ),
        },
    ]

    response = await _llm_with_cloud_fallback(
        state=state, messages=messages, task_type="decomposition", max_tokens=8192,
    )
    content = response.choices[0].message.content

    parsed = _parse_json_response(content)
    raw_goals = parsed.get("goals", [])

    if raw_goals:
        goals = []
        for g in raw_goals:
            try:
                goals.append(Goal(**g))
            except Exception as e:
                logger.warning("Skipping invalid goal: %s (%s)", g, e)
    else:
        # Fallback: single goal from the entire query
        goals = [
            Goal(
                id="g1",
                title="Execute task",
                description=task.query,
                complexity=complexity,
            )
        ]

    logger.info("Decomposed into %d goals (complexity=%s)", len(goals), complexity)

    return {
        "goals": [g.model_dump() for g in goals],
        "current_goal_index": 0,
    }


# --- Node: select_goal ---

def select_goal(state: dict) -> dict:
    """Select the current goal for processing with dependency validation.

    Checks whether the current goal's dependencies have been completed
    (present in goal_summaries). If not, attempts to swap with a later goal
    whose dependencies are all met. Falls through best-effort if no swap found.
    """
    goals = [Goal(**g) for g in state["goals"]]
    idx = state["current_goal_index"]

    if idx >= len(goals):
        logger.error("Goal index %d out of range (%d goals)", idx, len(goals))
        return {"error": f"Goal index {idx} out of range ({len(goals)} goals)"}

    # Build set of completed goal IDs
    completed_ids = {
        gs.get("goal_id") for gs in state.get("goal_summaries", [])
    }

    goal = goals[idx]

    # Check dependencies
    unmet = [dep for dep in goal.dependencies if dep not in completed_ids]
    if unmet:
        logger.warning(
            "Goal %s has unmet dependencies: %s. Trying to swap.",
            goal.id, unmet,
        )

        # Try to find a later goal with all dependencies met
        swap_idx = None
        for candidate_idx in range(idx + 1, len(goals)):
            candidate = goals[candidate_idx]
            candidate_unmet = [
                d for d in candidate.dependencies if d not in completed_ids
            ]
            if not candidate_unmet:
                swap_idx = candidate_idx
                break

        if swap_idx is not None:
            # Swap goals in the list
            goals[idx], goals[swap_idx] = goals[swap_idx], goals[idx]
            goal = goals[idx]
            logger.info(
                "Swapped goal %d with %d: now executing %s",
                idx, swap_idx, goal.title,
            )
            return {"goals": [g.model_dump() for g in goals]}

        # No swap possible — continue best-effort with warning
        logger.warning(
            "Cannot resolve dependencies for goal %s — proceeding best-effort",
            goal.id,
        )

    logger.info(
        "Selected goal %d/%d: %s (complexity=%s)",
        idx + 1, len(goals), goal.title, goal.complexity,
    )
    return {}


# --- Node: plan_steps ---

async def plan_steps(state: dict) -> dict:
    """Create execution steps for the current goal.

    Context-aware: includes cross-goal context (previously completed goals)
    and project context from KB so the LLM can plan steps that integrate
    with existing code and prior work.
    """
    task = CodingTask(**state["task"])
    goals = [Goal(**g) for g in state["goals"]]
    idx = state["current_goal_index"]
    goal = goals[idx]

    agent_type = select_agent(goal.complexity, task.agent_preference)

    # Build context sections
    context_parts: list[str] = []

    # Cross-goal context: what was already done
    goal_summaries = state.get("goal_summaries", [])
    if goal_summaries:
        context_parts.append("## Previously Completed Goals")
        for gs in goal_summaries:
            summary = GoalSummary(**gs)
            files_str = ", ".join(summary.changed_files[:10]) if summary.changed_files else "none"
            context_parts.append(
                f"- **{summary.title}**: {summary.summary} (files: {files_str})"
            )

    # Project context from KB (truncated for planning)
    project_context = state.get("project_context", "")
    if project_context:
        context_parts.append(
            f"\n## Project Context\n{project_context[:2000]}"
        )

    context_block = "\n".join(context_parts)

    messages = [
        {
            "role": "system",
            "content": (
                "You are a coding task planner. Create concrete, detailed steps "
                "for a coding agent to execute.\n\n"
                "Rules:\n"
                "- Each step should be a single, focused change that a coding agent "
                "(Claude CLI or Aider) will execute\n"
                "- Instructions must be specific enough for the agent to implement "
                "without further clarification\n"
                "- Include relevant file paths in the files array when known\n"
                "- Steps should be ordered logically (create before use, etc.)\n\n"
                "Respond with JSON:\n"
                "{\n"
                '  "steps": [\n'
                '    {\n'
                '      "index": 0,\n'
                '      "instructions": "Detailed instructions for the coding agent...",\n'
                f'      "agent_type": "{agent_type.value}",\n'
                '      "files": ["path/to/file.kt"]\n'
                "    }\n"
                "  ]\n"
                "}"
            ),
        },
        {
            "role": "user",
            "content": (
                f"Goal: {goal.title}\n"
                f"Description: {goal.description}"
                + (f"\n\n{context_block}" if context_block else "")
            ),
        },
    ]

    response = await _llm_with_cloud_fallback(
        state=state, messages=messages, task_type="planning", max_tokens=8192,
    )
    content = response.choices[0].message.content

    # Robust JSON parsing with fallbacks
    parsed = _parse_json_response(content)
    raw_steps = parsed.get("steps", [])

    steps: list[CodingStep] = []
    for s in raw_steps:
        try:
            step = CodingStep(**s)
            # Validate: skip steps with empty instructions
            if step.instructions.strip():
                steps.append(step)
        except Exception as e:
            logger.warning("Skipping invalid step: %s (%s)", s, e)

    if not steps:
        # Fallback: single step with the entire goal description
        steps = [
            CodingStep(
                index=0,
                instructions=goal.description,
                agent_type=agent_type,
            )
        ]

    logger.info("Planned %d steps for goal %s", len(steps), goal.id)

    return {
        "steps": [s.model_dump() for s in steps],
        "current_step_index": 0,
    }


# --- Node: execute_step ---

async def execute_step(state: dict) -> dict:
    """Execute one coding step via K8s Job."""
    task = CodingTask(**state["task"])
    rules = ProjectRules(**state["rules"])
    steps = [CodingStep(**s) for s in state["steps"]]
    idx = state["current_step_index"]
    step = steps[idx]

    # Pre-fetch KB context (fail on error)
    try:
        kb_context = await _prefetch_kb_context(
            task_description=step.instructions,
            client_id=task.client_id,
            project_id=task.project_id,
            files=step.files,
        )
    except Exception as e:
        logger.error("KB pre-fetch failed for task %s: %s", task.id, e)
        return {"error": f"Knowledge base pre-fetch failed: {e}"}

    # Prepare workspace (instructions, KB context, agent config, environment)
    await workspace_manager.prepare_workspace(
        task_id=f"{task.id}-step-{step.index}",
        client_id=task.client_id,
        project_id=task.project_id,
        project_path=task.workspace_path,
        instructions=step.instructions,
        files=step.files,
        agent_type=step.agent_type.value,
        kb_context=kb_context,
        environment_context=state.get("environment"),
    )

    # Run coding agent as K8s Job (ALLOW_GIT=false by default)
    result = await job_runner.run_coding_agent(
        task_id=f"{task.id}-step-{step.index}",
        agent_type=step.agent_type.value,
        client_id=task.client_id,
        project_id=task.project_id,
        workspace_path=f"{settings.data_root}/{task.workspace_path}",
    )

    step_result = StepResult(
        step_index=step.index,
        success=result.get("success", False),
        summary=result.get("summary", "No result"),
        agent_type=step.agent_type.value,
        changed_files=result.get("changedFiles", []),
    )

    # Append to results
    existing_results = list(state.get("step_results", []))
    existing_results.append(step_result.model_dump())

    return {"step_results": existing_results}


async def _prefetch_kb_context(
    task_description: str,
    client_id: str,
    project_id: str | None,
    files: list[str],
) -> str:
    """Pre-fetch KB context. Raises if KB is unavailable."""
    from app.kb.prefetch import prefetch_kb_context
    return await prefetch_kb_context(
        task_description=task_description,
        client_id=client_id,
        project_id=project_id,
        files=files,
    )


# --- Node: evaluate ---

async def evaluate(state: dict) -> dict:
    """Evaluate the result of the last step."""
    task = CodingTask(**state["task"])
    rules = ProjectRules(**state["rules"])
    step_results = state.get("step_results", [])

    if not step_results:
        return {"error": "No step results to evaluate"}

    last_result = StepResult(**step_results[-1])
    checks: list[str] = []

    if not last_result.success:
        checks.append(f"FAILED: Step {last_result.step_index} failed: {last_result.summary}")

    # Check forbidden files using fnmatch
    for f in last_result.changed_files:
        for pattern in rules.forbidden_files:
            if fnmatch.fnmatch(f, pattern):
                checks.append(f"BLOCKED: Changed forbidden file: {f}")

    # Check max file count
    if len(last_result.changed_files) > rules.max_changed_files:
        checks.append(
            f"WARNING: Changed {len(last_result.changed_files)} files "
            f"(max {rules.max_changed_files})"
        )

    acceptable = not any(c.startswith("BLOCKED") or c.startswith("FAILED") for c in checks)

    evaluation = Evaluation(acceptable=acceptable, checks=checks)

    logger.info(
        "Evaluation: acceptable=%s checks=%s",
        evaluation.acceptable,
        evaluation.checks,
    )

    return {"evaluation": evaluation.model_dump()}


# --- Node: next_step (conditional router) ---

def next_step(state: dict) -> str:
    """Route to next step, next goal, git operations, or report.

    Returns:
        "execute_step" — more steps in current goal
        "advance_goal" — current goal done, more goals remain
        "git_operations" — all goals done, proceed to git
        "report" — evaluation failed, skip to report
    """
    steps = state.get("steps", [])
    current_step = state.get("current_step_index", 0)
    goals = state.get("goals", [])
    current_goal = state.get("current_goal_index", 0)
    evaluation = state.get("evaluation", {})

    # If evaluation failed, go to report (skip remaining steps)
    if evaluation and not evaluation.get("acceptable", True):
        return "report"

    # More steps in current goal?
    if current_step + 1 < len(steps):
        return "execute_step"

    # More goals?
    if current_goal + 1 < len(goals):
        return "advance_goal"

    # All goals done – proceed to git
    return "git_operations"


def advance_step(state: dict) -> dict:
    """Advance to next step index."""
    return {"current_step_index": state.get("current_step_index", 0) + 1}


def advance_goal(state: dict) -> dict:
    """Advance to next goal index and build GoalSummary for cross-goal context.

    Collects results from steps of the current goal (the last N step_results
    where N = number of steps planned for this goal) and creates a summary
    that subsequent goals can reference.
    """
    current_idx = state.get("current_goal_index", 0)
    goals = [Goal(**g) for g in state.get("goals", [])]
    step_results = [StepResult(**r) for r in state.get("step_results", [])]
    steps_for_goal = state.get("steps", [])

    # Collect results from steps of the current goal
    num_steps = len(steps_for_goal)
    recent_results = step_results[-num_steps:] if num_steps > 0 else []

    # Build summary
    changed_files: list[str] = []
    summaries: list[str] = []
    for r in recent_results:
        if r.success:
            summaries.append(r.summary)
        changed_files.extend(r.changed_files)

    goal = goals[current_idx] if current_idx < len(goals) else None
    goal_summary = GoalSummary(
        goal_id=goal.id if goal else f"g{current_idx}",
        title=goal.title if goal else "Unknown",
        summary="; ".join(summaries) if summaries else "No successful steps",
        changed_files=list(set(changed_files)),
    )

    existing_summaries = list(state.get("goal_summaries", []))
    existing_summaries.append(goal_summary.model_dump())

    logger.info(
        "Advancing from goal %d to %d, summary: %s",
        current_idx + 1, current_idx + 2, goal_summary.summary[:100],
    )

    return {
        "current_goal_index": current_idx + 1,
        "goal_summaries": existing_summaries,
    }


# --- Node: git_operations ---

async def git_operations(state: dict) -> dict:
    """Orchestrator DECIDES about git, DELEGATES execution to agent.

    Coding agents write better commit messages and handle staging properly.
    Orchestrator only decides WHEN and UNDER WHAT CONDITIONS.

    Uses LangGraph interrupt() to pause execution when user approval
    is required. The graph saves its checkpoint and the SSE stream
    delivers the approval request to the UI. When the user responds,
    POST /approve resumes the graph via Command(resume=...).
    """
    task = CodingTask(**state["task"])
    rules = ProjectRules(**state["rules"])
    step_results = state.get("step_results", [])

    # Check if there are any successful changes
    has_changes = any(StepResult(**r).success for r in step_results)
    if not has_changes:
        return {"branch": None}

    branch = rules.branch_naming.format(taskId=task.id)
    changed_files = []
    for r in step_results:
        changed_files.extend(StepResult(**r).changed_files)

    workspace_path = f"{settings.data_root}/{task.workspace_path}"

    # --- COMMIT approval gate ---
    if rules.require_approval_commit:
        approval = interrupt({
            "type": "approval_request",
            "action": "commit",
            "description": f"Commit changes to branch '{branch}'",
            "branch": branch,
            "task_id": task.id,
            "changed_files": list(set(changed_files)),
        })
        if not approval.get("approved", False):
            logger.info(
                "Commit rejected by user: %s", approval.get("reason", "")
            )
            return {"branch": None}

    # Prepare workspace for git delegation mode
    workspace_manager.prepare_git_workspace(
        workspace_path=workspace_path,
        client_id=task.client_id,
        project_id=task.project_id,
    )

    # Delegate commit to coding agent (ALLOW_GIT=true)
    commit_instructions = (
        f"Commit all current changes on branch '{branch}'.\n"
        f"Rules:\n"
        f"- Commit message prefix: {rules.commit_prefix.format(taskId=task.id)}\n"
        f"- Write a clear, descriptive commit message\n"
        f"- Stage only relevant files (not .jervis/ directory)\n"
        f"- Do NOT push"
    )

    await job_runner.run_coding_agent(
        task_id=f"{task.id}-git-commit",
        agent_type=AgentType.CLAUDE.value,
        client_id=task.client_id,
        project_id=task.project_id,
        workspace_path=workspace_path,
        allow_git=True,
        instructions_override=commit_instructions,
    )

    # --- PUSH approval gate ---
    if rules.auto_push:
        if rules.require_approval_push:
            approval = interrupt({
                "type": "approval_request",
                "action": "push",
                "description": f"Push branch '{branch}' to origin",
                "branch": branch,
                "task_id": task.id,
            })
            if not approval.get("approved", False):
                logger.info(
                    "Push rejected by user: %s", approval.get("reason", "")
                )
                return {"branch": branch}

        push_instructions = (
            f"Push branch '{branch}' to origin. Do NOT force push."
        )
        await job_runner.run_coding_agent(
            task_id=f"{task.id}-git-push",
            agent_type=AgentType.CLAUDE.value,
            client_id=task.client_id,
            project_id=task.project_id,
            workspace_path=workspace_path,
            allow_git=True,
            instructions_override=push_instructions,
        )

    return {"branch": branch}


# --- Node: report ---

async def report(state: dict) -> dict:
    """Generate final report."""
    task = CodingTask(**state["task"])
    step_results = [StepResult(**r) for r in state.get("step_results", [])]
    branch = state.get("branch")

    successful = sum(1 for r in step_results if r.success)
    total = len(step_results)

    artifacts = []
    for r in step_results:
        artifacts.extend(r.changed_files)

    summary = (
        f"Task {task.id}: {successful}/{total} steps completed successfully."
    )
    if branch:
        summary += f" Branch: {branch}"

    return {
        "final_result": summary,
        "artifacts": list(set(artifacts)),
    }


# --- Helpers ---

def _parse_json_response(content: str) -> dict:
    """Parse JSON from LLM response, handling markdown code blocks.

    LLMs often wrap JSON in ```json ... ``` blocks. This helper
    tries direct parse first, then extracts from code blocks.
    """
    # 1. Try direct JSON parse
    try:
        return json.loads(content)
    except (json.JSONDecodeError, TypeError):
        pass

    # 2. Try extracting from markdown code block
    match = re.search(r"```(?:json)?\s*\n?(.*?)\n?\s*```", content, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except (json.JSONDecodeError, TypeError):
            pass

    # 3. Try finding JSON object in the content
    # Look for the outermost { ... } pair
    brace_start = content.find("{")
    brace_end = content.rfind("}")
    if brace_start != -1 and brace_end > brace_start:
        try:
            return json.loads(content[brace_start:brace_end + 1])
        except (json.JSONDecodeError, TypeError):
            pass

    logger.warning("Failed to parse JSON from LLM response: %s", content[:200])
    return {}
