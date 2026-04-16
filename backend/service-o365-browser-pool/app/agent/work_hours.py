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
    try:
        from app.grpc_clients import server_user_activity_stub
        from jervis.server import o365_resources_pb2
        from jervis.common import types_pb2
        from jervis_contracts.interceptors import prepare_context

        ctx = types_pb2.RequestContext()
        prepare_context(ctx)
        resp = await server_user_activity_stub().LastActivity(
            o365_resources_pb2.LastActivityRequest(ctx=ctx, client_id=client_id),
            timeout=5.0,
        )
        return int(resp.last_active_seconds)
    except Exception as e:
        logger.warning("last-activity query failed: %s", e)
    return 10 ** 9
