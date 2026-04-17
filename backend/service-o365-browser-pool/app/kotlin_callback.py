"""Callback module for notifying Kotlin server about session state changes.

When the browser session needs user attention (MFA required, session expired),
this module calls the Kotlin server over gRPC. The server then creates a
USER_TASK notification visible in the chat UI with VNC link and MFA details.
"""

from __future__ import annotations

import logging

from app.config import settings
from app.grpc_clients import server_o365_session_stub
from jervis.common import types_pb2
from jervis.server import o365_session_pb2
from jervis_contracts.interceptors import prepare_context

logger = logging.getLogger("o365-browser-pool.callback")


def _build_context() -> types_pb2.RequestContext:
    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    return ctx


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
    """Fire-and-forget session state change RPC."""
    if not settings.kotlin_server_url:
        return

    request = o365_session_pb2.SessionEventRequest(
        ctx=_build_context(),
        client_id=client_id,
        connection_id=connection_id,
        state=state,
        mfa_type=mfa_type or "",
        mfa_message=mfa_message or "",
        mfa_number=mfa_number or "",
        vnc_url=vnc_url or f"{settings.novnc_external_url}/vnc-login",
    )
    try:
        resp = await server_o365_session_stub().SessionEvent(request, timeout=10.0)
        logger.info(
            "Notified Kotlin server: %s for %s (state=%s, status=%s)",
            "SessionEvent", client_id, state, resp.status,
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
    """Push discovered capabilities to Kotlin server after tab setup."""
    if not settings.kotlin_server_url:
        return

    request = o365_session_pb2.CapabilitiesDiscoveredRequest(
        ctx=_build_context(),
        connection_id=connection_id,
        available_capabilities=available_capabilities,
    )
    try:
        resp = await server_o365_session_stub().CapabilitiesDiscovered(request, timeout=10.0)
        if resp.ok:
            logger.info(
                "Capabilities discovered for %s: %s",
                client_id, available_capabilities,
            )
        else:
            logger.warning(
                "Capabilities callback returned error for %s: %s",
                client_id, resp.error,
            )
    except Exception as e:
        logger.warning(
            "Failed to push capabilities for %s: %s",
            client_id, e,
        )


async def shutdown() -> None:
    """Placeholder kept for import-compatibility (the gRPC channel closes
    automatically when the event loop winds down)."""
    return None
