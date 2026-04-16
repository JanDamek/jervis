"""
Jervis Visual Capture — IP camera frame capture + VLM analysis for real-time
meeting hints and on-demand desk/screen reading.

Connects to a Reolink Trackmix P760 (or any RTSP+ONVIF camera) on the LAN,
grabs periodic JPEG frames, analyzes them with a Vision Language Model, and
POSTs results to the Kotlin server's internal API. Results flow through the
existing MeetingHelper event stream to all connected clients.

## Endpoints

* ``POST /capture/start``  — start continuous capture loop (optional meeting_id)
* ``POST /capture/stop``   — stop capture loop
* ``POST /capture/snapshot`` — grab + analyze single frame (on-demand)
* ``POST /ptz/goto``       — move camera to ONVIF preset
* ``POST /ptz/presets``    — list available presets
* ``POST /ptz/set``        — save current position as preset
* ``GET  /health``         — k8s liveness
* ``GET  /ready``          — k8s readiness

## Design

Stateless — no MongoDB, no persistent state. All results are POSTed to the
Kotlin server which handles KB storage and event broadcasting. If the pod
crashes, re-issue ``/capture/start`` to resume.
"""

from __future__ import annotations

import asyncio
import base64
import logging
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from app.config import settings
from app.capture import RTSPCapture
from app.vlm_client import analyze_frame, AnalysisMode
from app import onvif_client

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("visual-capture")


# ── Models ────────────────────────────────────────────────────────────


class CaptureStartRequest(BaseModel):
    meeting_id: Optional[str] = None
    mode: AnalysisMode = "scene"
    interval_s: Optional[int] = None  # override default


class CaptureStopRequest(BaseModel):
    pass


class SnapshotRequest(BaseModel):
    mode: AnalysisMode = "scene"
    preset: Optional[str] = None  # move to preset before capturing
    custom_prompt: Optional[str] = None


class PTZRequest(BaseModel):
    preset: str


class PTZSetRequest(BaseModel):
    name: str


# ── Capture state ─────────────────────────────────────────────────────


class CaptureState:
    def __init__(self):
        self.running = False
        self.meeting_id: Optional[str] = None
        self.mode: AnalysisMode = "scene"
        self.interval_s: int = settings.visual_capture_interval_s
        self.task: Optional[asyncio.Task] = None
        self.frames_captured: int = 0
        self.last_capture_at: Optional[float] = None
        self.current_preset: str = "default"


state = CaptureState()
rtsp = RTSPCapture()
_http: Optional[httpx.AsyncClient] = None


# ── Capture loop ──────────────────────────────────────────────────────


async def _capture_loop():
    """Background loop: grab frame → VLM → POST to server. Runs until stopped."""
    logger.info(
        "CAPTURE_LOOP: started (meeting=%s, mode=%s, interval=%ds)",
        state.meeting_id, state.mode, state.interval_s,
    )
    while state.running:
        loop_start = time.monotonic()
        try:
            frame = await rtsp.grab_frame()
            if frame is None:
                logger.warning("CAPTURE_LOOP: frame grab failed — waiting before retry")
                await asyncio.sleep(state.interval_s)
                continue

            # VLM analysis
            result = await analyze_frame(frame, mode=state.mode)
            state.frames_captured += 1
            state.last_capture_at = time.time()

            # POST to Kotlin server
            await _post_result(result, state.meeting_id, state.current_preset)

        except asyncio.CancelledError:
            break
        except Exception as e:
            logger.error("CAPTURE_LOOP: error — %s", e, exc_info=True)

        # Wait for the remaining interval (subtract VLM processing time)
        elapsed = time.monotonic() - loop_start
        sleep_time = max(0.5, state.interval_s - elapsed)
        try:
            await asyncio.sleep(sleep_time)
        except asyncio.CancelledError:
            break

    logger.info("CAPTURE_LOOP: stopped (frames=%d)", state.frames_captured)


async def _post_result(
    result: dict,
    meeting_id: Optional[str],
    preset_name: str,
):
    """Post VLM result to Kotlin server via gRPC."""
    from app.grpc_clients import server_visual_capture_stub
    from jervis.server import visual_capture_pb2
    from jervis.common import types_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        await server_visual_capture_stub().PostResult(
            visual_capture_pb2.VisualResultRequest(
                ctx=ctx,
                meeting_id=meeting_id or "",
                type=result.get("mode", "scene"),
                description=result.get("description", "") or "",
                ocr_text=result.get("ocr_text", "") or "",
                preset_name=preset_name or "",
                timestamp_iso=datetime.now(timezone.utc).isoformat(),
                model=result.get("model", "") or "",
            ),
            timeout=10.0,
        )
    except Exception as e:
        logger.warning("POST_RESULT: failed — %s", e)


