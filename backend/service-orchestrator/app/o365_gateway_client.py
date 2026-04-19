"""gRPC client for jervis-o365-gateway.

All helpers are strongly typed against O365GatewayService. No JSON
passthrough remains — each function dials one RPC and returns the
proto message directly so callers work on typed fields.
"""

from __future__ import annotations

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


# === Teams chats ============================================================

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


# === V5d — Calendar typed ===================================================

async def list_calendar_events(
    client_id: str,
    top: int = 20,
    start_date_time: str = "",
    end_date_time: str = "",
    timeout: float = 30.0,
) -> list[gateway_pb2.CalendarEvent]:
    stub = _get_stub()
    req = gateway_pb2.ListCalendarEventsRequest(
        ctx=_ctx(),
        client_id=client_id,
        top=top,
        start_date_time=start_date_time,
        end_date_time=end_date_time,
    )
    try:
        resp = await stub.ListCalendarEvents(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
    return list(resp.events)


# === V5e — Online meetings typed ============================================

async def get_online_meeting_by_join_url(
    client_id: str, join_web_url: str, timeout: float = 30.0,
) -> gateway_pb2.OnlineMeeting:
    stub = _get_stub()
    req = gateway_pb2.OnlineMeetingByJoinUrlRequest(
        ctx=_ctx(), client_id=client_id, join_web_url=join_web_url,
    )
    try:
        return await stub.GetOnlineMeetingByJoinUrl(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")


async def get_online_meeting(
    client_id: str, meeting_id: str, timeout: float = 30.0,
) -> gateway_pb2.OnlineMeeting:
    stub = _get_stub()
    req = gateway_pb2.OnlineMeetingRequest(
        ctx=_ctx(), client_id=client_id, meeting_id=meeting_id,
    )
    try:
        return await stub.GetOnlineMeeting(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")


async def list_meeting_recordings(
    client_id: str, meeting_id: str, timeout: float = 30.0,
) -> list[gateway_pb2.CallRecording]:
    stub = _get_stub()
    req = gateway_pb2.OnlineMeetingRequest(
        ctx=_ctx(), client_id=client_id, meeting_id=meeting_id,
    )
    try:
        resp = await stub.ListMeetingRecordings(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
    return list(resp.recordings)


async def list_meeting_transcripts(
    client_id: str, meeting_id: str, timeout: float = 30.0,
) -> list[gateway_pb2.CallTranscript]:
    stub = _get_stub()
    req = gateway_pb2.OnlineMeetingRequest(
        ctx=_ctx(), client_id=client_id, meeting_id=meeting_id,
    )
    try:
        resp = await stub.ListMeetingTranscripts(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
    return list(resp.transcripts)


async def download_transcript_vtt(
    client_id: str, meeting_id: str, transcript_id: str, timeout: float = 60.0,
) -> bytes:
    stub = _get_stub()
    req = gateway_pb2.TranscriptRef(
        ctx=_ctx(),
        client_id=client_id,
        meeting_id=meeting_id,
        transcript_id=transcript_id,
    )
    try:
        resp = await stub.DownloadTranscriptVtt(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
    return bytes(resp.vtt)


# === V5f — Drive (OneDrive / SharePoint) typed ==============================

async def list_drive_items(
    client_id: str, path: str = "root", top: int = 50, timeout: float = 30.0,
) -> list[gateway_pb2.DriveItem]:
    stub = _get_stub()
    req = gateway_pb2.ListDriveItemsRequest(
        ctx=_ctx(), client_id=client_id, path=path, top=top,
    )
    try:
        resp = await stub.ListDriveItems(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
    return list(resp.items)


async def get_drive_item(
    client_id: str, item_id: str, timeout: float = 30.0,
) -> gateway_pb2.DriveItem:
    stub = _get_stub()
    req = gateway_pb2.DriveItemRequest(
        ctx=_ctx(), client_id=client_id, item_id=item_id,
    )
    try:
        return await stub.GetDriveItem(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")


async def search_drive(
    client_id: str, query: str, top: int = 25, timeout: float = 30.0,
) -> list[gateway_pb2.DriveItem]:
    stub = _get_stub()
    req = gateway_pb2.SearchDriveRequest(
        ctx=_ctx(), client_id=client_id, query=query, top=top,
    )
    try:
        resp = await stub.SearchDrive(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
    return list(resp.items)


# === V5g — Session status typed ============================================

async def get_session_status(
    client_id: str, timeout: float = 15.0,
) -> gateway_pb2.SessionStatus:
    stub = _get_stub()
    req = gateway_pb2.SessionStatusRequest(ctx=_ctx(), client_id=client_id)
    try:
        return await stub.GetSessionStatus(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        code = e.code().value[0] if e.code() else 502
        # NOT_FOUND -> 404 so callers can branch on "no session"
        if e.code() is not None and e.code().name == "NOT_FOUND":
            code = 404
        raise O365GatewayError(code, e.details() or "")


async def create_calendar_event(
    client_id: str,
    subject: str,
    start_date_time: str,
    start_time_zone: str,
    end_date_time: str,
    end_time_zone: str,
    location: str = "",
    body_content: str = "",
    attendee_addresses: list[str] | None = None,
    is_online_meeting: bool = False,
    timeout: float = 30.0,
) -> gateway_pb2.CalendarEvent:
    stub = _get_stub()
    body = gateway_pb2.MailBody(content_type="text", content=body_content) if body_content else None
    loc = gateway_pb2.Location(display_name=location) if location else None
    attendees = [
        gateway_pb2.Attendee(
            email_address=gateway_pb2.EmailAddress(address=addr), type="required",
        )
        for addr in (attendee_addresses or [])
    ]
    req = gateway_pb2.CreateCalendarEventRequest(
        ctx=_ctx(),
        client_id=client_id,
        subject=subject,
        start=gateway_pb2.DateTimeTimeZone(
            date_time=start_date_time, time_zone=start_time_zone or "UTC",
        ),
        end=gateway_pb2.DateTimeTimeZone(
            date_time=end_date_time, time_zone=end_time_zone or "UTC",
        ),
        attendees=attendees,
        is_online_meeting=is_online_meeting,
    )
    if body is not None:
        req.body.CopyFrom(body)
    if loc is not None:
        req.location.CopyFrom(loc)
    try:
        return await stub.CreateCalendarEvent(req, timeout=timeout)
    except grpc.aio.AioRpcError as e:
        raise O365GatewayError(e.code().value[0] if e.code() else 502, e.details() or "")
