"""Code review handler — prepares context and dispatches review agent K8s Job.

Flow:
1. Fetch diff from MR/PR API (fallback: workspace git diff)
2. KB prefetch — search for related context (Jira, meetings, architecture decisions)
3. Run static analysis (quick local checks)
4. Write diff + KB context to workspace files
5. Dispatch review coding agent K8s Job via internal API
6. AgentTaskWatcher picks up completed review → posts MR comment → fix task if needed

Called from AgentTaskWatcher after successful MR creation.
"""

from __future__ import annotations

import logging
import subprocess

from app.review.review_engine import run_static_analysis
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
    """Prepare review context and dispatch a review coding agent K8s Job.

    The actual review is performed by a Claude SDK agent in a K8s Job, not
    by the orchestrator's local LLM. This method only prepares context and
    dispatches the job.

    Args:
        task_id: The coding task ID (original, not review task).
        workspace_path: Path to the git workspace on shared PVC.
        mr_url: MR/PR URL for posting comments.
        task_content: Original task description (for scope checking).
        client_id: Client ID for KB and guidelines resolution.
        project_id: Project ID for KB and guidelines resolution.
        source_urn: Source URN of the coding task.
        review_round: Current review-fix cycle round (1-based).

    Returns:
        dict with dispatched=True on success, or error info.
    """
    logger.info(
        "CODE_REVIEW_PREPARE | task=%s | round=%d/%d | mr=%s",
        task_id, review_round, MAX_REVIEW_ROUNDS, mr_url,
    )

    # 1. Get diff — prefer MR/PR API (no workspace needed), fallback to git
    diff = ""
    diff_files: list[dict] = []
    try:
        diff_files = await kotlin_client.get_merge_request_diff(task_id) or []
        if diff_files:
            diff = _format_diff_from_api(diff_files)
    except Exception as e:
        logger.debug("MR diff API failed (will use workspace): %s", e)

    if not diff and workspace_path:
        diff = _get_workspace_diff(workspace_path)

    if not diff:
        logger.warning("No diff found — skipping review for task %s", task_id)
        return {"dispatched": False, "reason": "no_diff"}

    changed_files = [d.get("newPath", d.get("oldPath", "")) for d in diff_files] if diff_files else _get_changed_files(workspace_path)

    # 2. Get guidelines
    guidelines = {}
    try:
        guidelines = await kotlin_client.get_merged_guidelines(
            client_id=client_id,
            project_id=project_id,
        )
    except Exception as e:
        logger.debug("Guidelines fetch failed (non-fatal): %s", e)

    # 3. Run static analysis (quick local checks)
    static_passed, static_issues = run_static_analysis(diff, changed_files, guidelines)

    # 4. KB prefetch — search for related context
    kb_context = ""
    if project_id:
        try:
            from app.tools.executor import execute_tool

            # Search with task content to find related discussions, decisions, conventions
            kb_result = await execute_tool(
                tool_name="kb_search",
                arguments={"query": task_content[:300], "max_results": 8},
                client_id=client_id,
                project_id=project_id,
            )
            if kb_result and not str(kb_result).startswith("Error"):
                kb_context = str(kb_result)[:6000]
        except Exception as e:
            logger.debug("KB prefetch failed (non-fatal): %s", e)

    # 5. Write diff + KB context to workspace files for the review agent
    import os
    jervis_dir = os.path.join(workspace_path, ".jervis")
    os.makedirs(jervis_dir, exist_ok=True)

    diff_path = os.path.join(jervis_dir, "diff.txt")
    with open(diff_path, "w") as f:
        f.write(diff[:80000])  # Cap at 80k chars

    if kb_context:
        kb_path = os.path.join(jervis_dir, "review-kb-context.md")
        with open(kb_path, "w") as f:
            f.write(kb_context)

    # 6. Build review instructions (task description for review agent)
    guidelines_summary = _format_guidelines_summary(guidelines)
    static_text = ""
    if static_issues:
        static_text = "\n### Static Analysis Findings\n" + "\n".join(f"- {i}" for i in static_issues)
        if not static_passed:
            static_text += "\n\n**Static analysis found BLOCKERs — these MUST be flagged in your review.**"

    review_instructions = (
        f"## Code Review Task (Round {review_round}/{MAX_REVIEW_ROUNDS})\n\n"
        f"Review the code changes made by a coding agent for the following task.\n\n"
        f"### Original Task Description\n{task_content[:2000]}\n\n"
        f"### MR/PR URL\n{mr_url}\n\n"
        f"### Changed Files\n" + "\n".join(f"- `{f}`" for f in changed_files[:50]) + "\n\n"
        f"### Diff\nThe full diff is in `.jervis/diff.txt`. Read it carefully.\n"
        f"First 2000 chars for quick reference:\n```\n{diff[:2000]}\n```\n\n"
        f"{guidelines_summary}"
        f"{static_text}\n\n"
        f"### KB Context\n"
        f"Pre-fetched context is in `.jervis/review-kb-context.md` (if it exists).\n"
        f"Use `kb_search` MCP tool for additional context — search for architecture decisions,\n"
        f"conventions, and related discussions about this topic.\n\n"
        f"### Your Task\n"
        f"1. Read the full diff from `.jervis/diff.txt`\n"
        f"2. Read pre-fetched KB context from `.jervis/review-kb-context.md`\n"
        f"3. Search KB for additional context (architecture, conventions, related issues)\n"
        f"4. If KB info is stale (observedAt > 7 days), verify with `web_search`\n"
        f"5. Review the code changes against the criteria in CLAUDE.md\n"
        f"6. Output your review verdict as a JSON object (see CLAUDE.md for format)\n"
    )

    # 7. Dispatch review agent K8s Job
    try:
        import httpx
        from app.config import settings

        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.post(
                f"{settings.kotlin_server_url}/internal/dispatch-coding-agent",
                json={
                    "taskDescription": review_instructions,
                    "clientId": client_id,
                    "projectId": project_id or "",
                    "sourceUrn": f"code-review:{task_id}",
                    "mergeRequestUrl": mr_url,
                },
            )
            if resp.status_code == 200:
                result = resp.json()
                logger.info(
                    "CODE_REVIEW_DISPATCHED | original_task=%s | review_task=%s | round=%d",
                    task_id, result.get("taskId", ""), review_round,
                )
                return {"dispatched": True, "review_task_id": result.get("taskId")}
            else:
                logger.warning(
                    "CODE_REVIEW_DISPATCH_FAILED | task=%s | status=%d | body=%s",
                    task_id, resp.status_code, resp.text[:200],
                )
                return {"dispatched": False, "reason": f"HTTP {resp.status_code}"}
    except Exception as e:
        logger.warning("Failed to dispatch review agent for task %s: %s", task_id, e)
        return {"dispatched": False, "reason": str(e)}


