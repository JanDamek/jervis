"""Meeting recorder — joins Teams meetings using existing browser session.

Uses the already-authenticated browser context from the browser pool to join
a Teams meeting as the logged-in user (not as guest). Captures audio via
PulseAudio virtual sink + ffmpeg, uploads chunks to Kotlin server.
"""

from __future__ import annotations

import asyncio
import base64
import logging
import time
from dataclasses import dataclass, field

import httpx
from playwright.async_api import BrowserContext, Page

from app.browser_manager import BrowserManager
from app.config import settings

logger = logging.getLogger("o365-browser-pool.meeting")

CHUNK_SECONDS = 5
SAMPLE_RATE = 16000
BYTES_PER_CHUNK = SAMPLE_RATE * 2 * CHUNK_SECONDS  # 16-bit mono PCM

JERVIS_SERVER_URL = getattr(settings, "jervis_server_url", "http://jervis-server:5500")


@dataclass
class MeetingSession:
    task_id: str
    meeting_id: str
    client_id: str
    join_url: str
    end_epoch: float
    page: Page | None = None
    ffmpeg_proc: asyncio.subprocess.Process | None = None
    pump_task: asyncio.Task | None = None
    watchdog_task: asyncio.Task | None = None
    screenshot_task: asyncio.Task | None = None
    chunks_sent: int = 0
    state: str = "STARTING"


