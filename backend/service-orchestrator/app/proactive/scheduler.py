"""Proactive scheduler — asyncio-based cron triggers for proactive communication.

Runs scheduled proactive tasks:
- Morning briefing: daily 7:00 CET
- Invoice overdue check: daily 9:00 CET
- Weekly summary: Monday 8:00 CET

Uses asyncio tasks (no external scheduler dependency).
Calls internal REST endpoints defined in routes.py → Kotlin ProactiveScheduler.
"""

import asyncio
import logging
from datetime import datetime, time, timedelta

import httpx
import zoneinfo

from app.settings import settings

logger = logging.getLogger(__name__)

# Prague timezone for scheduling
_TZ = zoneinfo.ZoneInfo("Europe/Prague")

# Schedule configuration
_SCHEDULES = [
    {
        "name": "morning-briefing",
        "time": time(7, 0),
        "days": [0, 1, 2, 3, 4],  # Mon-Fri
        "endpoint": "morning-briefing",
        "payload": {},
    },
    {
        "name": "overdue-check",
        "time": time(9, 0),
        "days": [0, 1, 2, 3, 4],  # Mon-Fri
        "endpoint": "overdue-check",
        "payload": None,
    },
    {
        "name": "weekly-summary",
        "time": time(8, 0),
        "days": [0],  # Monday only
        "endpoint": "weekly-summary",
        "payload": {},
    },
]

DEFAULT_CLIENT_ID = "68a332361b04695a243e5ae8"

_task: asyncio.Task | None = None


def _seconds_until(target_time: time, target_days: list[int]) -> float:
    """Calculate seconds until next occurrence of target_time on target_days."""
    now = datetime.now(_TZ)
    today_target = now.replace(
        hour=target_time.hour, minute=target_time.minute, second=0, microsecond=0
    )

    # Check today first
    if now.weekday() in target_days and now < today_target:
        return (today_target - now).total_seconds()

    # Find next matching day
    for i in range(1, 8):
        candidate = today_target + timedelta(days=i)
        if candidate.weekday() in target_days:
            return (candidate - now).total_seconds()

    return 86400.0  # fallback: 24h


async def _execute_trigger(schedule: dict):
    """Execute a single proactive trigger."""
    endpoint = schedule["endpoint"]
    payload = schedule.get("payload")
    if payload is not None and "clientId" not in payload:
        payload["clientId"] = DEFAULT_CLIENT_ID

    url = f"http://localhost:{settings.port}/proactive/{endpoint}"
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(url, json=payload)
            logger.info(
                "Proactive trigger [%s]: %s (status=%d)",
                schedule["name"],
                resp.json() if resp.status_code == 200 else resp.text,
                resp.status_code,
            )
    except Exception as e:
        logger.error("Proactive trigger [%s] failed: %s", schedule["name"], e)


async def _scheduler_loop():
    """Main scheduler loop — sleeps until next trigger, executes, repeats."""
    logger.info("Proactive scheduler started (timezone: Europe/Prague)")

    # Initial delay to let server fully start
    await asyncio.sleep(10)

    while True:
        try:
            # Find the nearest trigger
            nearest_wait = float("inf")
            nearest_schedule = None

            for schedule in _SCHEDULES:
                wait = _seconds_until(schedule["time"], schedule["days"])
                if wait < nearest_wait:
                    nearest_wait = wait
                    nearest_schedule = schedule

            if nearest_schedule is None:
                await asyncio.sleep(3600)
                continue

            next_fire = datetime.now(_TZ) + timedelta(seconds=nearest_wait)
            logger.info(
                "Next proactive trigger: [%s] at %s (in %.0f min)",
                nearest_schedule["name"],
                next_fire.strftime("%Y-%m-%d %H:%M"),
                nearest_wait / 60,
            )

            # Sleep until trigger time
            await asyncio.sleep(nearest_wait)

            # Execute all triggers that match this time
            now = datetime.now(_TZ)
            for schedule in _SCHEDULES:
                target = now.replace(
                    hour=schedule["time"].hour,
                    minute=schedule["time"].minute,
                    second=0,
                    microsecond=0,
                )
                if now.weekday() in schedule["days"] and abs((now - target).total_seconds()) < 120:
                    await _execute_trigger(schedule)

            # Wait 2 minutes before recalculating (avoid double-firing)
            await asyncio.sleep(120)

        except asyncio.CancelledError:
            logger.info("Proactive scheduler cancelled")
            break
        except Exception as e:
            logger.error("Proactive scheduler error: %s", e, exc_info=True)
            await asyncio.sleep(300)  # wait 5 min on error


async def start():
    """Start the proactive scheduler as background task."""
    global _task
    if _task is not None:
        return
    _task = asyncio.create_task(_scheduler_loop())
    logger.info("Proactive scheduler task created")


async def stop():
    """Stop the proactive scheduler."""
    global _task
    if _task is not None:
        _task.cancel()
        try:
            await _task
        except asyncio.CancelledError:
            pass
        _task = None
        logger.info("Proactive scheduler stopped")
