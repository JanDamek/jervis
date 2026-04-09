"""RTSP frame grabber using OpenCV.

Connects to the camera's RTSP stream and grabs single JPEG frames on demand.
Handles reconnection with exponential backoff. Drops stale buffered frames
by doing an extra grab() before the real read() — RTSP streams buffer
aggressively and without this the frames would be seconds old.
"""

from __future__ import annotations

import asyncio
import io
import logging
import time

import cv2
import numpy as np
from PIL import Image

from app.config import settings

logger = logging.getLogger(__name__)

_MAX_BACKOFF_S = 30.0


class RTSPCapture:
    """Manages a single RTSP connection with auto-reconnect."""

    def __init__(self, rtsp_url: str | None = None):
        self.rtsp_url = rtsp_url or settings.visual_capture_rtsp_url
        self._cap: cv2.VideoCapture | None = None
        self._backoff = 1.0
        self._last_connect_attempt = 0.0

    def _connect(self) -> bool:
        """Open (or re-open) the RTSP stream. Returns True on success."""
        now = time.monotonic()
        if now - self._last_connect_attempt < self._backoff:
            return False  # too soon, respect backoff
        self._last_connect_attempt = now

        if self._cap is not None:
            try:
                self._cap.release()
            except Exception:
                pass

        logger.info("RTSP_CONNECT: opening %s", self.rtsp_url[:60])
        cap = cv2.VideoCapture(self.rtsp_url, cv2.CAP_FFMPEG)
        # Reduce internal buffer to 1 frame to minimize latency
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

        if cap.isOpened():
            self._cap = cap
            self._backoff = 1.0
            logger.info("RTSP_CONNECT: success")
            return True

        logger.warning("RTSP_CONNECT: failed, backoff=%.1fs", self._backoff)
        self._backoff = min(self._backoff * 2, _MAX_BACKOFF_S)
        return False

    async def grab_frame(self) -> bytes | None:
        """Grab one fresh JPEG frame. Returns None on failure.

        Runs the blocking OpenCV calls in a thread to avoid blocking the
        asyncio event loop.
        """
        return await asyncio.to_thread(self._grab_frame_sync)

    def _grab_frame_sync(self) -> bytes | None:
        # Ensure connection
        if self._cap is None or not self._cap.isOpened():
            if not self._connect():
                return None

        assert self._cap is not None

        # Drop stale buffered frames: grab (discard) then read (keep)
        self._cap.grab()
        ok, frame = self._cap.read()
        if not ok or frame is None:
            logger.warning("RTSP_READ: failed — reconnecting")
            self._cap.release()
            self._cap = None
            self._backoff = 1.0
            return None

        # Encode to JPEG via Pillow (better quality control than cv2.imencode)
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        img = Image.fromarray(rgb)
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=settings.visual_capture_jpeg_quality)
        jpeg_bytes = buf.getvalue()
        logger.debug("RTSP_FRAME: %d bytes, shape=%s", len(jpeg_bytes), frame.shape)
        return jpeg_bytes

    def release(self) -> None:
        if self._cap is not None:
            try:
                self._cap.release()
            except Exception:
                pass
            self._cap = None
