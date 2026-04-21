"""Proactive communication trigger functions.

These are plain async functions invoked by the internal scheduler
(see `app/proactive/scheduler.py`). Each function dials
`ServerProactiveService` gRPC on the Kotlin server. No HTTP route
exposes these — they are internal scheduling hooks only.
"""

import logging

from app.grpc_server_client import server_proactive_stub
from jervis.server import proactive_pb2
from jervis.common import types_pb2
from jervis_contracts.interceptors import prepare_context

logger = logging.getLogger(__name__)

# Default client ID for proactive tasks (Jervis project owner)
DEFAULT_CLIENT_ID = "68a332361b04695a243e5ae8"


def _ctx() -> types_pb2.RequestContext:
    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    return ctx


async def trigger_morning_briefing(client_id: str | None = None) -> dict:
    """Trigger morning briefing generation."""
    cid = client_id or DEFAULT_CLIENT_ID
    try:
        resp = await server_proactive_stub().MorningBriefing(
            proactive_pb2.MorningBriefingRequest(ctx=_ctx(), client_id=cid),
            timeout=60.0,
        )
        return {"status": "ok", "result": {"briefing": resp.briefing}}
    except Exception as e:
        logger.error("Morning briefing failed: %s", e)
        return {"status": "error", "error": str(e)}


async def trigger_overdue_check() -> dict:
    """Trigger overdue invoice check."""
    try:
        resp = await server_proactive_stub().OverdueCheck(
            proactive_pb2.OverdueCheckRequest(ctx=_ctx()),
            timeout=60.0,
        )
        return {"status": "ok", "result": {"overdueCount": resp.overdue_count}}
    except Exception as e:
        logger.error("Overdue check failed: %s", e)
        return {"status": "error", "error": str(e)}


async def trigger_weekly_summary(client_id: str | None = None) -> dict:
    """Trigger weekly summary generation."""
    cid = client_id or DEFAULT_CLIENT_ID
    try:
        resp = await server_proactive_stub().WeeklySummary(
            proactive_pb2.WeeklySummaryRequest(ctx=_ctx(), client_id=cid),
            timeout=60.0,
        )
        return {"status": "ok", "result": {"summary": resp.summary}}
    except Exception as e:
        logger.error("Weekly summary failed: %s", e)
        return {"status": "error", "error": str(e)}


async def trigger_vip_alert(client_id: str, sender_name: str, subject: str) -> dict:
    """Send VIP email alert."""
    try:
        await server_proactive_stub().VipAlert(
            proactive_pb2.VipAlertRequest(
                ctx=_ctx(),
                client_id=client_id,
                sender_name=sender_name,
                subject=subject,
            ),
            timeout=30.0,
        )
        return {"status": "ok"}
    except Exception as e:
        logger.error("VIP alert failed: %s", e)
        return {"status": "error", "error": str(e)}
