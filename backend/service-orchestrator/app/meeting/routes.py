"""Meeting Helper routes — processes transcript chunks and pushes results to Kotlin server."""

from __future__ import annotations

import logging

import httpx
from fastapi import APIRouter

from app.config import Settings
from app.meeting.live_helper import (
    get_session,
    process_transcript_chunk,
    start_session,
    stop_session,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/meeting-helper", tags=["meeting-helper"])

settings = Settings()


async def _push_to_kotlin(meeting_id: str, message) -> None:
    """Push a helper message to the Kotlin server for WebSocket broadcast."""
    url = f"{settings.kotlin_server_url}/internal/meeting-helper/push"
    payload = {
        "meetingId": meeting_id,
        "type": message.type,
        "text": message.text,
        "context": message.context,
        "fromLang": message.from_lang,
        "toLang": message.to_lang,
        "timestamp": message.timestamp,
    }
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, json=payload)
            if resp.status_code != 200:
                logger.warning(
                    "Failed to push helper message to Kotlin: %s %s",
                    resp.status_code,
                    resp.text[:200],
                )
    except Exception as e:
        logger.warning("Error pushing helper message to Kotlin: %s", e)


@router.post("/start")
async def start_helper_session(body: dict) -> dict:
    """Start a meeting helper session.

    Body: {meetingId, deviceId, sourceLang?, targetLang?}
    """
    meeting_id = body["meetingId"]
    device_id = body["deviceId"]
    source_lang = body.get("sourceLang", "en")
    target_lang = body.get("targetLang", "cs")

    session = start_session(meeting_id, device_id, source_lang, target_lang)
    logger.info(
        "Meeting helper session started: meeting=%s, device=%s, %s->%s",
        meeting_id,
        device_id,
        source_lang,
        target_lang,
    )
    return {
        "status": "ok",
        "meetingId": session.meeting_id,
        "deviceId": session.device_id,
        "sourceLang": session.source_lang,
        "targetLang": session.target_lang,
    }


@router.post("/stop")
async def stop_helper_session(body: dict) -> dict:
    """Stop a meeting helper session.

    Body: {meetingId}
    """
    meeting_id = body["meetingId"]
    stop_session(meeting_id)
    logger.info("Meeting helper session stopped: meeting=%s", meeting_id)
    return {"status": "ok", "meetingId": meeting_id}


@router.post("/chunk")
async def process_chunk(body: dict) -> dict:
    """Process a transcript chunk through the meeting helper pipeline.

    Body: {meetingId, text, speaker?}

    Called by Kotlin server when a new transcript segment is available during recording.
    Runs LLM analysis and pushes results back to Kotlin for WebSocket broadcast.
    """
    meeting_id = body["meetingId"]
    text = body["text"]
    speaker = body.get("speaker", "")

    session = get_session(meeting_id)
    if not session:
        return {"status": "no_session", "meetingId": meeting_id}

    # Get LLM provider
    try:
        from app.llm.provider import get_provider

        llm_provider = get_provider()
    except Exception as e:
        logger.warning("Failed to get LLM provider for meeting helper: %s", e)
        return {"status": "error", "error": str(e)}

    # Process through helper pipeline (translation + suggestions)
    messages = await process_transcript_chunk(
        meeting_id=meeting_id,
        transcript_text=text,
        speaker=speaker,
        llm_provider=llm_provider,
    )

    # Push non-empty messages to Kotlin for WebSocket broadcast
    pushed = 0
    for msg in messages:
        if msg.text:
            await _push_to_kotlin(meeting_id, msg)
            pushed += 1

    return {"status": "ok", "messagesPushed": pushed}


@router.get("/status/{meeting_id}")
async def get_helper_status(meeting_id: str) -> dict:
    """Get the status of a meeting helper session."""
    session = get_session(meeting_id)
    if not session:
        return {"active": False, "meetingId": meeting_id}
    return {
        "active": session.active,
        "meetingId": session.meeting_id,
        "deviceId": session.device_id,
        "sourceLang": session.source_lang,
        "targetLang": session.target_lang,
        "contextSize": len(session.context_window),
    }
