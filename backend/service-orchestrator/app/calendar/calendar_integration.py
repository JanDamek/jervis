"""EPIC 12-S2/S5: Calendar Integration for Orchestrator.

Provides calendar awareness for the chat context assembler and
scheduler integration. Fetches today's events and availability
to inform autonomous scheduling decisions.
"""

from __future__ import annotations

import logging
from datetime import date, datetime, timedelta

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


async def get_today_context(client_id: str) -> str | None:
    """Fetch today's calendar context from the Kotlin server.

    Returns a human-readable summary for the system prompt, or None
    if no calendar connection is configured.
    """
    try:
        async with httpx.AsyncClient(timeout=10) as http:
            resp = await http.get(
                f"{settings.kotlin_server_url}/api/calendar/today-context",
                params={"clientId": client_id},
                headers={"Authorization": f"Bearer {settings.internal_api_key}"},
            )
            if resp.status_code == 200:
                data = resp.json()
                context = data.get("context", "")
                return context if context else None
            return None
    except Exception:
        logger.debug("Calendar context unavailable", exc_info=True)
        return None


async def get_availability(client_id: str, target_date: str | None = None) -> dict | None:
    """Fetch availability info for a specific date.

    Used by scheduler integration to decide when Jervis should work
    autonomously (user busy) or defer (user free for interaction).
    """
    if target_date is None:
        target_date = date.today().isoformat()

    try:
        async with httpx.AsyncClient(timeout=10) as http:
            resp = await http.get(
                f"{settings.kotlin_server_url}/api/calendar/availability",
                params={"clientId": client_id, "date": target_date},
                headers={"Authorization": f"Bearer {settings.internal_api_key}"},
            )
            if resp.status_code == 200:
                return resp.json()
            return None
    except Exception:
        logger.debug("Availability check failed", exc_info=True)
        return None


def should_work_autonomously(availability: dict | None) -> bool:
    """E12-S5: Determine if Jervis should work autonomously.

    When the user is busy (in meetings), Jervis should prioritize
    autonomous background work. When the user is free, Jervis should
    be responsive and interactive.
    """
    if availability is None:
        return False  # Default: be responsive
    return availability.get("isUserBusy", False)


def format_scheduling_context(availability: dict | None) -> str:
    """Format availability into a scheduling context string for prompts."""
    if availability is None:
        return ""

    free_slots = availability.get("freeSlots", [])
    busy_slots = availability.get("busySlots", [])
    is_busy = availability.get("isUserBusy", False)

    lines = []
    if is_busy:
        lines.append("User is currently in a meeting — work autonomously.")
    else:
        lines.append("User is currently free — be responsive.")

    if busy_slots:
        lines.append(f"Busy slots today: {len(busy_slots)}")
    if free_slots:
        lines.append(f"Free slots today: {len(free_slots)}")

    return "\n".join(lines)
