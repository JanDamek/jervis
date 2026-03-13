"""Callback module for notifying Kotlin server about session state changes.

When the browser session needs user attention (MFA required, session expired),
this module POSTs to the Kotlin server's internal API. The server then creates
a USER_TASK notification visible in the chat UI with VNC link and MFA details.
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger("o365-browser-pool.callback")

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
    mfa_type: str | None = None,
    mfa_message: str | None = None,
    mfa_number: str | None = None,
    vnc_url: str | None = None,
) -> None:
    """POST session state change to Kotlin server. Fire-and-forget."""
    if not settings.kotlin_server_url:
        return

    url = f"{settings.kotlin_server_url}/internal/o365/session-event"
    payload = {
        "clientId": client_id,
        "connectionId": connection_id,
        "state": state,
        "mfaType": mfa_type,
        "mfaMessage": mfa_message,
        "mfaNumber": mfa_number,
        "vncUrl": vnc_url or f"{settings.novnc_external_url}/vnc-login",
    }

    try:
        client = await _get_client()
        resp = await client.post(url, json=payload)
        if resp.status_code < 300:
            logger.info(
                "Notified Kotlin server: %s for %s (state=%s)",
                url, client_id, state,
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


async def shutdown() -> None:
    """Close the HTTP client on service shutdown."""
    global _client
    if _client and not _client.is_closed:
        await _client.aclose()
        _client = None
