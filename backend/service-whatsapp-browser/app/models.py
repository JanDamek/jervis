"""Pydantic models for WhatsApp Browser service."""

from __future__ import annotations

from datetime import datetime
from enum import Enum

from pydantic import BaseModel


class SessionState(str, Enum):
    ACTIVE = "ACTIVE"
    EXPIRED = "EXPIRED"
    PENDING_LOGIN = "PENDING_LOGIN"
    ERROR = "ERROR"


class SessionStatus(BaseModel):
    client_id: str
    state: SessionState
    last_activity: str | None = None
    novnc_url: str | None = None
    message: str | None = None


# SessionInitRequest / SessionInitResponse removed 2026-04-20 — gRPC
# `jervis.whatsapp_browser.WhatsAppBrowserService.InitSession` is the
# only caller and speaks the typed Protobuf message directly. No need
# for a parallel Pydantic mirror; mirrors invite the "proto default
# "" becomes Pydantic None" class of bug (see guideline §11).


class WhatsAppMessage(BaseModel):
    """Single message extracted from WhatsApp Web."""
    sender: str
    time: str
    content: str
    type: str = "text"  # text, image, voice, document, sticker, video
    is_group: bool = False
    chat_name: str = ""
    is_forwarded: bool = False
    reply_to: str | None = None
    # Attachment info
    attachment_type: str | None = None  # image, video, document, voice, sticker
    attachment_description: str | None = None  # VLM description of the attachment


class WhatsAppChat(BaseModel):
    """Chat entry from sidebar."""
    name: str
    last_message: str = ""
    time: str = ""
    unread_count: int = 0
    is_group: bool = False
    is_pinned: bool = False