# ── Lifespan ──────────────────────────────────────────────────────────


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: load ONVIF presets (best-effort, camera may not be ready)
    try:
        if settings.visual_capture_onvif_host:
            await onvif_client.load_presets()
    except Exception as e:
        logger.warning("ONVIF startup failed (will retry on first PTZ call): %s", e)
    yield
    # Shutdown: stop capture + release RTSP
    state.running = False
    if state.task and not state.task.done():
        state.task.cancel()
        try:
            await state.task
        except asyncio.CancelledError:
            pass
    rtsp.release()
    global _http
    if _http is not None:
        await _http.aclose()


app = FastAPI(title="Jervis Visual Capture", lifespan=lifespan)


# ── Endpoints ─────────────────────────────────────────────────────────


@app.post("/capture/start")
async def capture_start(req: CaptureStartRequest) -> dict:
    """Start continuous capture loop."""
    if state.running:
        raise HTTPException(status_code=409, detail="Already running")
    if not settings.visual_capture_rtsp_url:
        raise HTTPException(status_code=400, detail="VISUAL_CAPTURE_RTSP_URL not configured")

    state.running = True
    state.meeting_id = req.meeting_id
    state.mode = req.mode
    state.interval_s = req.interval_s or settings.visual_capture_interval_s
    state.frames_captured = 0
    state.task = asyncio.create_task(_capture_loop())

    return {
        "status": "started",
        "meeting_id": state.meeting_id,
        "mode": state.mode,
        "interval_s": state.interval_s,
    }


@app.post("/capture/stop")
async def capture_stop(req: CaptureStopRequest = CaptureStopRequest()) -> dict:
    """Stop continuous capture loop."""
    if not state.running:
        return {"status": "already_stopped"}
    state.running = False
    if state.task and not state.task.done():
        state.task.cancel()
        try:
            await state.task
        except asyncio.CancelledError:
            pass
    return {"status": "stopped", "frames_captured": state.frames_captured}


@app.post("/capture/snapshot")
async def capture_snapshot(req: SnapshotRequest = SnapshotRequest()) -> dict:
    """Grab a single frame, analyze with VLM, return result immediately."""
    if not settings.visual_capture_rtsp_url:
        raise HTTPException(status_code=400, detail="VISUAL_CAPTURE_RTSP_URL not configured")

    # Optional: move to preset first
    if req.preset:
        moved = await onvif_client.goto_preset(req.preset)
        if moved:
            await asyncio.sleep(2)  # wait for camera to settle
            state.current_preset = req.preset

    frame = await rtsp.grab_frame()
    if frame is None:
        raise HTTPException(status_code=502, detail="Failed to grab frame from RTSP")

    result = await analyze_frame(frame, mode=req.mode, custom_prompt=req.custom_prompt)
    result["frame_size_bytes"] = len(frame)
    result["timestamp"] = datetime.now(timezone.utc).isoformat()
    result["preset"] = state.current_preset

    # Also POST to server for KB storage (fire-and-forget)
    asyncio.create_task(_post_result(result, None, state.current_preset))

    return result


@app.post("/ptz/goto")
async def ptz_goto(req: PTZRequest) -> dict:
    """Move camera to a named preset."""
    ok = await onvif_client.goto_preset(req.preset)
    if ok:
        state.current_preset = req.preset
        return {"status": "ok", "preset": req.preset}
    raise HTTPException(status_code=404, detail=f"Preset '{req.preset}' not found or camera unreachable")


@app.post("/ptz/presets")
async def ptz_presets() -> dict:
    """List available camera presets."""
    presets = await onvif_client.list_presets()
    return {"presets": presets}


@app.post("/ptz/set")
async def ptz_set(req: PTZSetRequest) -> dict:
    """Save current camera position as a named preset."""
    token = await onvif_client.set_preset(req.name)
    if token is not None:
        return {"status": "ok", "name": req.name, "token": token}
    raise HTTPException(status_code=500, detail="Failed to set preset")


@app.get("/health")
async def health() -> dict:
    return {
        "status": "ok",
        "capture_running": state.running,
        "frames_captured": state.frames_captured,
    }


@app.get("/ready")
async def ready() -> dict:
    return {"status": "ready", "rtsp_configured": bool(settings.visual_capture_rtsp_url)}