class MeetingRecorder:
    """Records Teams meetings using existing browser pool session."""

    def __init__(self, browser_manager: BrowserManager) -> None:
        self._bm = browser_manager
        self._sessions: dict[str, MeetingSession] = {}
        self._http = httpx.AsyncClient(timeout=30)

    async def join(
        self,
        task_id: str,
        client_id: str,
        meeting_id: str,
        join_url: str,
        end_time_epoch: float,
    ) -> MeetingSession:
        if task_id in self._sessions:
            return self._sessions[task_id]

        session = MeetingSession(
            task_id=task_id,
            meeting_id=meeting_id,
            client_id=client_id,
            join_url=join_url,
            end_epoch=end_time_epoch,
        )
        self._sessions[task_id] = session

        # Notify server that recording started
        await self._notify_server(
            "/internal/meeting/start-recording",
            {"meetingId": meeting_id, "taskId": task_id},
        )

        # Open meeting in new tab using existing authenticated context
        context = self._bm.get_context(client_id)
        if not context:
            # Fallback: use any available context
            context = self._bm.get_context()

        if not context:
            session.state = "ERROR"
            logger.error("No browser context available for meeting join")
            return session

        try:
            page = await context.new_page()
            session.page = page

            # Navigate to meeting join link
            logger.info("Meeting JOIN: navigating to %s", join_url)
            await page.goto(join_url, wait_until="domcontentloaded", timeout=30000)
            await asyncio.sleep(5)

            # Try to click "Join now" / "Pripojit se" button
            await self._click_join_button(page)
            session.state = "JOINED"
            logger.info("Meeting JOINED: %s (task=%s)", meeting_id, task_id)

            # Force all Chrome audio to our capture sink
            await self._force_audio_routing()
            await asyncio.sleep(2)

            # Start audio capture — write directly to PVC for persistence
            wav_path = f"/browser-profiles/meeting-{meeting_id}.wav"
            session.ffmpeg_proc = await self._start_audio_capture(wav_path)
            if session.ffmpeg_proc:
                session.pump_task = asyncio.create_task(self._pump_audio(session))
                session.state = "RECORDING"
                logger.info("Meeting RECORDING: audio → %s", wav_path)

            # Start screenshot loop
            session.screenshot_task = asyncio.create_task(self._screenshot_loop(session))

            # Start watchdog (auto-stop at end_time)
            session.watchdog_task = asyncio.create_task(self._watchdog(session))

        except Exception as e:
            session.state = "ERROR"
            logger.error("Meeting join failed: %s", e)

        return session

    async def stop(self, task_id: str) -> None:
        session = self._sessions.pop(task_id, None)
        if not session:
            return

        logger.info("Meeting STOP: %s (chunks=%d)", session.meeting_id, session.chunks_sent)
        session.state = "STOPPING"

        # Cancel tasks
        for task in [session.pump_task, session.watchdog_task, session.screenshot_task]:
            if task:
                task.cancel()
                try:
                    await task
                except (asyncio.CancelledError, Exception):
                    pass

        # Kill ffmpeg
        if session.ffmpeg_proc:
            try:
                session.ffmpeg_proc.kill()
                await session.ffmpeg_proc.wait()
            except Exception:
                pass

        # Close meeting tab
        if session.page and not session.page.is_closed():
            try:
                await session.page.close()
            except Exception:
                pass

        # Notify server
        duration = max(0, int(time.time() - (session.end_epoch - 3 * 3600)))
        await self._notify_server(
            "/internal/meeting/finalize-recording",
            {
                "meetingId": session.meeting_id,
                "taskId": session.task_id,
                "durationSeconds": session.chunks_sent * CHUNK_SECONDS,
            },
        )
        session.state = "DONE"

    async def start_adhoc(
        self,
        *,
        client_id: str,
        page: Page,
        title: str | None = None,
    ) -> MeetingSession | None:
        """Attach recording to an in-progress meeting the user started manually.

        Unlike `join()`, this does NOT navigate or click — the user is already
        in the meeting via VNC. We just allocate a MeetingDocument server-side,
        grab audio from the existing PulseAudio sink, and stream screenshots
        of the current page.

        Returns the session, or None if a server-side meeting could not be
        allocated. Deduplicates per client_id — if an ad-hoc session is already
        running for this client, the existing one is returned.
        """
        existing = next(
            (s for s in self._sessions.values()
             if s.client_id == client_id and s.task_id.startswith("adhoc-")),
            None,
        )
        if existing is not None:
            return existing

        # Create MeetingDocument server-side — taskId omitted = ad-hoc recording
        try:
            resp = await self._http.post(
                f"{JERVIS_SERVER_URL}/internal/meeting/start-recording",
                json={
                    "clientId": client_id,
                    "title": title or "Ad-hoc meeting",
                    "meetingType": "MEETING",
                },
            )
            resp.raise_for_status()
            meeting = resp.json()
            meeting_id = meeting.get("id")
            if not meeting_id:
                logger.error("Ad-hoc meeting start: server returned no id")
                return None
        except Exception as e:
            logger.error("Ad-hoc meeting start failed: %s", e)
            return None

        task_id = f"adhoc-{meeting_id}"
        # End-epoch set to +4h guard; watchdog stops when meeting_stage disappears
        # (see stop_adhoc_for_client) or at the hard deadline.
        end_epoch = time.time() + 4 * 3600
        session = MeetingSession(
            task_id=task_id,
            meeting_id=meeting_id,
            client_id=client_id,
            join_url="",
            end_epoch=end_epoch,
            page=page,
        )
        self._sessions[task_id] = session

        try:
            await self._force_audio_routing()
            await asyncio.sleep(1)

            wav_path = f"/browser-profiles/meeting-{meeting_id}.wav"
            session.ffmpeg_proc = await self._start_audio_capture(wav_path)
            if session.ffmpeg_proc:
                session.pump_task = asyncio.create_task(self._pump_audio(session))
                session.state = "RECORDING"
                logger.info(
                    "AD-HOC meeting recording started: meeting=%s client=%s wav=%s",
                    meeting_id, client_id, wav_path,
                )

            session.screenshot_task = asyncio.create_task(self._screenshot_loop(session))
            session.watchdog_task = asyncio.create_task(self._watchdog(session))
        except Exception as e:
            session.state = "ERROR"
            logger.error("Ad-hoc recording setup failed: %s", e)

        return session

    async def stop_adhoc_for_client(self, client_id: str) -> str | None:
        """Stop any in-progress ad-hoc recording for the given client.

        Called by the agent when `meeting_stage` becomes false. Returns the
        stopped task_id, or None if nothing was running.
        """
        for s in list(self._sessions.values()):
            if s.client_id == client_id and s.task_id.startswith("adhoc-"):
                await self.stop(s.task_id)
                return s.task_id
        return None

    def has_adhoc_session(self, client_id: str) -> bool:
        return any(
            s.client_id == client_id and s.task_id.startswith("adhoc-")
            for s in self._sessions.values()
        )

    def get_sessions(self) -> list[dict]:
        return [
            {
                "task_id": s.task_id,
                "meeting_id": s.meeting_id,
                "state": s.state,
                "chunks_sent": s.chunks_sent,
            }
            for s in self._sessions.values()
        ]

    async def _click_join_button(self, page: Page) -> None:
        """Try to find and click the Join/Pripojit button."""
        join_selectors = [
            'button[data-tid="prejoin-join-button"]',
            'button:has-text("Join now")',
            'button:has-text("Pripojit se")',
            'button:has-text("Pripojit")',
            'button:has-text("Join")',
            '#prejoin-join-button',
        ]
        for attempt in range(6):
            for selector in join_selectors:
                try:
                    btn = page.locator(selector).first
                    if await btn.is_visible(timeout=2000):
                        # Mute mic before joining
                        try:
                            mic_btn = page.locator(
                                'button[data-tid="toggle-mute"], '
                                'button[aria-label*="Mic"], '
                                'button[aria-label*="Mikrofon"]'
                            ).first
                            if await mic_btn.is_visible(timeout=1000):
                                await mic_btn.click()
                                logger.info("Meeting: muted microphone")
                        except Exception:
                            pass

                        # Disable camera
                        try:
                            cam_btn = page.locator(
                                'button[data-tid="toggle-video"], '
                                'button[aria-label*="Camera"], '
                                'button[aria-label*="Kamera"]'
                            ).first
                            if await cam_btn.is_visible(timeout=1000):
                                await cam_btn.click()
                                logger.info("Meeting: disabled camera")
                        except Exception:
                            pass

                        await btn.click()
                        logger.info("Meeting: clicked join button (%s)", selector)
                        await asyncio.sleep(5)
                        return
                except Exception:
                    continue
            logger.debug("Meeting: join button not found, attempt %d/6", attempt + 1)
            await asyncio.sleep(3)

        logger.warning("Meeting: could not find join button, may be in lobby")

    async def _force_audio_routing(self) -> None:
        """Move all PulseAudio sink-inputs to jervis_audio capture sink."""
        try:
            proc = await asyncio.create_subprocess_shell(
                "for idx in $(pactl list sink-inputs short | awk '{print $1}'); do "
                "pactl move-sink-input $idx jervis_audio 2>/dev/null; done",
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
            )
            await proc.wait()
            logger.info("Audio routing: forced all sink-inputs to jervis_audio")
        except Exception as e:
            logger.warning("Audio routing failed: %s", e)

    async def _start_audio_capture(self, wav_path: str | None = None) -> asyncio.subprocess.Process | None:
        """Start ffmpeg capturing from PulseAudio virtual sink.

        If wav_path is given, writes WAV to file (persistent) AND pipes raw PCM
        to stdout for chunk upload. If not, only pipes to stdout.
        """
        try:
            if wav_path:
                # Tee: write WAV to file + pipe raw PCM for chunk upload
                proc = await asyncio.create_subprocess_exec(
                    "ffmpeg", "-y",
                    "-f", "pulse",
                    "-i", "jervis_audio.monitor",
                    "-ac", "1", "-ar", str(SAMPLE_RATE),
                    wav_path,
                    "-ac", "1", "-ar", str(SAMPLE_RATE),
                    "-f", "s16le", "pipe:1",
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.DEVNULL,
                )
            else:
                proc = await asyncio.create_subprocess_exec(
                    "ffmpeg", "-y",
                    "-f", "pulse",
                    "-i", "jervis_audio.monitor",
                    "-ac", "1", "-ar", str(SAMPLE_RATE),
                    "-f", "s16le", "pipe:1",
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.DEVNULL,
                )
            logger.info("ffmpeg audio capture started (PID=%d, file=%s)", proc.pid, wav_path or "pipe-only")
            return proc
        except Exception as e:
            logger.error("Failed to start ffmpeg audio capture: %s", e)
            return None

    async def _pump_audio(self, session: MeetingSession) -> None:
        """Read audio chunks from ffmpeg and upload to server."""
        try:
            while session.ffmpeg_proc and session.ffmpeg_proc.stdout:
                data = await session.ffmpeg_proc.stdout.readexactly(BYTES_PER_CHUNK)
                b64 = base64.b64encode(data).decode()
                await self._notify_server(
                    "/internal/meeting/upload-chunk",
                    {
                        "meetingId": session.meeting_id,
                        "chunkIndex": session.chunks_sent,
                        "data": b64,
                        "mimeType": "audio/pcm",
                    },
                )
                session.chunks_sent += 1
                if session.chunks_sent % 12 == 0:  # Log every minute
                    logger.info(
                        "Meeting audio: %d chunks (%d min) for %s",
                        session.chunks_sent,
                        session.chunks_sent * CHUNK_SECONDS // 60,
                        session.meeting_id,
                    )
        except asyncio.IncompleteReadError:
            logger.info("Meeting audio stream ended")
        except asyncio.CancelledError:
            raise
        except Exception as e:
            logger.error("Audio pump error: %s", e)

    async def _screenshot_loop(self, session: MeetingSession) -> None:
        """Take periodic screenshots of the meeting."""
        interval = max(5, getattr(settings, "meeting_screenshot_interval", 30))
        try:
            while True:
                await asyncio.sleep(interval)
                if session.page and not session.page.is_closed():
                    try:
                        screenshot = await session.page.screenshot(type="jpeg", quality=70)
                        b64 = base64.b64encode(screenshot).decode()
                        await self._notify_server(
                            "/internal/meeting/upload-screenshot",
                            {
                                "meetingId": session.meeting_id,
                                "data": b64,
                                "mimeType": "image/jpeg",
                                "timestamp": int(time.time()),
                            },
                        )
                        logger.debug("Meeting screenshot uploaded for %s", session.meeting_id)
                    except Exception as e:
                        logger.debug("Screenshot failed: %s", e)
        except asyncio.CancelledError:
            raise

    async def _watchdog(self, session: MeetingSession) -> None:
        """Auto-stop when meeting end time is reached."""
        try:
            while True:
                await asyncio.sleep(30)
                if time.time() >= session.end_epoch:
                    logger.info("Meeting end time reached for %s", session.meeting_id)
                    await self.stop(session.task_id)
                    return
        except asyncio.CancelledError:
            raise

    async def _notify_server(self, path: str, payload: dict) -> None:
        try:
            resp = await self._http.post(f"{JERVIS_SERVER_URL}{path}", json=payload)
            if resp.status_code >= 400:
                logger.warning("Server %s returned %d: %s", path, resp.status_code, resp.text[:200])
        except Exception as e:
            logger.warning("Server notify failed %s: %s", path, e)
