"""Budget tracker for Claude companion jobs.

Simple counter per UTC day, stored in MongoDB collection orchestrator_companion_usage.
Companion dispatcher validates limits before starting a Job; on breach it pushes a
warning (70/90/100 %) but does not hard-block — the user decides whether to continue.
"""

from __future__ import annotations

import datetime
import logging
from dataclasses import dataclass

from app.config import settings

logger = logging.getLogger(__name__)


@dataclass
class BudgetDecision:
    allowed: bool
    warning_pct: int | None  # 70 / 90 / 100 or None
    adhoc_today: int
    sessions_active: int
    reason: str = ""


class CompanionBudget:
    """Mongo-backed soft budget tracker for Claude companion Jobs."""

    COLL = "orchestrator_companion_usage"

    def __init__(self):
        self._db = None

    def _get_db(self):
        if self._db is None:
            from pymongo import MongoClient
            client = MongoClient(settings.mongodb_url)
            self._db = client.get_default_database()
        return self._db

    @staticmethod
    def _today_key() -> str:
        return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")

    def check(self, active_sessions: int) -> BudgetDecision:
        """Check budget before dispatching a new adhoc Job or starting a session."""
        try:
            coll = self._get_db()[self.COLL]
            doc = coll.find_one({"_id": self._today_key()}) or {}
        except Exception as e:
            logger.warning("Companion budget: mongo unavailable, allowing by default: %s", e)
            return BudgetDecision(allowed=True, warning_pct=None, adhoc_today=0, sessions_active=active_sessions)

        adhoc_today = int(doc.get("adhoc_count", 0))
        cap = settings.companion_max_adhoc_per_hour * 24  # rough daily ceiling
        pct = int(100 * adhoc_today / max(cap, 1))
        warning = None
        for threshold in (100, 90, 70):
            if pct >= threshold:
                warning = threshold
                break

        if active_sessions >= settings.companion_max_concurrent_sessions:
            return BudgetDecision(
                allowed=False,
                warning_pct=warning,
                adhoc_today=adhoc_today,
                sessions_active=active_sessions,
                reason=f"Max concurrent sessions ({settings.companion_max_concurrent_sessions}) reached",
            )

        return BudgetDecision(
            allowed=True,
            warning_pct=warning,
            adhoc_today=adhoc_today,
            sessions_active=active_sessions,
        )

    def record_adhoc(self) -> None:
        try:
            coll = self._get_db()[self.COLL]
            coll.update_one(
                {"_id": self._today_key()},
                {"$inc": {"adhoc_count": 1}, "$set": {"last_update": datetime.datetime.now(datetime.timezone.utc)}},
                upsert=True,
            )
        except Exception as e:
            logger.warning("Companion budget: failed to record adhoc: %s", e)

    def record_session_start(self, session_id: str) -> None:
        try:
            coll = self._get_db()[self.COLL]
            coll.update_one(
                {"_id": self._today_key()},
                {"$inc": {"session_starts": 1},
                 "$addToSet": {"sessions": session_id}},
                upsert=True,
            )
        except Exception as e:
            logger.warning("Companion budget: failed to record session start: %s", e)


companion_budget = CompanionBudget()
