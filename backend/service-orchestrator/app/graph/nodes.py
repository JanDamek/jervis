"""LangGraph node functions for the orchestrator workflow.

Each function takes OrchestratorState and returns a partial state update.
Orchestrator is the brain – agent is just the executor.

Flow:
    decompose → select_goal → plan_steps → execute_step → evaluate → next_step
                    ↑                                              │
                    └──── advance_goal ←── (more goals) ───────────┘
                                           │
                                           ↓ (all goals done)
                                    git_operations → report
"""

from __future__ import annotations

import fnmatch
import json
import logging
from typing import Any

from langgraph.types import interrupt

from app.agents.job_runner import job_runner
from app.agents.workspace_manager import workspace_manager
from app.config import settings
from app.llm.provider import llm_provider
from app.models import (
    AgentType,
    Complexity,
    CodingStep,
    CodingTask,
    Evaluation,
    Goal,
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


# --- Node: decompose ---

async def decompose(state: dict) -> dict:
    """Decompose user query into goals using LLM."""
    task = CodingTask(**state["task"])

    tier = llm_provider.select_tier(
        task_type="decomposition",
        complexity=Complexity.MEDIUM,
    )

    messages = [
        {
            "role": "system",
            "content": (
                "You are a task decomposition agent. Break down the user's request "
                "into concrete goals. Each goal should be independently achievable.\n"
                "Respond with JSON: {\"goals\": [{\"id\": \"g1\", \"title\": \"...\", "
                "\"description\": \"...\", \"complexity\": \"simple|medium|complex|critical\", "
                "\"dependencies\": []}]}"
            ),
        },
        {"role": "user", "content": task.query},
    ]

    response = await llm_provider.completion(messages=messages, tier=tier)
    content = response.choices[0].message.content

    try:
        parsed = json.loads(content)
        goals = [Goal(**g) for g in parsed.get("goals", [])]
    except (json.JSONDecodeError, KeyError):
        # Fallback: single goal from the entire query
        goals = [
            Goal(
                id="g1",
                title="Execute task",
                description=task.query,
                complexity=Complexity.MEDIUM,
            )
        ]

    logger.info("Decomposed into %d goals", len(goals))

    return {
        "goals": [g.model_dump() for g in goals],
        "current_goal_index": 0,
    }


# --- Node: select_goal ---

def select_goal(state: dict) -> dict:
    """Select the current goal for processing.

    Logs the current goal and validates the index. No state mutation needed –
    the goal is already selected by current_goal_index.
    """
    goals = [Goal(**g) for g in state["goals"]]
    idx = state["current_goal_index"]

    if idx >= len(goals):
        logger.error("Goal index %d out of range (%d goals)", idx, len(goals))
        return {"error": f"Goal index {idx} out of range ({len(goals)} goals)"}

    goal = goals[idx]
    logger.info(
        "Selected goal %d/%d: %s (complexity=%s)",
        idx + 1, len(goals), goal.title, goal.complexity,
    )
    return {}


# --- Node: plan_steps ---

async def plan_steps(state: dict) -> dict:
    """Create execution steps for the current goal."""
    task = CodingTask(**state["task"])
    goals = [Goal(**g) for g in state["goals"]]
    idx = state["current_goal_index"]
    goal = goals[idx]

    tier = llm_provider.select_tier(
        task_type="planning",
        complexity=goal.complexity,
    )

    agent_type = select_agent(goal.complexity, task.agent_preference)

    messages = [
        {
            "role": "system",
            "content": (
                "You are a coding task planner. Create concrete steps for the coding agent.\n"
                "Each step should be a single, focused change.\n"
                "Respond with JSON: {\"steps\": [{\"index\": 0, \"instructions\": \"...\", "
                f"\"agent_type\": \"{agent_type.value}\", \"files\": []}}]}}"
            ),
        },
        {
            "role": "user",
            "content": f"Goal: {goal.title}\nDescription: {goal.description}",
        },
    ]

    response = await llm_provider.completion(messages=messages, tier=tier)
    content = response.choices[0].message.content

    try:
        parsed = json.loads(content)
        steps = [CodingStep(**s) for s in parsed.get("steps", [])]
    except (json.JSONDecodeError, KeyError):
        # Fallback: single step
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

    # Pre-fetch KB context
    kb_context = await _prefetch_kb_safe(
        task_description=step.instructions,
        client_id=task.client_id,
        project_id=task.project_id,
        files=step.files,
    )

    # Prepare workspace (instructions, KB context, agent config)
    await workspace_manager.prepare_workspace(
        task_id=f"{task.id}-step-{step.index}",
        client_id=task.client_id,
        project_id=task.project_id,
        project_path=task.workspace_path,
        instructions=step.instructions,
        files=step.files,
        agent_type=step.agent_type.value,
        kb_context=kb_context,
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


async def _prefetch_kb_safe(
    task_description: str,
    client_id: str,
    project_id: str | None,
    files: list[str],
) -> str:
    """Pre-fetch KB context with error handling."""
    try:
        from app.kb.prefetch import prefetch_kb_context

        return await prefetch_kb_context(
            task_description=task_description,
            client_id=client_id,
            project_id=project_id,
            files=files,
        )
    except Exception as e:
        logger.warning("KB pre-fetch failed, continuing without context: %s", e)
        return ""


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
    """Advance to next goal index."""
    return {"current_goal_index": state.get("current_goal_index", 0) + 1}


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
