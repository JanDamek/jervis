"""Callback module for notifying Kotlin server about WhatsApp session state changes.

When the WhatsApp session needs user attention (QR code scan, session expired),
this module POSTs to the Kotlin server's internal API.
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger("whatsapp-browser.callback")

_client: httpx.AsyncClient | None = None


async def _get_client() -> httpx.AsyncClient:
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(timeout=10.0)
    return _client


async def notify_session_state(
    client_id: str,
    connection_id: str,
    state: str,
    *,
    vnc_url: str | None = None,
) -> None:
    """POST session state change to Kotlin server. Fire-and-forget."""
    if not settings.kotlin_server_url:
        return

    url = f"{settings.kotlin_server_url}/internal/whatsapp/session-event"
    payload = {
        "clientId": client_id,
        "connectionId": connection_id,
        "state": state,
        "vncUrl": vnc_url or f"{settings.novnc_external_url}/vnc-login",
    }

    try:
        client = await _get_client()
        resp = await client.post(url, json=payload)
        if resp.status_code < 300:
            logger.info(
                "Notified Kotlin server: state=%s for %s",
                state, client_id,
            )
        else:
            logger.warning(
                "Kotlin callback returned %d for %s: %s",
                resp.status_code, client_id, resp.text[:200],
            )
    except Exception as e:
        logger.warning(
            "Failed to notify Kotlin server for %s: %s",
            client_id, e,
        )


async def notify_capabilities_discovered(
    client_id: str,
    connection_id: str,
    available_capabilities: list[str],
) -> None:
    """Push discovered capabilities to Kotlin server.

    For WhatsApp, capabilities are always CHAT_READ (Phase 1).
    """
    if not settings.kotlin_server_url:
        return

    url = f"{settings.kotlin_server_url}/internal/whatsapp/capabilities-discovered"
    payload = {
        "clientId": client_id,
        "connectionId": connection_id,
        "availableCapabilities": available_capabilities,
    }

    try:
        client = await _get_client()
        resp = await client.post(url, json=payload)
        if resp.status_code < 300:
            logger.info(
                "Capabilities discovered for %s: %s",
                client_id, available_capabilities,
            )
        else:
            logger.warning(
                "Capabilities callback returned %d for %s: %s",
                resp.status_code, client_id, resp.text[:200],
            )
    except Exception as e:
        logger.warning(
            "Failed to push capabilities for %s: %s",
            client_id, e,
        )


async def shutdown() -> None:
    """Close the HTTP client on service shutdown."""
    global _client
    if _client and not _client.is_closed:
        await _client.aclose()
        _client = None