def _format_diff_from_api(diff_files: list[dict]) -> str:
    """Format API diff entries into unified diff text."""
    parts = []
    for entry in diff_files:
        old_path = entry.get("oldPath", entry.get("old_path", ""))
        new_path = entry.get("newPath", entry.get("new_path", ""))
        diff_text = entry.get("diff", "")

        header = f"--- a/{old_path}\n+++ b/{new_path}"
        if entry.get("newFile", entry.get("new_file", False)):
            header = f"--- /dev/null\n+++ b/{new_path}"
        elif entry.get("deletedFile", entry.get("deleted_file", False)):
            header = f"--- a/{old_path}\n+++ /dev/null"

        parts.append(f"{header}\n{diff_text}")

    return "\n".join(parts)


def _format_guidelines_summary(guidelines: dict) -> str:
    """Format guidelines as compact summary for review instructions."""
    if not guidelines:
        return ""

    parts = ["### Project Guidelines\n"]
    coding = guidelines.get("coding", {})
    review = guidelines.get("review", {})

    if coding.get("forbiddenPatterns"):
        parts.append("**Forbidden patterns:** " + ", ".join(
            p.get("description", p.get("pattern", "")) for p in coding["forbiddenPatterns"]
        ))
    if review.get("focusAreas"):
        parts.append("**Review focus areas:** " + ", ".join(review["focusAreas"]))
    if review.get("checklistItems"):
        enabled = [i for i in review["checklistItems"] if i.get("enabled", True)]
        if enabled:
            parts.append("**Checklist:**")
            for item in enabled:
                parts.append(f"- {item.get('label', '?')} (severity: {item.get('severity', 'WARNING')})")

    return "\n".join(parts) + "\n\n" if len(parts) > 1 else ""


def _get_workspace_diff(workspace_path: str) -> str:
    """Get the diff between current branch and its merge target."""
    try:
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
                    return result.stdout[:80000]
            except subprocess.SubprocessError:
                continue

        result = subprocess.run(
            ["git", "diff", "HEAD~1..HEAD"],
            cwd=workspace_path,
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.returncode == 0:
            return result.stdout[:80000]
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
