"""Procedural Memory — learned workflow procedures stored in KB.

When the orchestrator encounters a task, it first checks Procedural Memory
for a previously successful workflow pattern (e.g. "task_completion" →
CodeReview → Deploy → Test → Close). If none exists, the orchestrator
asks the user and stores the answer as a new procedure.

Communicates with the KB service REST API (ArangoDB ProcedureNode).
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

import httpx

from app.config import settings
from app.models import ProcedureNode

logger = logging.getLogger(__name__)

_PROCEDURE_ENDPOINT = "/api/v1/procedures"
_TIMEOUT = 10.0


class ProceduralMemory:
    """Client for KB service procedural memory operations."""

    def __init__(self) -> None:
        self._client: httpx.AsyncClient | None = None

    async def init(self) -> None:
        """Initialise the HTTP client."""
        self._client = httpx.AsyncClient(
            base_url=settings.knowledgebase_url,
            timeout=_TIMEOUT,
        )
        logger.info("Procedural memory initialised (kb_url=%s)", settings.knowledgebase_url)

    async def close(self) -> None:
        """Close the HTTP client."""
        if self._client:
            await self._client.aclose()
            self._client = None

    @property
    def client(self) -> httpx.AsyncClient:
        if self._client is None:
            raise RuntimeError("Procedural memory not initialised. Call init() first.")
        return self._client

    # ------------------------------------------------------------------
    # Lookup
    # ------------------------------------------------------------------

    async def find_procedure(
        self,
        trigger_pattern: str,
        client_id: str,
    ) -> ProcedureNode | None:
        """Find a stored procedure by trigger pattern and client scope.

        Returns the best-matching procedure, or None if not found.
        The KB service handles fuzzy matching on trigger_pattern.
        """
        if not settings.use_procedural_memory:
            return None

        try:
            resp = await self.client.get(
                _PROCEDURE_ENDPOINT,
                params={
                    "trigger": trigger_pattern,
                    "client_id": client_id,
                },
            )
            if resp.status_code == 404:
                return None
            resp.raise_for_status()
            data = resp.json()
            if not data:
                return None
            # KB may return a list — take the highest success_rate
            if isinstance(data, list):
                if not data:
                    return None
                data = max(data, key=lambda d: d.get("success_rate", 0))
            return ProcedureNode(**data)
        except httpx.HTTPError as exc:
            logger.warning("Procedural memory lookup failed: %s", exc)
            return None
        except Exception as exc:
            logger.warning("Procedural memory parse error: %s", exc)
            return None

    # ------------------------------------------------------------------
    # Store
    # ------------------------------------------------------------------

    async def save_procedure(self, procedure: ProcedureNode) -> bool:
        """Save a new or updated procedure to the KB.

        Returns True on success.
        """
        if not settings.use_procedural_memory:
            return False

        try:
            payload = procedure.model_dump()
            payload["last_used"] = datetime.now(timezone.utc).isoformat()
            resp = await self.client.post(
                _PROCEDURE_ENDPOINT,
                json=payload,
            )
            resp.raise_for_status()
            logger.info(
                "Procedure saved: trigger=%s client=%s source=%s",
                procedure.trigger_pattern,
                procedure.client_id,
                procedure.source,
            )
            return True
        except Exception as exc:
            logger.warning("Failed to save procedure: %s", exc)
            return False

    # ------------------------------------------------------------------
    # Feedback
    # ------------------------------------------------------------------

    async def update_success_rate(
        self,
        trigger_pattern: str,
        client_id: str,
        success: bool,
    ) -> None:
        """Update the success rate of a procedure after execution.

        The KB service recalculates the rate based on historical data.
        """
        if not settings.use_procedural_memory:
            return

        try:
            await self.client.patch(
                f"{_PROCEDURE_ENDPOINT}/feedback",
                json={
                    "trigger": trigger_pattern,
                    "client_id": client_id,
                    "success": success,
                    "timestamp": datetime.now(timezone.utc).isoformat(),
                },
            )
        except Exception as exc:
            logger.warning("Procedural memory feedback failed: %s", exc)


# Singleton
procedural_memory = ProceduralMemory()
