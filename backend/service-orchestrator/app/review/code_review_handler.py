"""Code review handler — runs after coding agent creates MR/PR.

Orchestrates: diff extraction → static analysis → LLM review → MR comment.
If BLOCKERs found and within max rounds → creates fix task for coding agent.

Called from AgentTaskWatcher after successful MR creation.
"""

from __future__ import annotations

import logging
import subprocess

from app.review.review_engine import (
    ReviewVerdict,
    format_review_report,
    run_llm_review,
    run_static_analysis,
)
from app.tools.kotlin_client import kotlin_client

logger = logging.getLogger(__name__)

MAX_REVIEW_ROUNDS = 2


async def run_code_review(
    task_id: str,
    workspace_path: str,
    mr_url: str,
    task_content: str,
    client_id: str,
    project_id: str | None,
    source_urn: str = "",
    review_round: int = 1,
) -> dict:
    """Run code review on a coding agent's output and post results to MR.

    Args:
        task_id: The coding task ID.
        workspace_path: Path to the git workspace on shared PVC.
        mr_url: MR/PR URL for posting comments.
        task_content: Original task description (for scope checking).
        client_id: Client ID for guidelines resolution.
        project_id: Project ID for guidelines resolution.
        source_urn: Source URN of the task (for fix task identification).
        review_round: Current review-fix cycle round (1-based).

    Returns:
        dict with verdict, has_blockers, round, posted.
    """
    logger.info(
        "CODE_REVIEW_START | task=%s | round=%d/%d | mr=%s",
        task_id, review_round, MAX_REVIEW_ROUNDS, mr_url,
    )

    # 1. Get diff from workspace
    diff = _get_workspace_diff(workspace_path)
    if not diff:
        logger.warning("No diff found in workspace %s — skipping review", workspace_path)
        return {"verdict": "APPROVE", "has_blockers": False, "round": review_round, "posted": False}

    changed_files = _get_changed_files(workspace_path)

    # 2. Get merged guidelines
    guidelines = {}
    try:
        guidelines = await kotlin_client.get_merged_guidelines(
            client_id=client_id,
            project_id=project_id,
        )
    except Exception as e:
        logger.debug("Guidelines fetch failed (non-fatal): %s", e)

    # 3. Run static analysis
    static_passed, static_issues = run_static_analysis(diff, changed_files, guidelines)

    # 4. Run LLM review
    report = await run_llm_review(
        diff=diff,
        task_description=task_content,
        guidelines=guidelines,
        static_issues=static_issues,
    )

    # 5. Format and post comment on MR
    formatted = format_review_report(report)

    blocker_issues = [i for i in report.issues if i.severity.value == "BLOCKER"]
    has_blockers = len(blocker_issues) > 0 or not static_passed

    # Add round info header
    header = f"### Jervis Code Review (Round {review_round}/{MAX_REVIEW_ROUNDS})\n\n"
    comment_body = header + formatted

    posted = False
    try:
        posted = await kotlin_client.post_mr_comment(
            task_id=task_id,
            comment=comment_body,
            merge_request_url=mr_url,
        )
        if posted:
            logger.info("CODE_REVIEW_POSTED | task=%s | verdict=%s", task_id, report.verdict.value)
        else:
            logger.warning("CODE_REVIEW_POST_FAILED | task=%s", task_id)
    except Exception as e:
        logger.warning("Failed to post review comment for task %s: %s", task_id, e)

    # 6. If BLOCKERs and within round limit → create fix task
    if has_blockers and report.verdict in (ReviewVerdict.REQUEST_CHANGES, ReviewVerdict.REJECT):
        if review_round < MAX_REVIEW_ROUNDS:
            try:
                await _create_fix_task(
                    original_task_id=task_id,
                    task_content=task_content,
                    blocker_issues=blocker_issues,
                    static_issues=[i for i in static_issues if "[BLOCKER]" in i],
                    mr_url=mr_url,
                    client_id=client_id,
                    project_id=project_id,
                    review_round=review_round,
                    workspace_path=workspace_path,
                )
            except Exception as e:
                logger.warning("Failed to create fix task for %s: %s", task_id, e)
        else:
            # Max rounds reached — escalation note
            escalation = (
                f"\n\n---\n**Dosažen limit {MAX_REVIEW_ROUNDS} kol review.** "
                "Zbývající problémy vyžadují manuální posouzení."
            )
            try:
                await kotlin_client.post_mr_comment(
                    task_id=task_id,
                    comment=escalation,
                    merge_request_url=mr_url,
                )
            except Exception:
                pass

    logger.info(
        "CODE_REVIEW_DONE | task=%s | verdict=%s | blockers=%d | round=%d",
        task_id, report.verdict.value, len(blocker_issues), review_round,
    )

    return {
        "verdict": report.verdict.value,
        "has_blockers": has_blockers,
        "round": review_round,
        "posted": posted,
    }


