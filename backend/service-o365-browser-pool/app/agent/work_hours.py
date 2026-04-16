"""Work hours + user activity helpers.

The pod refuses to submit credentials outside work hours when the user has
been idle for a while — it asks for explicit approval via kind='auth_request'.
"""

from __future__ import annotations

import logging
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx

from app.config import settings

logger = logging.getLogger("o365-browser-pool.work_hours")

TIMEZONE = "Europe/Prague"
WORK_DAYS = {0, 1, 2, 3, 4}
WORK_START_HOUR = 9
WORK_END_HOUR = 16
RECENT_ACTIVITY_THRESHOLD_S = 300


def is_work_hours_now() -> bool:
    now = datetime.now(ZoneInfo(TIMEZONE))
    return (
        now.weekday() in WORK_DAYS
        and WORK_START_HOUR <= now.hour < WORK_END_HOUR
    )


async def query_user_activity_seconds(client_id: str) -> int:
    """Seconds since the client's UI was last active. Returns a large number
    when unknown — conservative default keeps the pod cautious off-hours."""
    if not settings.kotlin_server_url:
        return 10 ** 9
    url = f"{settings.kotlin_server_url}/internal/user/last-activity"
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            resp = await client.get(url, params={"clientId": client_id})
            if resp.status_code == 200:
                data = resp.json()
                return int(data.get("last_active_seconds", 10 ** 9))
    except Exception as e:
        logger.warning("last-activity query failed: %s", e)
    return 10 ** 9
