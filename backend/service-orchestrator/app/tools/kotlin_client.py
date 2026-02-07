"""REST client for Kotlin server internal API.

Intentionally minimal – the architecture uses a "full request" model
where Kotlin sends everything upfront in OrchestrateRequest.

Communication model:
- Kotlin → Python: POST /orchestrate/stream (fire-and-forget)
- Python → UI: SSE streaming
- Kotlin → Python: GET /status/{thread_id} (polling)
- Kotlin → Python: POST /approve/{thread_id}
- NO Python → Kotlin callbacks

This client exists for:
1. Lifecycle management (httpx.AsyncClient cleanup in lifespan)
2. Optional task status reporting (error conditions polling can't detect)
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class KotlinServerClient:
    """HTTP client for Kotlin server.

    Minimal by design – most communication uses the polling model
    where Kotlin asks Python via GET /status/{thread_id}.
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

    async def report_task_error(self, task_id: str, error: str) -> bool:
        """Report a critical task error to Kotlin server.

        Only used for errors that the polling loop might not detect
        (e.g., graph construction failure before any state is saved).
        Normal errors are detected via GET /status/{thread_id}.

        Returns True if successfully reported, False otherwise.
        """
        try:
            client = await self._get_client()
            resp = await client.post(
                "/api/internal/orchestrator/error",
                json={"taskId": task_id, "error": error},
            )
            return resp.status_code == 200
        except Exception as e:
            logger.warning("Failed to report error to Kotlin: %s", e)
            return False

    async def close(self):
        if self._client and not self._client.is_closed:
            await self._client.aclose()


# Singleton
kotlin_client = KotlinServerClient()
