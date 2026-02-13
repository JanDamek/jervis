"""Retention Policy — decides what to store in KB vs discard.

After each orchestration, the retention policy evaluates results
and determines what should be:
- Stored in KB (permanent semantic memory)
- Stored in Session Memory (7-day bridge cache)
- Stored in Context Store (30-day operational data)
- Discarded (routine, low-value results)
"""

from __future__ import annotations

import logging
from typing import Any

from app.models import AgentOutput, SessionEntry

logger = logging.getLogger(__name__)


class RetentionPolicy:
    """Evaluates delegation results for storage decisions."""

    # Results from these agents are always stored in KB
    KB_ALWAYS_STORE_AGENTS = {
        "legal", "financial", "security",
        "project_management", "documentation",
    }

    # Results from these agents go to session memory only
    SESSION_ONLY_AGENTS = {
        "research", "calendar", "administrative",
        "personal", "learning",
    }

    # Minimum confidence threshold for KB storage
    KB_MIN_CONFIDENCE = 0.7

    def should_store_in_kb(self, output: AgentOutput) -> bool:
        """Determine if an agent's result should be stored in KB.

        Criteria for KB storage:
        - User decisions (always)
        - Legal, financial, security results (always)
        - Successful results with high confidence
        - Results containing key decisions or new information
        """
        if not output.success:
            return False

        # Always store high-importance domain results
        if output.agent_name in self.KB_ALWAYS_STORE_AGENTS:
            return True

        # Store if high confidence and substantial result
        if output.confidence >= self.KB_MIN_CONFIDENCE and len(output.result) > 100:
            return True

        # Store if there are changed files (code changes are permanent)
        if output.changed_files:
            return True

        return False

    def should_store_in_session(self, output: AgentOutput) -> bool:
        """Determine if result should go to session memory.

        Almost everything goes to session memory — it's a short-term bridge.
        Only skip truly empty or failed results.
        """
        if not output.result:
            return False

        return True

    def should_store_in_context(self, output: AgentOutput) -> bool:
        """Determine if result should go to context store (operational).

        All successful results are stored for potential retrieval.
        """
        return output.success

    def build_session_entry(
        self,
        output: AgentOutput,
        task_id: str | None = None,
    ) -> SessionEntry:
        """Build a SessionEntry from an AgentOutput."""
        from app.context.summarizer import summarize_for_session_memory

        from datetime import datetime, timezone

        return SessionEntry(
            timestamp=datetime.now(timezone.utc).isoformat(),
            source="orchestrator_decision",
            summary=summarize_for_session_memory(
                result=output.result,
                agent_name=output.agent_name,
                success=output.success,
            ),
            details={
                "agent": output.agent_name,
                "success": output.success,
                "confidence": output.confidence,
                "changed_files": output.changed_files[:5],
            },
            task_id=task_id,
        )


# Singleton
retention_policy = RetentionPolicy()
