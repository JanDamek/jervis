"""Callback module for notifying Kotlin server about WhatsApp session state changes.

When the WhatsApp session needs user attention (QR code scan, session expired),
this module calls the Kotlin server's gRPC surface.
"""

from __future__ import annotations

import logging

from app.config import settings
from app.grpc_clients import server_whatsapp_session_stub
from jervis.server import whatsapp_session_pb2
from jervis.common import types_pb2
from jervis_contracts.interceptors import prepare_context

logger = logging.getLogger("whatsapp-browser.callback")


def _ctx() -> types_pb2.RequestContext:
    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    return ctx


async def notify_session_state(
    client_id: str,
    connection_id: str,
    state: str,
    *,
    vnc_url: str | None = None,
) -> None:
    """Push session state change to Kotlin server. Fire-and-forget."""
    if not settings.kotlin_server_url:
        return
    try:
        await server_whatsapp_session_stub().SessionEvent(
            whatsapp_session_pb2.WhatsappSessionEventRequest(
                ctx=_ctx(),
                client_id=client_id,
                connection_id=connection_id,
                state=state,
                vnc_url=vnc_url or f"{settings.novnc_external_url}/vnc-login",
            ),
            timeout=10.0,
        )
        logger.info("Notified Kotlin server: state=%s for %s", state, client_id)
    except Exception as e:
        logger.warning("Failed to notify Kotlin server for %s: %s", client_id, e)


async def notify_capabilities_discovered(
    client_id: str,
    connection_id: str,
    available_capabilities: list[str],
) -> None:
    """Push discovered capabilities to Kotlin server."""
    if not settings.kotlin_server_url:
        return
    try:
        await server_whatsapp_session_stub().CapabilitiesDiscovered(
            whatsapp_session_pb2.WhatsappCapabilitiesRequest(
                ctx=_ctx(),
                client_id=client_id,
                connection_id=connection_id,
                available_capabilities=list(available_capabilities),
            ),
            timeout=10.0,
        )
        logger.info(
            "Capabilities discovered for %s: %s",
            client_id, available_capabilities,
        )
    except Exception as e:
        logger.warning("Failed to push capabilities for %s: %s", client_id, e)


async def shutdown() -> None:
    """No-op — kept for backward compatibility with the old httpx path."""
    return None
