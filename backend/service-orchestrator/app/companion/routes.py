"""HTTP endpoints for Claude companion dispatch & sessions.

Endpoints:
    POST /companion/adhoc           — dispatch a one-shot deep-analysis Job
    GET  /companion/adhoc/{task_id} — poll adhoc result.json
    POST /companion/session         — start a persistent session Job
    POST /companion/session/{id}/event — append an event to the session inbox
    GET  /companion/session/{id}/stream — SSE stream of outbox events
    POST /companion/session/{id}/stop  — graceful shutdown

All routes are internal (called by Kotlin server or orchestrator nodes).
Auth handled at ingress / Kotlin gateway.
"""

from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.agents.companion_runner import companion_runner
from app.config import settings

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/companion", tags=["companion"])


class AdhocRequest(BaseModel):
    task_id: str
    brief: str
    client_id: str = ""
    project_id: str | None = None
    language: str = "cs"
    context: dict = Field(default_factory=dict)
    attachment_paths: list[str] = Field(default_factory=list)


class SessionStartRequest(BaseModel):
    session_id: str | None = None
    brief: str
    client_id: str = ""
    project_id: str | None = None
    language: str = "cs"
    context: dict = Field(default_factory=dict)
    attachment_paths: list[str] = Field(default_factory=list)


class SessionEventRequest(BaseModel):
    type: str = "user"  # user | meeting | system
    content: str
    meta: dict = Field(default_factory=dict)


@router.post("/adhoc")
async def adhoc_dispatch(req: AdhocRequest):
    attachments = [Path(p) for p in req.attachment_paths if Path(p).exists()]
    try:
        disp = await companion_runner.dispatch_adhoc(
            task_id=req.task_id,
            brief=req.brief,
            context=req.context,
            attachments=attachments,
            client_id=req.client_id,
            project_id=req.project_id,
            language=req.language,
        )
    except RuntimeError as e:
        raise HTTPException(status_code=429, detail=str(e))
    return {
        "job_name": disp.job_name,
        "workspace_path": disp.workspace_path,
        "mode": disp.mode,
    }


@router.get("/adhoc/{task_id}")
async def adhoc_result(task_id: str, workspace_path: str):
    result = companion_runner.collect_adhoc_result(workspace_path)
    if result is None:
        return {"task_id": task_id, "status": "pending"}
    return {"task_id": task_id, "status": "done", "result": result}


@router.post("/session")
async def session_start(req: SessionStartRequest):
    attachments = [Path(p) for p in req.attachment_paths if Path(p).exists()]
    try:
        disp = await companion_runner.start_session(
            session_id=req.session_id,
            brief=req.brief,
            context=req.context,
            attachments=attachments,
            client_id=req.client_id,
            project_id=req.project_id,
            language=req.language,
        )
    except RuntimeError as e:
        raise HTTPException(status_code=429, detail=str(e))
    return {
        "job_name": disp.job_name,
        "workspace_path": disp.workspace_path,
        "session_id": disp.session_id,
    }


@router.post("/session/{session_id}/event")
async def session_event(session_id: str, req: SessionEventRequest):
    try:
        companion_runner.send_event(session_id, req.type, req.content, req.meta)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"send_event failed: {e}")
    return {"ok": True}


@router.get("/session/{session_id}/stream")
async def session_stream(
    session_id: str,
    max_age_seconds: float | None = Query(
        default=None,
        description=(
            "Drop outbox events older than N seconds. Default: "
            "settings.companion_assistant_event_ttl_seconds (assistant-safe). "
            "Pass 0 or a large value to disable TTL filtering."
        ),
    ),
):
    ttl = max_age_seconds if max_age_seconds is not None else settings.companion_assistant_event_ttl_seconds
    effective_ttl: float | None = None if ttl <= 0 else float(ttl)

    stop_event = asyncio.Event()

    async def generator():
        try:
            async for event in companion_runner.stream_outbox(
                session_id, stop_event=stop_event, max_age_seconds=effective_ttl,
            ):
                yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except asyncio.CancelledError:
            stop_event.set()
            raise

    return StreamingResponse(generator(), media_type="text/event-stream")


@router.post("/session/{session_id}/stop")
async def session_stop(session_id: str):
    await companion_runner.stop_session(session_id)
    return {"ok": True}
