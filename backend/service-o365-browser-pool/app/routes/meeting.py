"""Meeting join + recording routes for browser pool."""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.meeting_recorder import MeetingRecorder

logger = logging.getLogger("o365-browser-pool.meeting")


class MeetingJoinRequest(BaseModel):
    task_id: str
    client_id: str
    meeting_id: str
    join_url: str
    end_time_epoch: float


def create_meeting_router(recorder: MeetingRecorder) -> APIRouter:
    router = APIRouter(tags=["meeting"])

    @router.post("/meeting/join")
    async def join_meeting(req: MeetingJoinRequest) -> dict:
        session = await recorder.join(
            task_id=req.task_id,
            client_id=req.client_id,
            meeting_id=req.meeting_id,
            join_url=req.join_url,
            end_time_epoch=req.end_time_epoch,
        )
        return {
            "task_id": session.task_id,
            "meeting_id": session.meeting_id,
            "state": session.state,
            "chunks_sent": session.chunks_sent,
        }

    @router.post("/meeting/stop")
    async def stop_meeting(task_id: str) -> dict:
        await recorder.stop(task_id)
        return {"status": "stopped", "task_id": task_id}

    @router.get("/meeting/sessions")
    async def list_sessions() -> dict:
        return {"sessions": recorder.get_sessions()}

    return router
