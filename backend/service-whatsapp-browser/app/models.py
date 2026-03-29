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


class SessionInitRequest(BaseModel):
    login_url: str = "https://web.whatsapp.com"
    user_agent: str = (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/131.0.0.0 Safari/537.36"
    )
    capabilities: list[str] = ["CHAT_READ"]
    # Phone number — informational only, not used for login
    phone_number: str | None = None


class SessionInitResponse(BaseModel):
    client_id: str
    state: SessionState
    novnc_url: str | None = None
    message: str


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
