"""EPIC 9-S4: Action Memory — action_log KB category.

Records what JERVIS did for the user into the Knowledge Base.
Enables queries like "what did you do last week?" or
"show me all code reviews you've done for project X".

Action log entries are stored as KB chunks in a dedicated
'action_log' category with structured metadata for filtering.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

import httpx

from app.config import settings
from app.memory.content_reducer import trim_for_display

logger = logging.getLogger(__name__)


async def log_action(
    action: str,
    description: str,
    result: str,
    client_id: str,
    project_id: str | None = None,
    related_task_id: str | None = None,
    artifact_ids: list[str] | None = None,
) -> bool:
    """Store an action log entry in the KB.

    Called after significant actions: task completion, code review,
    deployment, KB maintenance, etc.

    Args:
        action: Action type (e.g., "CODE_REVIEW", "TASK_COMPLETED", "KB_STORE")
        description: Human-readable description of what was done
        result: Outcome (e.g., "APPROVED", "3 issues found", "deployed to staging")
        client_id: Client context
        project_id: Project context (optional)
        related_task_id: Related task ID (optional)
        artifact_ids: Related artifact IDs (optional)

    Returns:
        True if logged successfully
    """
    timestamp = datetime.now(timezone.utc).isoformat()
    source_urn = f"action-log:{action}:{timestamp}"

    content = (
        f"## Action: {action}\n\n"
        f"**When:** {timestamp}\n"
        f"**Description:** {description}\n"
        f"**Result:** {result}\n"
    )
    if related_task_id:
        content += f"**Task:** {related_task_id}\n"
    if artifact_ids:
        content += f"**Artifacts:** {', '.join(artifact_ids)}\n"

    from jervis_contracts import kb_client

    metadata = {
        "action": action,
        "description": description,
        "result": result,
        "timestamp": timestamp,
        "relatedTaskId": related_task_id or "",
        "artifactIds": ",".join(artifact_ids or []),
        "category": "action_log",
    }
    try:
        await kb_client.ingest(
            caller="orchestrator.memory.action_log",
            source_urn=source_urn,
            content=content,
            client_id=client_id,
            project_id=project_id or "",
            kind="action_log",
            metadata=metadata,
            timeout=5.0,
        )
        logger.info(
            "ACTION_LOG | action=%s | client=%s | project=%s",
            action, client_id, project_id,
        )
        return True
    except Exception as e:
        logger.debug("ACTION_LOG failed: %s", e)
        return False


async def query_action_log(
    client_id: str,
    query: str = "",
    action_type: str | None = None,
    project_id: str | None = None,
    max_results: int = 10,
) -> str:
    """Query the action log from KB.

    Searches for past actions matching the query and filters.

    Args:
        client_id: Client context
        query: Search query (e.g., "code reviews", "deployments last week")
        action_type: Filter by action type (optional)
        project_id: Filter by project (optional)
        max_results: Maximum results to return

    Returns:
        Formatted string of matching action log entries
    """
    search_query = f"action_log {query}" if query else "action_log"
    if action_type:
        search_query += f" {action_type}"

    from jervis_contracts import kb_client

    try:
        items = await kb_client.retrieve(
            caller="orchestrator.memory.action_log",
            query=search_query,
            client_id=client_id,
            project_id=project_id or "",
            max_results=max_results,
            min_confidence=0.3,
            kinds=["action_log"],
            timeout=5.0,
        )
    except Exception as e:
        return f"Error querying action log: {trim_for_display(str(e), 200)}"

    if not items:
        return "No matching action log entries found."

    lines = [f"Found {len(items)} action log entries:\n"]
    for i, item in enumerate(items, 1):
        content = item.get("content", "")
        metadata = item.get("metadata", {})
        lines.append(f"### {i}. {metadata.get('action', 'UNKNOWN')}")
        lines.append(f"When: {metadata.get('timestamp', 'unknown')}")
        lines.append(f"Description: {metadata.get('description', trim_for_display(content, 100))}")
        lines.append(f"Result: {metadata.get('result', 'N/A')}")
        if metadata.get("relatedTaskId"):
            lines.append(f"Task: {metadata['relatedTaskId']}")
        lines.append("")
    return "\n".join(lines)
