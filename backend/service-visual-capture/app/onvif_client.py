"""ONVIF PTZ preset control for Reolink Trackmix P760.

Uses ``onvif-zeep`` (SOAP-based ONVIF Profile S client) to control camera
pan-tilt-zoom presets. Presets are created manually on the camera first (via
Reolink app), then referenced here by token number.

All SOAP calls are blocking (zeep is synchronous), so we wrap them in
``asyncio.to_thread()`` for the async FastAPI context.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Optional

from app.config import settings

logger = logging.getLogger(__name__)

# Lazy-loaded ONVIF client (first call initializes)
_ptz_service = None
_media_service = None
_profile_token: str | None = None


def _ensure_connected():
    """Initialize the ONVIF connection (synchronous, call in thread)."""
    global _ptz_service, _media_service, _profile_token

    if _ptz_service is not None:
        return

    host = settings.visual_capture_onvif_host
    port = settings.visual_capture_onvif_port
    user = settings.visual_capture_onvif_user
    passwd = settings.visual_capture_onvif_pass

    if not host:
        raise RuntimeError("ONVIF host not configured (VISUAL_CAPTURE_ONVIF_HOST)")

    try:
        from onvif import ONVIFCamera
        camera = ONVIFCamera(host, port, user, passwd)
        _media_service = camera.create_media_service()
        _ptz_service = camera.create_ptz_service()

        # Get the first media profile (contains the PTZ config)
        profiles = _media_service.GetProfiles()
        if not profiles:
            raise RuntimeError("No media profiles found on camera")
        _profile_token = profiles[0].token
        logger.info(
            "ONVIF_CONNECT: host=%s profile=%s — connected",
            host, _profile_token,
        )
    except Exception as e:
        _ptz_service = None
        _media_service = None
        _profile_token = None
        logger.error("ONVIF_CONNECT: failed — %s", e)
        raise


def _goto_preset_sync(preset_token: int, speed: float = 1.0) -> bool:
    """Move camera to a preset position (synchronous)."""
    _ensure_connected()
    assert _ptz_service is not None and _profile_token is not None

    request = _ptz_service.create_type("GotoPreset")
    request.ProfileToken = _profile_token
    request.PresetToken = str(preset_token)
    # Speed is optional — some cameras ignore it
    try:
        _ptz_service.GotoPreset(request)
        logger.info("ONVIF_GOTO_PRESET: token=%d", preset_token)
        return True
    except Exception as e:
        logger.warning("ONVIF_GOTO_PRESET: failed token=%d — %s", preset_token, e)
        return False


def _list_presets_sync() -> list[dict]:
    """List all PTZ presets from the camera (synchronous)."""
    _ensure_connected()
    assert _ptz_service is not None and _profile_token is not None

    try:
        presets = _ptz_service.GetPresets({"ProfileToken": _profile_token})
        result = []
        for p in presets:
            result.append({
                "token": int(p.token) if hasattr(p, "token") else 0,
                "name": getattr(p, "Name", f"Preset {p.token}"),
            })
        logger.info("ONVIF_LIST_PRESETS: found %d presets", len(result))
        return result
    except Exception as e:
        logger.warning("ONVIF_LIST_PRESETS: failed — %s", e)
        return []


def _set_preset_sync(name: str) -> Optional[int]:
    """Save current camera position as a named preset (synchronous).

    Returns the preset token assigned by the camera, or None on failure.
    """
    _ensure_connected()
    assert _ptz_service is not None and _profile_token is not None

    try:
        request = _ptz_service.create_type("SetPreset")
        request.ProfileToken = _profile_token
        request.PresetName = name
        result = _ptz_service.SetPreset(request)
        token = int(result) if result else None
        logger.info("ONVIF_SET_PRESET: name=%s token=%s", name, token)
        return token
    except Exception as e:
        logger.warning("ONVIF_SET_PRESET: failed name=%s — %s", name, e)
        return None


# ── Async wrappers ───────────────────────────────────────────────────

# Named preset mapping — user-defined names to camera preset tokens.
# Populated at startup from camera presets or manually configured.
_PRESET_MAP: dict[str, int] = {}


async def load_presets() -> dict[str, int]:
    """Load presets from camera and build name→token mapping."""
    global _PRESET_MAP
    presets = await asyncio.to_thread(_list_presets_sync)
    _PRESET_MAP = {p["name"].lower(): p["token"] for p in presets}
    logger.info("ONVIF_PRESETS_LOADED: %s", _PRESET_MAP)
    return _PRESET_MAP


async def goto_preset(name: str) -> bool:
    """Move camera to a named preset. Case-insensitive lookup."""
    name_lower = name.lower()
    token = _PRESET_MAP.get(name_lower)
    if token is None:
        # Try reloading presets (user might have added one via Reolink app)
        await load_presets()
        token = _PRESET_MAP.get(name_lower)
    if token is None:
        logger.warning("ONVIF_GOTO: preset '%s' not found (available: %s)", name, list(_PRESET_MAP.keys()))
        return False
    return await asyncio.to_thread(_goto_preset_sync, token)


async def list_presets() -> list[dict]:
    """List all camera presets with name and token."""
    return await asyncio.to_thread(_list_presets_sync)


async def set_preset(name: str) -> Optional[int]:
    """Save current position as a named preset."""
    token = await asyncio.to_thread(_set_preset_sync, name)
    if token is not None:
        _PRESET_MAP[name.lower()] = token
    return token
