"""HTTP client for Jervis Brain internal API.

Brain is Jervis's own Jira + Confluence project used by the orchestrator
to organize work, plan epics, track progress, and store documentation.

Endpoints are served by KtorRpcServer under /internal/brain/*.
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

_TIMEOUT = 15.0  # seconds


class BrainClient:
    """HTTP client for Brain internal API on the Kotlin server."""

    def __init__(self, base_url: str | None = None):
        self.base_url = base_url or settings.kotlin_server_url

    async def _post(self, path: str, json: dict) -> dict:
        async with httpx.AsyncClient(base_url=self.base_url, timeout=_TIMEOUT) as client:
            resp = await client.post(path, json=json)
            resp.raise_for_status()
            return resp.json()

    async def _get(self, path: str, params: dict | None = None) -> Any:
        async with httpx.AsyncClient(base_url=self.base_url, timeout=_TIMEOUT) as client:
            resp = await client.get(path, params=params)
            resp.raise_for_status()
            return resp.json()

    # ---- Config ----

    async def get_config(self) -> dict:
        """Get brain configuration (configured, projectKey, spaceKey)."""
        return await self._get("/internal/brain/config")

    # ---- Jira ----

    async def create_issue(
        self,
        summary: str,
        description: str | None = None,
        issue_type: str = "Task",
        priority: str | None = None,
        labels: list[str] | None = None,
        epic_key: str | None = None,
    ) -> dict:
        """Create a Jira issue in the brain project."""
        return await self._post(
            "/internal/brain/jira/issue",
            {
                "summary": summary,
                "description": description,
                "issueType": issue_type,
                "priority": priority,
                "labels": labels or [],
                "epicKey": epic_key,
            },
        )

    async def update_issue(
        self,
        issue_key: str,
        summary: str | None = None,
        description: str | None = None,
        assignee: str | None = None,
        priority: str | None = None,
        labels: list[str] | None = None,
    ) -> dict:
        """Update an existing Jira issue in the brain project."""
        return await self._post(
            f"/internal/brain/jira/issue/{issue_key}/update",
            {
                "summary": summary,
                "description": description,
                "assignee": assignee,
                "priority": priority,
                "labels": labels,
            },
        )

    async def add_comment(self, issue_key: str, body: str) -> dict:
        """Add a comment to a Jira issue."""
        return await self._post(
            f"/internal/brain/jira/issue/{issue_key}/comment",
            {"body": body},
        )

    async def transition_issue(self, issue_key: str, transition_name: str) -> dict:
        """Transition issue to a new status (e.g., 'In Progress', 'Done')."""
        return await self._post(
            f"/internal/brain/jira/issue/{issue_key}/transition",
            {"transitionName": transition_name},
        )

    async def search_issues(self, jql: str, max_results: int = 20) -> list[dict]:
        """Search issues using JQL (automatically scoped to brain project)."""
        return await self._get(
            "/internal/brain/jira/search",
            {"jql": jql, "maxResults": max_results},
        )

    # ---- Confluence ----

    async def create_page(
        self,
        title: str,
        content: str,
        parent_page_id: str | None = None,
    ) -> dict:
        """Create a Confluence page in the brain wiki space."""
        return await self._post(
            "/internal/brain/confluence/page",
            {
                "title": title,
                "content": content,
                "parentPageId": parent_page_id,
            },
        )

    async def update_page(
        self,
        page_id: str,
        title: str,
        content: str,
        version: int,
    ) -> dict:
        """Update an existing Confluence page."""
        return await self._post(
            f"/internal/brain/confluence/page/{page_id}/update",
            {
                "title": title,
                "content": content,
                "version": version,
            },
        )

    async def search_pages(self, query: str, max_results: int = 20) -> list[dict]:
        """Search Confluence pages in the brain wiki space."""
        return await self._get(
            "/internal/brain/confluence/search",
            {"query": query, "maxResults": max_results},
        )


# Singleton
brain_client = BrainClient()
