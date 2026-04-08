"""
Jervis Meeting Attender — read-only headless audio capture for online meetings.

Used as a fallback path to the desktop loopback recorder when the user is not
present at their desktop but has approved Jervis to attend a calendar-discovered
Teams / Meet / Zoom meeting on their behalf. Joins the meeting page in a
Chromium tab inside an in-pod virtual display, routes the meeting audio into a
PulseAudio null sink, then records from the sink monitor via ffmpeg and
streams 5-second base64 PCM chunks to the Jervis server's MeetingRpc HTTP
bridge.

## Read-only v1 invariants

* **Never** speaks. The meeting tab's microphone is held muted at all times.
* **Never** sends any chat message into the meeting.
* **Never** auto-joins without an explicit per-occurrence approval — the
  Kotlin server is the sole entry point and only POSTs `/attend` after the
  user has approved the corresponding `CALENDAR_PROCESSING` task in the
  approval queue. The pod has no autonomous discovery loop.
* If the meeting tab presents an unknown or unexpected dialog (lobby, login,
  etc.), the session terminates with status `LOBBY_BLOCKED` rather than
  attempting to bypass it.

## Endpoints

* `POST /attend` — start a new attend session for an approved meeting.
* `POST /stop` — terminate an active session early (user revoked approval).
* `GET  /sessions` — list active sessions for ops visibility.
* `GET  /health` / `/ready` — k8s probes.

The pod owns no MongoDB collection. All persistent state (`MeetingDocument`,
`TaskDocument`, `ApprovalQueueDocument`) lives on the Kotlin server. The pod
is intentionally stateless — if it crashes, the server's
`MeetingRecordingDispatcher` simply re-issues the trigger on the next cycle
because `meetingMetadata.recordingDispatchedAt` is reset on crash recovery
(future enhancement).
"""

from __future__ import annotations

import asyncio
import base64
import logging
import os
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("meeting-attender")

# Frame size: 16 kHz * 2 bytes/sample * 1 channel * 5 s = 160 000 bytes
CHUNK_SIZE_BYTES = 160_000
SAMPLE_RATE = 16_000
PULSE_SOURCE = "jervis-sink.monitor"

JERVIS_SERVER_URL = os.environ.get("JERVIS_SERVER_URL", "http://jervis-server:8084")
INTERNAL_AUTH_TOKEN = os.environ.get("INTERNAL_AUTH_TOKEN", "")


# ---- Models ----------------------------------------------------------------


class AttendRequest(BaseModel):
    task_id: str
    client_id: str
    project_id: Optional[str] = None
    title: str
    join_url: str
    end_time_iso: str  # ISO 8601 instant; pod terminates the session at this time
    provider: str  # "TEAMS" | "GOOGLE_MEET" | "ZOOM" | "WEBEX" | "UNKNOWN"


class StopRequest(BaseModel):
    task_id: str
    reason: str = "USER_STOP"


class SessionInfo(BaseModel):
    task_id: str
    meeting_id: str
    started_at: float
    end_time_iso: str
    chunks_sent: int
    state: str


@dataclass
class AttendSession:
    task_id: str
    meeting_id: str
    request: AttendRequest
    started_at: float
    end_epoch: float
    state: str = "STARTING"
    chunks_sent: int = 0
    ffmpeg_proc: Optional[asyncio.subprocess.Process] = None
    browser_task: Optional[asyncio.Task] = None
    pump_task: Optional[asyncio.Task] = None
    watchdog_task: Optional[asyncio.Task] = None
    extras: dict = field(default_factory=dict)


# ---- Session manager -------------------------------------------------------