async def _create_fix_task(
    original_task_id: str,
    task_content: str,
    blocker_issues: list,
    static_issues: list[str],
    mr_url: str,
    client_id: str,
    project_id: str | None,
    review_round: int,
    workspace_path: str,
) -> None:
    """Create a new coding task to fix BLOCKERs found in code review."""
    import httpx
    from app.config import settings

    # Build fix instructions
    issues_text = ""
    for issue in blocker_issues:
        loc = issue.file
        if issue.line:
            loc += f":{issue.line}"
        issues_text += f"- [{loc}] {issue.message}"
        if issue.suggestion:
            issues_text += f"\n  Fix: {issue.suggestion}"
        issues_text += "\n"

    for issue in static_issues:
        issues_text += f"- {issue}\n"

    fix_instructions = (
        f"## Code Review Fix (Round {review_round + 1})\n\n"
        f"The previous code review found BLOCKER issues that must be fixed.\n"
        f"Fix ONLY the issues listed below. Do NOT make other changes.\n\n"
        f"### Original Task\n{task_content[:1000]}\n\n"
        f"### Issues to Fix\n{issues_text}\n\n"
        f"### MR/PR\n{mr_url}\n"
    )

    # Create coding task via Kotlin internal API
    try:
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.post(
                f"{settings.kotlin_server_url}/internal/dispatch-coding-agent",
                json={
                    "taskDescription": fix_instructions,
                    "clientId": client_id,
                    "projectId": project_id,
                    "sourceUrn": f"code-review-fix:{original_task_id}",
                    "reviewRound": review_round + 1,
                },
            )
            if resp.status_code == 200:
                logger.info(
                    "FIX_TASK_CREATED | original=%s | round=%d",
                    original_task_id, review_round + 1,
                )
            else:
                logger.warning(
                    "FIX_TASK_CREATE_FAILED | original=%s | status=%d | body=%s",
                    original_task_id, resp.status_code, resp.text[:200],
                )
    except Exception as e:
        logger.warning("Failed to create fix task: %s", e)


def _get_workspace_diff(workspace_path: str) -> str:
    """Get the diff between current branch and its merge target."""
    try:
        # Try to get diff against origin/main or origin/develop
        for target in ["origin/main", "origin/develop", "origin/master"]:
            try:
                result = subprocess.run(
                    ["git", "diff", f"{target}...HEAD"],
                    cwd=workspace_path,
                    capture_output=True,
                    text=True,
                    timeout=30,
                )
                if result.returncode == 0 and result.stdout.strip():
                    return result.stdout[:50000]  # Cap at 50k chars
            except subprocess.SubprocessError:
                continue

        # Fallback: diff of last commit
        result = subprocess.run(
            ["git", "diff", "HEAD~1..HEAD"],
            cwd=workspace_path,
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.returncode == 0:
            return result.stdout[:50000]
    except Exception as e:
        logger.warning("Failed to get diff from %s: %s", workspace_path, e)
    return ""


def _get_changed_files(workspace_path: str) -> list[str]:
    """Get list of changed files in workspace."""
    try:
        for target in ["origin/main", "origin/develop", "origin/master"]:
            try:
                result = subprocess.run(
                    ["git", "diff", "--name-only", f"{target}...HEAD"],
                    cwd=workspace_path,
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                if result.returncode == 0 and result.stdout.strip():
                    return result.stdout.strip().splitlines()
            except subprocess.SubprocessError:
                continue

        result = subprocess.run(
            ["git", "diff", "--name-only", "HEAD~1..HEAD"],
            cwd=workspace_path,
            capture_output=True,
            text=True,
            timeout=10,
        )
        if result.returncode == 0:
            return result.stdout.strip().splitlines()
    except Exception as e:
        logger.warning("Failed to get changed files from %s: %s", workspace_path, e)
    return []
