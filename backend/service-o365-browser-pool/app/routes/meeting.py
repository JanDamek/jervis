"""Meeting recording routes — debug only.

Scheduled meeting joins come in via `/instruction/{connectionId}`
(server → agent as HumanMessage). The agent composes navigate + join via
tools. Ad-hoc recordings attach when the background watcher detects
`meeting_stage` rising. There is no /meeting/join direct RPC path —
per docs/teams-pod-agent.md §15 hard rule.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

from app.meeting_recorder import MeetingRecorder

logger = logging.getLogger("o365-browser-pool.meeting")


def create_meeting_router(recorder: MeetingRecorder) -> APIRouter:
    router = APIRouter(tags=["meeting"])

    @router.get("/meeting/sessions")
    async def list_sessions() -> dict:
        """Read-only diagnostic: list all in-flight recording sessions."""
        return {"sessions": recorder.get_sessions()}

    return router