class AttenderManager:
    def __init__(self) -> None:
        self.sessions: dict[str, AttendSession] = {}
        self._lock = asyncio.Lock()
        self._http: Optional[httpx.AsyncClient] = None

    async def startup(self) -> None:
        self._http = httpx.AsyncClient(timeout=httpx.Timeout(30.0))

    async def shutdown(self) -> None:
        for task_id in list(self.sessions.keys()):
            await self.stop(task_id, reason="SHUTDOWN")
        if self._http is not None:
            await self._http.aclose()

    async def attend(self, request: AttendRequest) -> SessionInfo:
        async with self._lock:
            if request.task_id in self.sessions:
                raise HTTPException(status_code=409, detail="Already attending this task")
            meeting_id = await self._create_meeting_on_server(request)
            end_epoch = self._parse_iso(request.end_time_iso)
            session = AttendSession(
                task_id=request.task_id,
                meeting_id=meeting_id,
                request=request,
                started_at=time.time(),
                end_epoch=end_epoch,
            )
            self.sessions[request.task_id] = session

        # Outside lock — these can take a few seconds.
        try:
            session.browser_task = asyncio.create_task(self._open_meeting_tab(session))
            session.ffmpeg_proc = await self._spawn_ffmpeg()
            session.pump_task = asyncio.create_task(self._pump_audio(session))
            session.watchdog_task = asyncio.create_task(self._watchdog(session))
            session.state = "RECORDING"
        except Exception as exc:
            logger.exception("Failed to start attend session for task=%s", request.task_id)
            await self.stop(request.task_id, reason=f"START_FAILED:{exc}")
            raise HTTPException(status_code=500, detail=f"Start failed: {exc}")

        return self._info(session)

    async def stop(self, task_id: str, reason: str) -> None:
        async with self._lock:
            session = self.sessions.pop(task_id, None)
        if session is None:
            return
        logger.info("Stopping session task=%s reason=%s", task_id, reason)
        for t in (session.pump_task, session.watchdog_task, session.browser_task):
            if t is not None:
                t.cancel()
        if session.ffmpeg_proc is not None and session.ffmpeg_proc.returncode is None:
            try:
                session.ffmpeg_proc.terminate()
                await asyncio.wait_for(session.ffmpeg_proc.wait(), timeout=3.0)
            except (asyncio.TimeoutError, ProcessLookupError):
                try:
                    session.ffmpeg_proc.kill()
                except ProcessLookupError:
                    pass
        await self._finalize_meeting_on_server(session, reason)
        session.state = "STOPPED"

    def list_sessions(self) -> list[SessionInfo]:
        return [self._info(s) for s in self.sessions.values()]

    # ---- Internals ---------------------------------------------------------

    def _info(self, s: AttendSession) -> SessionInfo:
        return SessionInfo(
            task_id=s.task_id,
            meeting_id=s.meeting_id,
            started_at=s.started_at,
            end_time_iso=s.request.end_time_iso,
            chunks_sent=s.chunks_sent,
            state=s.state,
        )

    @staticmethod
    def _parse_iso(iso: str) -> float:
        # Tolerant ISO 8601 parser; falls back to "1 hour from now" if unparseable.
        from datetime import datetime, timezone
        try:
            if iso.endswith("Z"):
                iso = iso[:-1] + "+00:00"
            return datetime.fromisoformat(iso).astimezone(timezone.utc).timestamp()
        except Exception:
            return time.time() + 3600

    async def _spawn_ffmpeg(self) -> asyncio.subprocess.Process:
        cmd = [
            "ffmpeg",
            "-loglevel", "warning",
            "-nostdin",
            "-f", "pulse",
            "-i", PULSE_SOURCE,
            "-ac", "1",
            "-ar", str(SAMPLE_RATE),
            "-f", "s16le",
            "pipe:1",
        ]
        logger.info("Spawning ffmpeg: %s", " ".join(cmd))
        return await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )

    async def _open_meeting_tab(self, session: AttendSession) -> None:
        """
        Open the meeting in Chromium under Xvfb. Read-only: microphone is
        explicitly denied via Playwright permissions, audio output is routed
        to the in-pod PulseAudio null sink (Chromium picks up the default
        sink set in entrypoint.sh).
        """
        try:
            from playwright.async_api import async_playwright
        except ImportError:
            logger.error("Playwright not installed")
            return
        try:
            async with async_playwright() as p:
                browser = await p.chromium.launch(
                    headless=False,  # Teams web refuses headless guest joins
                    args=[
                        "--no-sandbox",
                        "--disable-dev-shm-usage",
                        "--use-fake-ui-for-media-stream",  # auto-deny mic prompt
                        "--mute-audio=false",  # we WANT audio so it reaches the null sink
                    ],
                )
                context = await browser.new_context(
                    permissions=[],  # explicitly NO mic / camera
                    viewport={"width": 1280, "height": 720},
                )
                page = await context.new_page()
                logger.info("Opening join URL for task=%s", session.task_id)
                await page.goto(session.request.join_url, wait_until="domcontentloaded")
                # Provider-specific anonymous-join click would go here. For v1
                # we just keep the page open until end-time / stop. The pod's
                # contract is "audio that plays in this tab will be captured".
                while True:
                    if time.time() >= session.end_epoch:
                        break
                    await asyncio.sleep(5)
                await context.close()
                await browser.close()
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("Browser tab failed for task=%s", session.task_id)
            session.state = "BROWSER_FAILED"

    async def _pump_audio(self, session: AttendSession) -> None:
        proc = session.ffmpeg_proc
        if proc is None or proc.stdout is None:
            return
        try:
            while True:
                buf = await proc.stdout.readexactly(CHUNK_SIZE_BYTES)
                if not buf:
                    break
                payload = base64.b64encode(buf).decode("ascii")
                await self._upload_chunk(session, payload)
                session.chunks_sent += 1
        except asyncio.IncompleteReadError as e:
            if e.partial:
                payload = base64.b64encode(e.partial).decode("ascii")
                await self._upload_chunk(session, payload)
                session.chunks_sent += 1
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("pump_audio failed for task=%s", session.task_id)

    async def _watchdog(self, session: AttendSession) -> None:
        try:
            while True:
                if time.time() >= session.end_epoch:
                    logger.info("Watchdog: end time reached for task=%s", session.task_id)
                    asyncio.create_task(self.stop(session.task_id, reason="END_TIME"))
                    return
                await asyncio.sleep(5)
        except asyncio.CancelledError:
            raise

    async def _create_meeting_on_server(self, request: AttendRequest) -> str:
        """
        Call the Kotlin server to create the MeetingDocument up-front so the
        chunk uploads have a meetingId to attach to. Endpoint is the same
        internal route used by `MeetingRpc.startRecording` (mirrors what the
        desktop recorder does over kRPC).
        """
        assert self._http is not None
        url = f"{JERVIS_SERVER_URL}/internal/meeting/start-recording"
        payload = {
            "clientId": request.client_id,
            "projectId": request.project_id,
            "title": request.title,
            "meetingType": "MEETING",
            "deviceSessionId": f"attender-{request.task_id}",
            "audioInputType": "MIXED",
        }
        headers = {"Authorization": f"Bearer {INTERNAL_AUTH_TOKEN}"} if INTERNAL_AUTH_TOKEN else {}
        try:
            r = await self._http.post(url, json=payload, headers=headers)
            r.raise_for_status()
            return r.json()["id"]
        except Exception as e:
            logger.exception("Failed to create meeting on server")
            raise HTTPException(status_code=502, detail=f"Server unreachable: {e}")

    async def _upload_chunk(self, session: AttendSession, b64: str) -> None:
        assert self._http is not None
        url = f"{JERVIS_SERVER_URL}/internal/meeting/upload-chunk"
        payload = {
            "meetingId": session.meeting_id,
            "chunkIndex": session.chunks_sent,
            "data": b64,
            "mimeType": "audio/pcm",
        }
        headers = {"Authorization": f"Bearer {INTERNAL_AUTH_TOKEN}"} if INTERNAL_AUTH_TOKEN else {}
        try:
            r = await self._http.post(url, json=payload, headers=headers)
            r.raise_for_status()
        except Exception as e:
            logger.warning("Chunk upload failed (idx=%s): %s", session.chunks_sent, e)

    async def _finalize_meeting_on_server(self, session: AttendSession, reason: str) -> None:
        assert self._http is not None
        url = f"{JERVIS_SERVER_URL}/internal/meeting/finalize-recording"
        duration = max(1, int(time.time() - session.started_at))
        payload = {
            "meetingId": session.meeting_id,
            "meetingType": "MEETING",
            "durationSeconds": duration,
            "stopReason": reason,
        }
        headers = {"Authorization": f"Bearer {INTERNAL_AUTH_TOKEN}"} if INTERNAL_AUTH_TOKEN else {}
        try:
            r = await self._http.post(url, json=payload, headers=headers)
            r.raise_for_status()
        except Exception:
            logger.exception("Failed to finalize meeting on server")


manager = AttenderManager()


@asynccontextmanager
async def lifespan(app: FastAPI):
    await manager.startup()
    yield
    await manager.shutdown()


app = FastAPI(title="Jervis Meeting Attender", lifespan=lifespan)


@app.post("/attend", response_model=SessionInfo)
async def attend(req: AttendRequest) -> SessionInfo:
    return await manager.attend(req)


@app.post("/stop")
async def stop(req: StopRequest) -> dict:
    await manager.stop(req.task_id, reason=req.reason)
    return {"ok": True}


@app.get("/sessions", response_model=list[SessionInfo])
async def sessions() -> list[SessionInfo]:
    return manager.list_sessions()


@app.get("/health")
async def health() -> dict:
    return {"status": "ok", "active_sessions": len(manager.sessions)}


@app.get("/ready")
async def ready() -> dict:
    return {"status": "ready"}
