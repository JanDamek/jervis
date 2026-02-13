"""Procedural Memory — learned workflow procedures stored in KB.

Orchestrator looks up procedures by trigger_pattern before planning.
If a procedure exists for a task type, it's used as a template for delegation.
If not, orchestrator asks the user and stores the response as a new procedure.

Procedures are stored in ArangoDB via KB service REST API.
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from app.config import settings
from app.models import ProcedureNode, ProcedureStep

logger = logging.getLogger(__name__)


class ProceduralMemoryStore:
    """KB-backed procedural memory for learned workflows."""

    def __init__(self) -> None:
        self._client: httpx.AsyncClient | None = None

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                base_url=settings.knowledgebase_url,
                timeout=10.0,
            )
        return self._client

    async def find_procedure(
        self,
        trigger_pattern: str,
        client_id: str,
    ) -> ProcedureNode | None:
        """Find a procedure by trigger pattern for a specific client.

        Searches KB for ProcedureNode with matching trigger_pattern.
        Returns the best matching procedure (highest success_rate if multiple).
        """
        if not settings.use_procedural_memory:
            return None

        try:
            client = await self._get_client()
            response = await client.post(
                "/api/graph/query",
                json={
                    "collection": "procedures",
                    "filter": {
                        "trigger_pattern": trigger_pattern,
                        "client_id": client_id,
                    },
                    "sort": {"success_rate": -1},
                    "limit": 1,
                },
            )

            if response.status_code != 200:
                logger.debug(
                    "Procedure lookup failed (status=%d): %s",
                    response.status_code, trigger_pattern,
                )
                return None

            data = response.json()
            results = data.get("results", [])
            if not results:
                return None

            # Parse procedure from KB result
            proc_data = results[0]
            return ProcedureNode(
                trigger_pattern=proc_data.get("trigger_pattern", trigger_pattern),
                procedure_steps=[
                    ProcedureStep(**s) for s in proc_data.get("procedure_steps", [])
                ],
                success_rate=proc_data.get("success_rate", 0.0),
                last_used=proc_data.get("last_used"),
                usage_count=proc_data.get("usage_count", 0),
                source=proc_data.get("source", "learned"),
                client_id=proc_data.get("client_id", client_id),
            )

        except Exception as e:
            logger.debug("Procedure lookup error for '%s': %s", trigger_pattern, e)
            return None

    async def save_procedure(
        self,
        procedure: ProcedureNode,
    ) -> bool:
        """Save a learned procedure to KB.

        Called after successful orchestration to store the workflow pattern.
        """
        if not settings.use_procedural_memory:
            return False

        try:
            client = await self._get_client()
            response = await client.post(
                "/api/graph/upsert",
                json={
                    "collection": "procedures",
                    "key": f"{procedure.client_id}:{procedure.trigger_pattern}",
                    "data": procedure.model_dump(),
                },
            )
            success = response.status_code in (200, 201)
            if success:
                logger.info(
                    "Procedure saved: trigger=%s client=%s steps=%d",
                    procedure.trigger_pattern,
                    procedure.client_id,
                    len(procedure.procedure_steps),
                )
            return success
        except Exception as e:
            logger.debug("Failed to save procedure: %s", e)
            return False

    async def update_usage(
        self,
        trigger_pattern: str,
        client_id: str,
        success: bool,
    ) -> None:
        """Update usage stats after a procedure is used.

        Increments usage_count and adjusts success_rate (exponential moving average).
        """
        if not settings.use_procedural_memory:
            return

        try:
            existing = await self.find_procedure(trigger_pattern, client_id)
            if existing is None:
                return

            # Exponential moving average for success_rate
            alpha = 0.3  # Weight of new observation
            new_rate = alpha * (1.0 if success else 0.0) + (1 - alpha) * existing.success_rate

            from datetime import datetime, timezone
            existing.success_rate = round(new_rate, 3)
            existing.usage_count += 1
            existing.last_used = datetime.now(timezone.utc).isoformat()

            await self.save_procedure(existing)

        except Exception as e:
            logger.debug("Failed to update procedure usage: %s", e)

    async def get_procedure_context(
        self,
        trigger_pattern: str,
        client_id: str,
    ) -> str:
        """Get procedure as formatted text for LLM context in plan_delegations."""
        procedure = await self.find_procedure(trigger_pattern, client_id)
        if procedure is None:
            return ""

        steps_text = "\n".join(
            f"  {i+1}. {s.agent}: {s.action}"
            + (f" (params: {s.parameters})" if s.parameters else "")
            for i, s in enumerate(procedure.procedure_steps)
        )

        return (
            f"Known procedure for '{trigger_pattern}' "
            f"(success rate: {procedure.success_rate:.0%}, "
            f"used {procedure.usage_count} times):\n"
            f"{steps_text}"
        )

    async def close(self) -> None:
        """Close HTTP client."""
        if self._client and not self._client.is_closed:
            await self._client.aclose()


# Singleton
procedural_memory = ProceduralMemoryStore()
