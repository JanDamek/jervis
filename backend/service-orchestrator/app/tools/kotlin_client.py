"""REST client for Kotlin server internal API.

Minimal client – only used for operations that genuinely require
server-side data not available in OrchestrateRequest.

Most data flows are handled differently:
- Project rules/info → sent in OrchestrateRequest upfront
- Progress updates → SSE streaming directly from Python
- Approval flow → LangGraph interrupt() + SSE + POST /approve
- KB data → direct call to KB service
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class KotlinServerClient:
    """HTTP client for Kotlin server.

    Currently minimal – most communication uses the "full request" model
    where Kotlin sends everything upfront in OrchestrateRequest.
    """

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


# Singleton
kotlin_client = KotlinServerClient()
