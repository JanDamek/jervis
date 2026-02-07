"""REST client for Kotlin server internal API.

All endpoints are internal-only (no auth headers, cluster network).
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class KotlinServerClient:
    """HTTP client for Kotlin server /api/internal/* endpoints."""

    def __init__(self, base_url: str | None = None):
        self.base_url = base_url or settings.kotlin_server_url
        self._client: httpx.AsyncClient | None = None

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                base_url=self.base_url,
                timeout=30.0,
            )
        return self._client

    async def close(self):
        if self._client and not self._client.is_closed:
            await self._client.aclose()

    async def get(self, path: str, **params) -> dict:
        client = await self._get_client()
        resp = await client.get(f"/api/internal{path}", params=params)
        resp.raise_for_status()
        return resp.json()

    async def post(self, path: str, data: dict) -> dict:
        client = await self._get_client()
        resp = await client.post(f"/api/internal{path}", json=data)
        resp.raise_for_status()
        return resp.json()

    # --- Convenience methods ---

    async def get_project_info(self, project_id: str) -> dict:
        """Get project metadata."""
        return await self.get(f"/project/{project_id}/info")

    async def get_project_rules(self, client_id: str, project_id: str | None) -> dict:
        """Get project rules (branch naming, approval, etc.)."""
        params = {"clientId": client_id}
        if project_id:
            params["projectId"] = project_id
        return await self.get("/preferences/rules", **params)

    async def emit_chat_message(
        self,
        client_id: str,
        project_id: str,
        message: str,
        metadata: dict | None = None,
    ) -> dict:
        """Emit a message to the UI chat stream."""
        return await self.post(
            f"/chat/{client_id}/{project_id}/emit",
            data={"message": message, "metadata": metadata or {}},
        )

    async def emit_approval_request(
        self,
        client_id: str,
        project_id: str,
        task_id: str,
        action_type: str,
        description: str,
        details: dict,
    ) -> dict:
        """Emit an approval request to the UI."""
        return await self.post(
            f"/chat/{client_id}/{project_id}/approval",
            data={
                "taskId": task_id,
                "actionType": action_type,
                "description": description,
                "details": details,
            },
        )

    async def update_process_status(
        self,
        task_id: str,
        status: str,
        current_action: str | None = None,
        progress: float | None = None,
    ) -> dict:
        """Update running process status for UI."""
        return await self.post(
            "/processes/update",
            data={
                "taskId": task_id,
                "status": status,
                "currentAction": current_action,
                "progress": progress,
            },
        )

    async def search_issues(
        self, client_id: str, query: str, project: str | None = None
    ) -> dict:
        """Search bug tracker issues."""
        params = {"query": query}
        if project:
            params["project"] = project
        return await self.get(f"/bugtracker/{client_id}/search", **params)

    async def get_issue(self, client_id: str, issue_key: str) -> dict:
        """Get a single issue."""
        return await self.get(f"/bugtracker/{client_id}/issue/{issue_key}")


# Singleton
kotlin_client = KotlinServerClient()
