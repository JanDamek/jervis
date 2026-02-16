"""Git operations node — commit/push with approval gates.

Delegates actual git work to coding agents (they write better commit messages).
Orchestrator only decides WHEN and UNDER WHAT CONDITIONS.
"""

from __future__ import annotations

import logging

from langgraph.types import interrupt

from app.agents.job_runner import job_runner
from app.agents.workspace_manager import workspace_manager
from app.config import settings
from app.tools.kotlin_client import kotlin_client
from app.models import (
    AgentType,
    CodingTask,
    ProjectRules,
    StepResult,
)

logger = logging.getLogger(__name__)


async def git_operations(state: dict) -> dict:
    """Git operations with approval gates. Delegates execution to coding agent."""
    task = CodingTask(**state["task"])
    rules = ProjectRules(**state["rules"])
    step_results = state.get("step_results", [])

    # Check if there are any successful changes
    has_changes = any(StepResult(**r).success for r in step_results)
    if not has_changes:
        return {"branch": None}

    # Only do git operations if we have coding step results with changed files
    has_code_changes = any(
        StepResult(**r).changed_files for r in step_results
    )
    if not has_code_changes:
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

    # Delegate commit to coding agent (ALLOW_GIT=true, non-blocking)
    commit_instructions = (
        f"Commit all current changes on branch '{branch}'.\n"
        f"Rules:\n"
        f"- Commit message prefix: {rules.commit_prefix.format(taskId=task.id)}\n"
        f"- Write a clear, descriptive commit message\n"
        f"- Stage only relevant files (not .jervis/ directory)\n"
        f"- Do NOT push"
    )

    dispatch_info = await job_runner.dispatch_coding_agent(
        task_id=f"{task.id}-git-commit",
        agent_type=AgentType.CLAUDE.value,
        client_id=task.client_id,
        project_id=task.project_id,
        workspace_path=workspace_path,
        allow_git=True,
        instructions_override=commit_instructions,
    )

    # Notify Kotlin server — sets task state to WAITING_FOR_AGENT
    await kotlin_client.notify_agent_dispatched(task.id, dispatch_info["job_name"])

    # Interrupt — watcher resumes when commit job completes
    commit_result = interrupt({
        "type": "waiting_for_agent",
        "job_name": dispatch_info["job_name"],
        "agent_type": dispatch_info["agent_type"],
        "task_id": task.id,
        "workspace_path": workspace_path,
    })

    logger.info("Git commit completed: job=%s success=%s", dispatch_info["job_name"], commit_result.get("success"))

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
        push_dispatch = await job_runner.dispatch_coding_agent(
            task_id=f"{task.id}-git-push",
            agent_type=AgentType.CLAUDE.value,
            client_id=task.client_id,
            project_id=task.project_id,
            workspace_path=workspace_path,
            allow_git=True,
            instructions_override=push_instructions,
        )

        # Notify Kotlin server — sets task state to WAITING_FOR_AGENT
        await kotlin_client.notify_agent_dispatched(task.id, push_dispatch["job_name"])

        # Interrupt — watcher resumes when push job completes
        interrupt({
            "type": "waiting_for_agent",
            "job_name": push_dispatch["job_name"],
            "agent_type": push_dispatch["agent_type"],
            "task_id": task.id,
            "workspace_path": workspace_path,
        })

    return {"branch": branch}
