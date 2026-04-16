"""Proactive communication routes — scheduled triggers.

Registered as cron-like scheduled tasks:
- Morning briefing: daily 7:00
- Invoice overdue check: daily 9:00
- Weekly summary: Monday 8:00

These routes are called by the internal scheduler or can be triggered manually.
"""

import logging

from fastapi import APIRouter

from app.grpc_server_client import server_proactive_stub
from jervis.server import proactive_pb2
from jervis.common import types_pb2
from jervis_contracts.interceptors import prepare_context

logger = logging.getLogger(__name__)

router = APIRouter(tags=["proactive"])

# Default client ID for proactive tasks (Jervis project owner)
DEFAULT_CLIENT_ID = "68a332361b04695a243e5ae8"


def _ctx() -> types_pb2.RequestContext:
    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    return ctx


@router.post("/proactive/morning-briefing")
async def trigger_morning_briefing(client_id: str | None = None):
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


@router.post("/proactive/overdue-check")
async def trigger_overdue_check():
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


@router.post("/proactive/weekly-summary")
async def trigger_weekly_summary(client_id: str | None = None):
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


@router.post("/proactive/vip-alert")
async def trigger_vip_alert(client_id: str, sender_name: str, subject: str):
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
