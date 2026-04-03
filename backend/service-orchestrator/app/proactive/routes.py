"""Proactive communication routes — scheduled triggers.

Registered as cron-like scheduled tasks:
- Morning briefing: daily 7:00
- Invoice overdue check: daily 9:00
- Weekly summary: Monday 8:00

These routes are called by the internal scheduler or can be triggered manually.
"""

import logging

import httpx
from fastapi import APIRouter

from app.config import settings

logger = logging.getLogger(__name__)

router = APIRouter(tags=["proactive"])

# Default client ID for proactive tasks (Jervis project owner)
DEFAULT_CLIENT_ID = "68a332361b04695a243e5ae8"


async def _post_to_kotlin(path: str, payload: dict | None = None) -> dict:
    """POST to Kotlin proactive API."""
    url = f"{settings.kotlin_server_url}/internal/proactive/{path}"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(url, json=payload or {})
        return resp.json()


@router.post("/proactive/morning-briefing")
async def trigger_morning_briefing(client_id: str | None = None):
    """Trigger morning briefing generation."""
    cid = client_id or DEFAULT_CLIENT_ID
    try:
        result = await _post_to_kotlin("morning-briefing", {"clientId": cid})
        return {"status": "ok", "result": result}
    except Exception as e:
        logger.error("Morning briefing failed: %s", e)
        return {"status": "error", "error": str(e)}


@router.post("/proactive/overdue-check")
async def trigger_overdue_check():
    """Trigger overdue invoice check."""
    try:
        result = await _post_to_kotlin("overdue-check")
        return {"status": "ok", "result": result}
    except Exception as e:
        logger.error("Overdue check failed: %s", e)
        return {"status": "error", "error": str(e)}


@router.post("/proactive/weekly-summary")
async def trigger_weekly_summary(client_id: str | None = None):
    """Trigger weekly summary generation."""
    cid = client_id or DEFAULT_CLIENT_ID
    try:
        result = await _post_to_kotlin("weekly-summary", {"clientId": cid})
        return {"status": "ok", "result": result}
    except Exception as e:
        logger.error("Weekly summary failed: %s", e)
        return {"status": "error", "error": str(e)}


@router.post("/proactive/vip-alert")
async def trigger_vip_alert(client_id: str, sender_name: str, subject: str):
    """Send VIP email alert."""
    try:
        result = await _post_to_kotlin("vip-alert", {
            "clientId": client_id,
            "senderName": sender_name,
            "subject": subject,
        })
        return {"status": "ok", "result": result}
    except Exception as e:
        logger.error("VIP alert failed: %s", e)
        return {"status": "error", "error": str(e)}
