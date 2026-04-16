"""Meeting recorder — WebM chunk pipeline with disk-backed upload queue.

Product §10a: records Teams meetings happening inside the pod's Chromium
(via VNC display :99). ffmpeg muxes a VP9 video stream (x11grab at
`O365_POOL_MEETING_FPS`) + an Opus audio stream (PulseAudio
`jervis_audio.monitor`) into a WebM segment stream, 10-second chunks
written to `/browser-profiles/meeting-chunks/<meeting_id>/`.

An async upload loop posts each chunk to
`POST /internal/meeting/{id}/video-chunk?chunkIndex=<N>` with indefinite
retry (3 s poll, 2 s failure delay). On
`stop_meeting_recording`/`leave_meeting` the pipeline flushes the queue
and calls `POST /internal/meeting/{id}/finalize`.

No "join" tool here — scheduled meetings join via `/instruction/`
(server → agent → tools), ad-hoc meetings are attached when the user
clicks Join via VNC (agent detects `meeting_stage` rising).
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from dataclasses import dataclass, field
from pathlib import Path

import httpx
from playwright.async_api import Page

from app.browser_manager import BrowserManager
from app.config import settings

logger = logging.getLogger("o365-browser-pool.meeting")


JERVIS_SERVER_URL = getattr(settings, "kotlin_server_url", "http://jervis-server:5500")


@dataclass
class MeetingSession:
    task_id: str              # "adhoc-<meeting_id>" or server-supplied
    meeting_id: str           # MeetingDocument._id
    client_id: str
    connection_id: str
    chunk_dir: Path
    page: Page | None = None
    ffmpeg_proc: asyncio.subprocess.Process | None = None
    upload_task: asyncio.Task | None = None
    state: str = "STARTING"   # STARTING | RECORDING | FINALIZING | DONE | ERROR
    joined_by: str = "agent"  # "user" | "agent"
    title: str | None = None
    started_at: float = field(default_factory=time.time)
    chunks_uploaded: int = 0
    last_chunk_uploaded_index: int = -1
    last_ack_at: float | None = None


class MeetingRecorder:
    """Manages at most one active recording per client (1 pod = 1 client)."""

    def __init__(self, browser_manager: BrowserManager) -> None:
        self._bm = browser_manager
        self._sessions: dict[str, MeetingSession] = {}       # task_id → session
        self._http = httpx.AsyncClient(timeout=30)

    # ---- Public API ------------------------------------------------------

    async def start_adhoc(
        self,
        *,
        client_id: str,
        page: Page,
        title: str | None = None,
        joined_by: str = "user",
        meeting_id: str | None = None,
        connection_id: str | None = None,
    ) -> MeetingSession | None:
        """Start (or reattach) a recording.

        When `meeting_id` is None, a new MeetingDocument is allocated
        server-side. `joined_by` distinguishes user-joined (VNC) from
        agent-joined (/instruction/) so the server + alone-check logic can
        branch appropriately.
        """
        existing = self._active_for_client(client_id)
        if existing is not None:
            logger.info("MEETING: reusing active session %s", existing.meeting_id)
            return existing

        if meeting_id is None:
            allocated = await self._allocate_meeting(
                client_id=client_id, title=title, joined_by=joined_by,
            )
            if allocated is None:
                return None
            meeting_id = allocated

        task_id = f"adhoc-{meeting_id}"
        chunk_dir = Path(settings.meeting_chunk_dir) / meeting_id
        chunk_dir.mkdir(parents=True, exist_ok=True)

        session = MeetingSession(
            task_id=task_id,
            meeting_id=meeting_id,
            client_id=client_id,
            connection_id=connection_id or client_id,
            chunk_dir=chunk_dir,
            page=page,
            joined_by=joined_by,
            title=title,
        )
        self._sessions[task_id] = session

        try:
            await self._force_audio_routing()
            await asyncio.sleep(0.5)
            session.ffmpeg_proc = await self._start_ffmpeg(session)
            if session.ffmpeg_proc is None:
                session.state = "ERROR"
                return session
            session.state = "RECORDING"
            session.upload_task = asyncio.create_task(self._upload_loop(session))
            logger.info(
                "MEETING_RECORDING_STARTED: meeting=%s client=%s joined_by=%s dir=%s",
                meeting_id, client_id, joined_by, chunk_dir,
            )
        except Exception as e:
            session.state = "ERROR"
            logger.error("Meeting start failed: %s", e)

        return session

    async def stop_adhoc_for_client(self, client_id: str) -> str | None:
        session = self._active_for_client(client_id)
        if session is None:
            return None
        await self._stop_session(session)
        return session.task_id

    async def stop_by_meeting_id(self, meeting_id: str) -> str | None:
        for s in list(self._sessions.values()):
            if s.meeting_id == meeting_id:
                await self._stop_session(s)
                return s.task_id
        return None

    def has_adhoc_session(self, client_id: str) -> bool:
        return self._active_for_client(client_id) is not None

    def get_sessions(self) -> list[dict]:
        return [
            {
                "task_id": s.task_id,
                "meeting_id": s.meeting_id,
                "state": s.state,
                "chunks_uploaded": s.chunks_uploaded,
                "joined_by": s.joined_by,
            }
            for s in self._sessions.values()
        ]

    # ---- Internal --------------------------------------------------------

    def _active_for_client(self, client_id: str) -> MeetingSession | None:
        return next(
            (s for s in self._sessions.values()
             if s.client_id == client_id and s.state in ("STARTING", "RECORDING")),
            None,
        )

    async def _allocate_meeting(
        self, *, client_id: str, title: str | None, joined_by: str,
    ) -> str | None:
        try:
            resp = await self._http.post(
                f"{JERVIS_SERVER_URL}/internal/meeting/start-recording",
                json={
                    "clientId": client_id,
                    "title": title or "Ad-hoc meeting",
                    "meetingType": "MEETING",
                    "joinedBy": joined_by,
                },
            )
            resp.raise_for_status()
            meeting_id = resp.json().get("id")
            if not meeting_id:
                logger.error("Ad-hoc meeting start: server returned no id")
                return None
            return meeting_id
        except Exception as e:
            logger.error("Ad-hoc meeting start failed: %s", e)
            return None

    async def _force_audio_routing(self) -> None:
        """Move all PulseAudio sink-inputs to jervis_audio capture sink so
        the meeting audio is captured by our ffmpeg pipeline."""
        try:
            proc = await asyncio.create_subprocess_shell(
                "for idx in $(pactl list sink-inputs short | awk '{print $1}'); do "
                "pactl move-sink-input $idx jervis_audio 2>/dev/null; done",
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
            )
            await proc.wait()
        except Exception as e:
            logger.warning("Audio routing failed: %s", e)

    async def _start_ffmpeg(self, session: MeetingSession) -> asyncio.subprocess.Process | None:
        """Start the ffmpeg WebM segment pipeline.

        Video: x11grab from :99 at O365_POOL_MEETING_FPS → VP9 realtime.
        Audio: PulseAudio jervis_audio.monitor → Opus.
        Container: webm segment, `chunk_seconds` seconds per file.
        Output pattern: <dir>/chunk_%06d.webm — a filesystem watcher picks
        them up, the upload loop ships them in order.
        """
        fps = max(1, int(settings.meeting_fps))
        chunk_s = max(5, int(settings.meeting_chunk_seconds))
        display = os.environ.get("DISPLAY", ":99")
        output = str(session.chunk_dir / "chunk_%06d.webm")

        cmd = [
            "ffmpeg", "-y", "-loglevel", "warning",
            # Video
            "-f", "x11grab", "-framerate", str(fps), "-i", display,
            # Audio
            "-f", "pulse", "-i", "jervis_audio.monitor",
            # Encoders
            "-c:v", "libvpx-vp9", "-b:v", "600k",
            "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1",
            "-c:a", "libopus", "-b:a", "64k",
            # Segment muxer
            "-f", "segment", "-segment_format", "webm",
            "-segment_time", str(chunk_s), "-reset_timestamps", "1",
            output,
        ]
        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.PIPE,
            )
            logger.info(
                "MEETING_FFMPEG_STARTED: pid=%d dir=%s fps=%d chunk=%ds",
                proc.pid, session.chunk_dir, fps, chunk_s,
            )
            # Lightweight stderr drain so ffmpeg never blocks on a full pipe.
            asyncio.create_task(self._drain_stderr(proc, session.meeting_id))
            return proc
        except Exception as e:
            logger.error("ffmpeg start failed: %s", e)
            return None

    async def _drain_stderr(
        self, proc: asyncio.subprocess.Process, meeting_id: str,
    ) -> None:
        if proc.stderr is None:
            return
        while True:
            line = await proc.stderr.readline()
            if not line:
                break
            text = line.decode(errors="ignore").rstrip()
            if text:
                logger.debug("ffmpeg[%s] %s", meeting_id[:8], text)

    async def _upload_loop(self, session: MeetingSession) -> None:
        """Poll the chunk dir for completed segments, post each in order.

        We only upload the files whose index is strictly less than the
        newest one ffmpeg is currently writing — otherwise we could grab a
        partial WebM. The max committed index is `(max_on_disk - 1)`.
        """
        poll_s = max(1, int(settings.meeting_upload_poll_seconds))
        retry_s = max(1, int(settings.meeting_upload_retry_seconds))
        next_index = 0
        try:
            while session.state in ("RECORDING", "FINALIZING"):
                disk_max = self._max_chunk_index(session.chunk_dir)
                # Only upload indexes strictly below disk_max (finished files).
                upload_through = (
                    disk_max if session.state == "FINALIZING" else disk_max - 1
                )
                while next_index <= upload_through:
                    chunk_path = session.chunk_dir / f"chunk_{next_index:06d}.webm"
                    if not chunk_path.exists():
                        next_index += 1
                        continue
                    uploaded = await self._upload_chunk(session, chunk_path, next_index)
                    if not uploaded:
                        await asyncio.sleep(retry_s)
                        break  # retry same chunk next tick
                    # Free disk space after successful upload
                    try:
                        chunk_path.unlink()
                    except OSError:
                        pass
                    session.chunks_uploaded += 1
                    session.last_chunk_uploaded_index = next_index
                    session.last_ack_at = time.time()
                    next_index += 1
                await asyncio.sleep(poll_s)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("Upload loop crashed for meeting %s", session.meeting_id)

    async def _upload_chunk(
        self, session: MeetingSession, path: Path, index: int,
    ) -> bool:
        url = f"{JERVIS_SERVER_URL}/internal/meeting/{session.meeting_id}/video-chunk"
        try:
            data = path.read_bytes()
        except OSError as e:
            logger.warning("chunk read failed path=%s: %s", path, e)
            return False
        try:
            resp = await self._http.post(
                url,
                params={"chunkIndex": str(index)},
                content=data,
                headers={"Content-Type": "video/webm"},
            )
            if resp.status_code < 300:
                return True
            logger.warning(
                "chunk upload HTTP %d meeting=%s idx=%d body=%s",
                resp.status_code, session.meeting_id, index, resp.text[:200],
            )
            return False
        except Exception as e:
            logger.debug("chunk upload exception meeting=%s idx=%d: %s",
                         session.meeting_id, index, e)
            return False

    def _max_chunk_index(self, chunk_dir: Path) -> int:
        max_idx = -1
        try:
            for p in chunk_dir.iterdir():
                if not p.is_file() or not p.name.startswith("chunk_"):
                    continue
                try:
                    idx = int(p.stem.split("_")[1])
                except (IndexError, ValueError):
                    continue
                if idx > max_idx:
                    max_idx = idx
        except OSError:
            pass
        return max_idx

    async def _stop_session(self, session: MeetingSession) -> None:
        if session.state in ("FINALIZING", "DONE"):
            return
        session.state = "FINALIZING"
        logger.info(
            "MEETING_STOP: meeting=%s uploaded=%d",
            session.meeting_id, session.chunks_uploaded,
        )

        # 1. Terminate ffmpeg so it flushes the last segment.
        if session.ffmpeg_proc is not None:
            try:
                session.ffmpeg_proc.terminate()
                await asyncio.wait_for(session.ffmpeg_proc.wait(), timeout=10)
            except asyncio.TimeoutError:
                session.ffmpeg_proc.kill()
                await session.ffmpeg_proc.wait()
            except Exception:
                pass

        # 2. Let upload loop drain remaining chunks (up to ~60s).
        drain_deadline = time.time() + 60
        while time.time() < drain_deadline:
            pending = self._count_pending(session.chunk_dir)
            if pending == 0:
                break
            await asyncio.sleep(2)

        # 3. Cancel upload loop.
        if session.upload_task is not None:
            session.upload_task.cancel()
            try:
                await session.upload_task
            except (asyncio.CancelledError, Exception):
                pass

        # 4. Post finalize.
        try:
            resp = await self._http.post(
                f"{JERVIS_SERVER_URL}/internal/meeting/{session.meeting_id}/finalize",
                json={
                    "chunksUploaded": session.chunks_uploaded,
                    "joinedBy": session.joined_by,
                },
            )
            if resp.status_code >= 400:
                logger.warning(
                    "finalize HTTP %d meeting=%s body=%s",
                    resp.status_code, session.meeting_id, resp.text[:200],
                )
        except Exception as e:
            logger.warning("finalize failed meeting=%s: %s", session.meeting_id, e)

        session.state = "DONE"
        self._sessions.pop(session.task_id, None)

    def _count_pending(self, chunk_dir: Path) -> int:
        try:
            return sum(
                1 for p in chunk_dir.iterdir()
                if p.is_file() and p.name.startswith("chunk_") and p.suffix == ".webm"
            )
        except OSError:
            return 0
