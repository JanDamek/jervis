"""gRPC client for jervis-o365-gateway (MCP service).

Mirrors the orchestrator's o365_gateway_client — one insecure_channel to
`<o365-gateway>:5501`, lazy stub, passthrough `request` helper.
"""

from __future__ import annotations

import json as _json
import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.common import types_pb2
from jervis.o365_gateway import gateway_pb2, gateway_pb2_grpc

logger = logging.getLogger("mcp.o365_gateway")

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
    return types_pb2.RequestContext(request_id="", trace={"caller": "service-mcp"})


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


# === V5c — Mail (Outlook) typed =============================================

async def list_mail(
    client_id: str,
    top: int = 20,
    folder: str = "inbox",
    filter: str = "",
    timeout: float = 30.0,
) -> list[gateway_pb2.MailMessage]:
    stub = _get_stub()
    req = gateway_pb2.ListMailRequest(
        ctx=_ctx(), client_id=client_id, top=top, folder=folder, filter=filter,
    )
    try:
        resp = await stub.ListMail(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
    return list(resp.messages)


async def read_mail(
    client_id: str, message_id: str, timeout: float = 30.0,
) -> gateway_pb2.MailMessage:
    stub = _get_stub()
    req = gateway_pb2.ReadMailRequest(
        ctx=_ctx(), client_id=client_id, message_id=message_id,
    )
    try:
        return await stub.ReadMail(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")


async def send_mail(
    client_id: str,
    subject: str,
    body_content: str,
    to_addresses: list[str],
    cc_addresses: list[str] | None = None,
    content_type: str = "text",
    save_to_sent_items: bool = True,
    timeout: float = 30.0,
) -> gateway_pb2.SendMailAck:
    stub = _get_stub()
    body = gateway_pb2.MailBody(content_type=content_type, content=body_content)
    to_recipients = [
        gateway_pb2.Recipient(email_address=gateway_pb2.EmailAddress(address=addr))
        for addr in to_addresses
    ]
    cc_recipients = [
        gateway_pb2.Recipient(email_address=gateway_pb2.EmailAddress(address=addr))
        for addr in (cc_addresses or [])
    ]
    req = gateway_pb2.SendMailRpcRequest(
        ctx=_ctx(),
        client_id=client_id,
        subject=subject,
        body=body,
        to_recipients=to_recipients,
        cc_recipients=cc_recipients,
        save_to_sent_items=save_to_sent_items,
    )
    try:
        return await stub.SendMail(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
