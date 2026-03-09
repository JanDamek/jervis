"""Background watcher for async coding agent K8s Jobs.

Polls MongoDB for tasks in CODING state, checks their K8s Job
status, and resumes the orchestrator graph when a job completes.

Survives pod restarts — all state is in MongoDB (TaskDocument + LangGraph checkpoints).
"""

from __future__ import annotations

import asyncio
import logging

from app.agents.job_runner import job_runner
from app.config import settings
from app.tools.kotlin_client import kotlin_client

logger = logging.getLogger(__name__)


class AgentTaskWatcher:
    """Monitors dispatched coding agent jobs and resumes orchestration on completion."""

    def __init__(self):
        self._running = False
        self._task: asyncio.Task | None = None
        # Track processed jobs to avoid infinite reprocessing if Kotlin call fails
        self._processed_jobs: set[str] = set()

    async def start(self):
        if self._running:
            return
        self._running = True
        self._task = asyncio.create_task(self._watch_loop())
        logger.info("AgentTaskWatcher started (poll interval=%ds)", settings.agent_watcher_poll_interval)

    async def stop(self):
        if not self._running:
            return
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("AgentTaskWatcher stopped")

    async def _watch_loop(self):
        """Main loop — poll for CODING tasks and check their jobs."""
        while self._running:
            try:
                await self._poll_once()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error("AgentTaskWatcher error: %s", e, exc_info=True)

            await asyncio.sleep(settings.agent_watcher_poll_interval)

    async def _poll_once(self):
        """Single poll iteration — find waiting tasks, check jobs, resume if done."""
        # Query Kotlin server for tasks in CODING state
        import httpx

        try:
            async with httpx.AsyncClient(timeout=15) as client:
                resp = await client.get(
                    f"{settings.kotlin_server_url}/internal/tasks/by-state",
                    params={"state": "CODING"},
                )
                if resp.status_code != 200:
                    return
                tasks = resp.json()
        except Exception as e:
            logger.debug("Failed to fetch CODING tasks: %s", e)
            return

        if not tasks:
            return

        logger.debug("Checking %d CODING tasks", len(tasks))

        for task_data in tasks:
            task_id = task_data.get("id", "")
            job_name = task_data.get("agentJobName")
            thread_id = task_data.get("orchestratorThreadId")

            if not job_name:
                logger.warning("Task %s in CODING but missing job_name", task_id)
                continue

            # thread_id is required for graph-based tasks but optional for direct coding
            source_urn = task_data.get("sourceUrn", "")
            if not thread_id and source_urn != "chat:coding-agent":
                logger.warning("Task %s in CODING but missing thread_id (non-direct)", task_id)
                continue

            # Skip already-processed jobs (prevents infinite loop if Kotlin update fails)
            if job_name in self._processed_jobs:
                logger.debug("Skipping already-processed job: %s", job_name)
                continue

            # Check K8s Job status
            status = job_runner.get_job_status(job_name)
            job_status = status.get("status", "unknown")

            if job_status == "running":
                continue

            # Job completed (succeeded or failed) — collect result and resume
            workspace_path = task_data.get("agentJobWorkspacePath", "")
            agent_type = task_data.get("agentJobAgentType", "unknown")

            # Always try to collect result.json first — entrypoint writes detailed errors
            result = job_runner.collect_result(
                workspace_path=workspace_path,
                job_name=job_name,
                task_id=task_id,
                agent_type=agent_type,
            )

            if job_status == "succeeded":
                logger.info(
                    "Agent job completed: task=%s job=%s success=%s",
                    task_id, job_name, result.get("success", False),
                )
            else:
                # Enrich with job status if result.json had no detailed summary
                if result.get("summary", "").startswith("Job "):
                    result["summary"] = f"Agent job {job_name} failed: {job_status}"
                result["success"] = False
                logger.warning(
                    "Agent job failed: task=%s job=%s status=%s summary=%s",
                    task_id, job_name, job_status, result.get("summary", "")[:200],
                )

            # Mark as processed immediately to prevent reprocessing
            self._processed_jobs.add(job_name)

            # Check if this is a direct coding task (no graph to resume)
            source_urn = task_data.get("sourceUrn", "")
            is_direct_coding = source_urn == "chat:coding-agent"
            is_review_task = source_urn.startswith("code-review:")
            is_fix_task = source_urn.startswith("code-review-fix:")

            if is_review_task:
                # Review agent completed — parse verdict, post MR comment, create fix task
                await self._handle_review_completed(task_id, task_data, result, job_name, job_status, source_urn)
            elif is_direct_coding or is_fix_task:
                # Direct coding task — three-step: CODING→PROCESSING→(MR)→DONE
                # Step 1: agent-completed moves CODING→PROCESSING
                try:
                    async with httpx.AsyncClient(timeout=15) as client:
                        await client.post(
                            f"{settings.kotlin_server_url}/internal/tasks/{task_id}/agent-completed",
                            json={
                                "agentJobState": job_status,
                                "result": result,
                            },
                        )
                except Exception as e:
                    logger.error("Failed to mark agent-completed for direct task %s: %s", task_id, e)

                # Step 2: Create MR/PR BEFORE marking DONE (prevents race condition
                # where task.copy(mergeRequestUrl=...) overwrites DONE state back to PROCESSING)
                mr_url = ""
                review_round = 1
                if result.get("success") and result.get("branch"):
                    branch = result["branch"]
                    task_title = (
                        task_data.get("taskName")
                        or (task_data.get("content", "") or "")[:80]
                        or f"Coding: {task_id[:12]}"
                    )

                    # Determine review round from source_urn (fix tasks carry round info)
                    if source_urn.startswith("code-review-fix:"):
                        # Fix task — don't create new MR, branch + MR already exist
                        import re as _re
                        round_match = _re.search(r"Code Review Fix \(Round (\d+)\)", task_data.get("content", ""))
                        review_round = int(round_match.group(1)) if round_match else 2
                        mr_url = task_data.get("mergeRequestUrl") or ""
                    else:
                        # New coding task — create MR
                        try:
                            mr_result = await kotlin_client.create_merge_request(
                                task_id=task_id,
                                branch=branch,
                                title=task_title,
                                description=result.get("summary", ""),
                            )
                            mr_url = mr_result.get("url", "")
                            if mr_url:
                                logger.info("MR created for task %s: %s", task_id, mr_url)
                            else:
                                logger.warning("MR creation returned no URL for task %s: %s", task_id, mr_result)
                        except Exception as e:
                            logger.warning("Failed to create MR for task %s: %s", task_id, e)

                # Step 3: report_status_change moves PROCESSING→DONE (MUST be last DB write)
                try:
                    await kotlin_client.report_status_change(
                        task_id=task_id,
                        thread_id=thread_id or "",
                        status="done" if result.get("success") else "error",
                        summary=result.get("summary", "Coding agent completed"),
                        error=None if result.get("success") else result.get("summary"),
                    )
                    logger.info("Direct coding task completed: task=%s job=%s success=%s", task_id, job_name, result.get("success"))
                except Exception as e:
                    logger.error("Failed to report coding task done %s: %s", task_id, e)

                # Code review is NOT auto-triggered here. MR/PR creation is enough.
                # Review will be triggered when:
                # 1. MR gets indexed through GitLab webhooks/polling
                # 2. Review is assigned to the user
                # 3. BackgroundEngine creates a review task from the indexed MR
                if mr_url:
                    logger.info("MR ready for review: task=%s url=%s", task_id, mr_url)

                # Update memory map TASK_REF vertex → completed
                try:
                    from app.agent.persistence import agent_store
                    job_success = result.get("success", False)
                    task_title = (
                        task_data.get("taskName")
                        or (task_data.get("content", "") or "")[:80]
                        or f"Coding: {task_id[:12]}"
                    )
                    await agent_store.link_thinking_map(
                        task_id=task_id,
                        sub_graph_id="",
                        title=task_title,
                        completed=job_success,
                        failed=not job_success,
                        result_summary=result.get("summary", "")[:500],
                        client_id=str(task_data.get("clientId", "")),
                        project_id=str(task_data.get("projectId", "")) if task_data.get("projectId") else None,
                    )
                except Exception:
                    pass  # Non-fatal
            else:
                # Graph-based task — update state and resume orchestration graph
                try:
                    async with httpx.AsyncClient(timeout=15) as client:
                        await client.post(
                            f"{settings.kotlin_server_url}/internal/tasks/{task_id}/agent-completed",
                            json={
                                "agentJobState": job_status,
                                "result": result,
                            },
                        )
                except Exception as e:
                    logger.error("Failed to update task %s after agent completion: %s", task_id, e)
                    continue

                try:
                    from app.graph.orchestrator import resume_orchestration_streaming

                    logger.info("Resuming orchestration: thread=%s task=%s", thread_id, task_id)
                    async for _event in resume_orchestration_streaming(
                        thread_id, resume_value=result,
                    ):
                        pass  # Consume events — the graph handles the rest
                    logger.info("Orchestration resumed successfully: task=%s", task_id)
                except Exception as e:
                    logger.error(
                        "Failed to resume orchestration for task %s thread=%s: %s",
                        task_id, thread_id, e, exc_info=True,
                    )

            # Cap processed set size (prevent unbounded memory growth)
            if len(self._processed_jobs) > 1000:
                self._processed_jobs.clear()

    async def _handle_review_completed(
        self,
        task_id: str,
        task_data: dict,
        result: dict,
        job_name: str,
        job_status: str,
        source_urn: str,
    ):
        """Handle completed code review agent job — parse verdict, post MR comment, fix task."""
        import httpx
        import json
        import re as _re
        from app.review.review_engine import ReviewVerdict

        # Mark task completed (CODING→PROCESSING→DONE)
        try:
            async with httpx.AsyncClient(timeout=15) as client:
                await client.post(
                    f"{settings.kotlin_server_url}/internal/tasks/{task_id}/agent-completed",
                    json={"agentJobState": job_status, "result": result},
                )
        except Exception as e:
            logger.error("Failed to mark review agent-completed %s: %s", task_id, e)

        try:
            await kotlin_client.report_status_change(
                task_id=task_id,
                thread_id="",
                status="done" if result.get("success") else "error",
                summary=result.get("summary", "Review completed")[:500],
                error=None if result.get("success") else result.get("summary"),
            )
        except Exception as e:
            logger.error("Failed to report review done %s: %s", task_id, e)

        if not result.get("success"):
            logger.warning("Review agent failed for task %s: %s", task_id, result.get("summary", "")[:200])
            return

        # Parse review verdict from agent output (JSON in summary)
        summary = result.get("summary", "")
        review_data = _extract_review_json(summary)

        if not review_data:
            logger.warning("Could not parse review JSON from task %s summary", task_id)
            # Post raw summary as MR comment (best effort)
            mr_url = task_data.get("mergeRequestUrl", "")
            if mr_url and summary:
                try:
                    original_task_id = source_urn.replace("code-review:", "")
                    await kotlin_client.post_mr_inline_comments(
                        task_id=original_task_id,
                        summary=f"### Jervis Code Review\n\n{summary[:3000]}",
                        verdict="COMMENT",
                        merge_request_url=mr_url,
                    )
                except Exception:
                    pass
            return

        verdict = review_data.get("verdict", "APPROVE")
        score = review_data.get("score", 70)
        review_summary = review_data.get("summary", "")
        issues = review_data.get("issues", [])
        checklist = review_data.get("checklist", {})

        # Determine review round from task content
        review_round = 1
        round_match = _re.search(r"Round (\d+)/", task_data.get("content", ""))
        if round_match:
            review_round = int(round_match.group(1))

        # Build summary for MR overview comment
        summary_lines = [
            f"### Jervis Code Review (Round {review_round}/{2})",
            f"**Verdict:** {verdict} | **Score:** {score}/100",
            "",
            review_summary,
        ]

        if checklist:
            summary_lines.append("")
            summary_lines.append("### Checklist")
            for item, passed in checklist.items():
                icon = "+" if passed else "-"  # Avoid emoji
                summary_lines.append(f"- [{icon}] {item}")

        # Separate issues into inline (file:line) and general
        inline_comments = []
        general_issues = []
        for issue in issues:
            sev = issue.get("severity", "INFO")
            file_path = issue.get("file")
            line_num = issue.get("line")
            msg = issue.get("message", "")
            suggestion = issue.get("suggestion", "")
            body = f"**[{sev}]** {msg}"
            if suggestion:
                body += f"\n\n> Suggestion: {suggestion}"

            if file_path and line_num:
                inline_comments.append({"file": file_path, "line": line_num, "body": body})
            else:
                general_issues.append(issue)

        # Append general issues (no file:line) to summary
        if general_issues:
            summary_lines.append("")
            summary_lines.append("### General Issues")
            for issue in general_issues:
                sev = issue.get("severity", "INFO")
                file_ref = issue.get("file", "")
                if file_ref:
                    summary_lines.append(f"- **[{sev}]** `{file_ref}`: {issue.get('message', '')}")
                else:
                    summary_lines.append(f"- **[{sev}]** {issue.get('message', '')}")
                if issue.get("suggestion"):
                    summary_lines.append(f"  > Fix: {issue['suggestion']}")

        summary_body = "\n".join(summary_lines)

        # Map review verdict to GitHub event / GitLab action
        verdict_map = {"APPROVE": "APPROVE", "REQUEST_CHANGES": "REQUEST_CHANGES", "REJECT": "REQUEST_CHANGES"}
        review_event = verdict_map.get(verdict, "COMMENT")

        # Post inline comments + summary on MR
        original_task_id = source_urn.replace("code-review:", "")
        mr_url = task_data.get("mergeRequestUrl", "")
        posted = False
        if mr_url:
            try:
                posted = await kotlin_client.post_mr_inline_comments(
                    task_id=original_task_id,
                    summary=summary_body,
                    verdict=review_event,
                    comments=inline_comments if inline_comments else None,
                    merge_request_url=mr_url,
                )
                if posted:
                    logger.info(
                        "REVIEW_POSTED | task=%s | verdict=%s | score=%d | inline=%d",
                        task_id, verdict, score, len(inline_comments),
                    )
            except Exception as e:
                logger.warning("Failed to post review comment: %s", e)

        # If BLOCKERs and within round limit → create fix task
        blocker_issues = [i for i in issues if i.get("severity") == "BLOCKER"]
        has_blockers = len(blocker_issues) > 0

        if has_blockers and verdict in ("REQUEST_CHANGES", "REJECT") and review_round < 2:
            try:
                await self._create_review_fix_task(
                    original_task_id=original_task_id,
                    task_content=task_data.get("content", ""),
                    blocker_issues=blocker_issues,
                    mr_url=mr_url,
                    client_id=str(task_data.get("clientId", "")),
                    project_id=str(task_data.get("projectId", "")) if task_data.get("projectId") else None,
                    review_round=review_round,
                )
            except Exception as e:
                logger.warning("Failed to create fix task from review %s: %s", task_id, e)
        elif has_blockers and review_round >= 2:
            # Max rounds reached — post escalation
            try:
                await kotlin_client.post_mr_inline_comments(
                    task_id=original_task_id,
                    summary=(
                        "---\n**Max review rounds (2) reached.** "
                        "Remaining issues require manual review."
                    ),
                    verdict="COMMENT",
                    merge_request_url=mr_url,
                )
            except Exception:
                pass

        # Store review findings in KB for future reference
        if review_summary:
            try:
                from app.tools.executor import execute_tool
                client_id = str(task_data.get("clientId", ""))
                project_id = str(task_data.get("projectId", "")) if task_data.get("projectId") else None
                kb_content = (
                    f"Code review ({verdict}, score {score}/100): {review_summary}\n"
                    f"Task: {task_data.get('content', '')[:200]}\n"
                    f"MR: {mr_url}"
                )
                await execute_tool(
                    tool_name="kb_store",
                    arguments={
                        "content": kb_content,
                        "kind": "finding",
                        "source_urn": f"code-review:{original_task_id}",
                    },
                    client_id=client_id,
                    project_id=project_id,
                )
            except Exception:
                pass  # Non-fatal

        # Record code review result in memory map
        try:
            from app.agent.persistence import agent_store
            review_title = f"Code Review: {verdict} (score {score}/100, round {review_round})"
            await agent_store.link_thinking_map(
                task_id=task_id,
                sub_graph_id="",
                title=review_title,
                completed=(verdict == "APPROVE"),
                failed=(verdict in ("REJECT",)),
                result_summary=f"{review_summary[:300]}\nMR: {mr_url}",
                client_id=str(task_data.get("clientId", "")),
                project_id=str(task_data.get("projectId", "")) if task_data.get("projectId") else None,
            )
        except Exception:
            pass  # Non-fatal

        logger.info(
            "REVIEW_COMPLETED | task=%s | verdict=%s | blockers=%d | round=%d | posted=%s",
            task_id, verdict, len(blocker_issues), review_round, posted,
        )

    async def _create_review_fix_task(
        self,
        original_task_id: str,
        task_content: str,
        blocker_issues: list[dict],
        mr_url: str,
        client_id: str,
        project_id: str | None,
        review_round: int,
    ):
        """Create a new coding task to fix BLOCKERs found in code review."""
        import httpx

        issues_text = ""
        for issue in blocker_issues:
            loc = issue.get("file", "?")
            line = issue.get("line")
            if line:
                loc += f":{line}"
            issues_text += f"- [{loc}] {issue.get('message', '')}"
            if issue.get("suggestion"):
                issues_text += f"\n  Fix: {issue['suggestion']}"
            issues_text += "\n"

        fix_instructions = (
            f"## Code Review Fix (Round {review_round + 1})\n\n"
            f"The previous code review found BLOCKER issues that must be fixed.\n"
            f"Fix ONLY the issues listed below. Do NOT make other changes.\n\n"
            f"### Original Task\n{task_content[:1000]}\n\n"
            f"### Issues to Fix\n{issues_text}\n\n"
            f"### MR/PR\n{mr_url}\n"
        )

        try:
            async with httpx.AsyncClient(timeout=15) as client:
                resp = await client.post(
                    f"{settings.kotlin_server_url}/internal/dispatch-coding-agent",
                    json={
                        "taskDescription": fix_instructions,
                        "clientId": client_id,
                        "projectId": project_id or "",
                        "sourceUrn": f"code-review-fix:{original_task_id}",
                        "mergeRequestUrl": mr_url,
                    },
                )
                if resp.status_code == 200:
                    logger.info(
                        "FIX_TASK_CREATED | original=%s | round=%d",
                        original_task_id, review_round + 1,
                    )
                else:
                    logger.warning(
                        "FIX_TASK_CREATE_FAILED | original=%s | status=%d",
                        original_task_id, resp.status_code,
                    )
        except Exception as e:
            logger.warning("Failed to create fix task: %s", e)


def _extract_review_json(text: str) -> dict | None:
    """Extract JSON review verdict from agent output text."""
    import json
    import re

    # Try direct parse
    try:
        return json.loads(text)
    except (json.JSONDecodeError, TypeError):
        pass

    # Try extracting from ```json ... ``` block
    match = re.search(r"```(?:json)?\s*\n?(.*?)\n?\s*```", text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except (json.JSONDecodeError, TypeError):
            pass

    # Try finding last { ... } block (review JSON is usually at the end)
    matches = list(re.finditer(r"\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}", text, re.DOTALL))
    for match in reversed(matches):
        try:
            data = json.loads(match.group(0))
            if "verdict" in data:
                return data
        except (json.JSONDecodeError, TypeError):
            continue

    return None


# Singleton
agent_task_watcher = AgentTaskWatcher()
