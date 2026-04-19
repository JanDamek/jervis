"""gRPC client for jervis-o365-gateway.

Passthrough shape — one `request` call maps (method, path, query, body)
onto the `O365GatewayService.Request` RPC. Body responses come back as
raw JSON strings that tools parse the same way they used to parse the
HTTP response body.
"""

from __future__ import annotations

import json as _json
import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.common import types_pb2
from jervis.o365_gateway import gateway_pb2, gateway_pb2_grpc

logger = logging.getLogger("orchestrator.o365_gateway")

_channel: Optional[grpc.aio.Channel] = None
_stub: Optional[gateway_pb2_grpc.O365GatewayServiceStub] = None


def _target() -> str:
    url = (getattr(settings, "o365_gateway_url", None) or "http://jervis-o365-gateway:8080").rstrip("/")
    if "://" in url:
        url = url.split("://", 1)[1]
    host = url.split("/")[0].split(":")[0]
    return f"{host}:5501"


def _get_stub() -> gateway_pb2_grpc.O365GatewayServiceStub:
    global _channel, _stub
    if _stub is None:
        _channel = grpc.aio.insecure_channel(
            _target(),
            options=[
                ("grpc.max_receive_message_length", 64 * 1024 * 1024),
                ("grpc.max_send_message_length", 64 * 1024 * 1024),
            ],
        )
        _stub = gateway_pb2_grpc.O365GatewayServiceStub(_channel)
    return _stub


def _ctx() -> types_pb2.RequestContext:
    return types_pb2.RequestContext(
        request_id="",
        trace={"caller": "service-orchestrator"},
    )


class O365GatewayError(Exception):
    def __init__(self, status_code: int, body: str):
        self.status_code = status_code
        self.body = body
        super().__init__(f"O365 gateway returned {status_code}: {body[:200]}")


async def o365_request(
    method: str,
    path: str,
    query: Optional[dict] = None,
    body: Optional[dict] = None,
    timeout: float = 30.0,
) -> dict | list:
    """Call the O365 gateway and return the parsed JSON body.

    `path` is relative to the old `/api/o365/` root (e.g.
    `chats/{clientId}`). Raises O365GatewayError on non-2xx responses.
    """
    stub = _get_stub()
    req = gateway_pb2.O365Request(
        ctx=_ctx(),
        method=(method or "GET").upper(),
        path=path.lstrip("/"),
        query={str(k): str(v) for k, v in (query or {}).items() if v is not None},
        body_json=_json.dumps(body) if body is not None else "",
    )
    resp = await stub.Request(req, timeout=timeout)
    if resp.status_code >= 400:
        raise O365GatewayError(resp.status_code, resp.body_json)
    if not resp.body_json:
        return {}
    try:
        return _json.loads(resp.body_json)
    except Exception:
        return {"raw": resp.body_json}


async def o365_request_bytes(
    path: str,
    query: Optional[dict] = None,
    timeout: float = 60.0,
) -> tuple[int, bytes, str]:
    """Binary passthrough — used for transcript VTT / drive content."""
    stub = _get_stub()
    req = gateway_pb2.O365Request(
        ctx=_ctx(),
        method="GET",
        path=path.lstrip("/"),
        query={str(k): str(v) for k, v in (query or {}).items() if v is not None},
    )
    resp = await stub.RequestBytes(req, timeout=timeout)
    return resp.status_code, bytes(resp.body), resp.content_type


# === V5a — Teams chats typed ================================================

async def list_chats(
    client_id: str, top: int = 20, timeout: float = 30.0,
) -> list[gateway_pb2.ChatSummary]:
    stub = _get_stub()
    req = gateway_pb2.ListChatsRequest(ctx=_ctx(), client_id=client_id, top=top)
    try:
        resp = await stub.ListChats(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
    return list(resp.chats)


async def read_chat(
    client_id: str, chat_id: str, top: int = 20, timeout: float = 30.0,
) -> list[gateway_pb2.ChatMessage]:
    stub = _get_stub()
    req = gateway_pb2.ReadChatRequest(
        ctx=_ctx(), client_id=client_id, chat_id=chat_id, top=top,
    )
    try:
        resp = await stub.ReadChat(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
    return list(resp.messages)


async def send_chat_message(
    client_id: str,
    chat_id: str,
    content: str,
    content_type: str = "text",
    timeout: float = 30.0,
) -> gateway_pb2.ChatMessage:
    stub = _get_stub()
    req = gateway_pb2.SendChatMessageRequest(
        ctx=_ctx(),
        client_id=client_id,
        chat_id=chat_id,
        content=content,
        content_type=content_type,
    )
    try:
        return await stub.SendChatMessage(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")


# === V5b — Teams teams/channels typed =======================================

async def list_teams(client_id: str, timeout: float = 30.0) -> list[gateway_pb2.Team]:
    stub = _get_stub()
    req = gateway_pb2.ListTeamsRequest(ctx=_ctx(), client_id=client_id)
    try:
        resp = await stub.ListTeams(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
    return list(resp.teams)


async def list_channels(
    client_id: str, team_id: str, timeout: float = 30.0,
) -> list[gateway_pb2.Channel]:
    stub = _get_stub()
    req = gateway_pb2.ListChannelsRequest(ctx=_ctx(), client_id=client_id, team_id=team_id)
    try:
        resp = await stub.ListChannels(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
    return list(resp.channels)


async def read_channel(
    client_id: str,
    team_id: str,
    channel_id: str,
    top: int = 20,
    timeout: float = 30.0,
) -> list[gateway_pb2.ChatMessage]:
    stub = _get_stub()
    req = gateway_pb2.ReadChannelRequest(
        ctx=_ctx(),
        client_id=client_id,
        team_id=team_id,
        channel_id=channel_id,
        top=top,
    )
    try:
        resp = await stub.ReadChannel(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
    return list(resp.messages)


async def send_channel_message(
    client_id: str,
    team_id: str,
    channel_id: str,
    content: str,
    content_type: str = "text",
    timeout: float = 30.0,
) -> gateway_pb2.ChatMessage:
    stub = _get_stub()
    req = gateway_pb2.SendChannelMessageRequest(
        ctx=_ctx(),
        client_id=client_id,
        team_id=team_id,
        channel_id=channel_id,
        content=content,
        content_type=content_type,
    )
    try:
        return await stub.SendChannelMessage(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
