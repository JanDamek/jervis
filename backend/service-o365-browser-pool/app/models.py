"""Pydantic models for O365 Browser Pool."""

from __future__ import annotations

from datetime import datetime
from enum import Enum

from pydantic import BaseModel


class SessionState(str, Enum):
    ACTIVE = "ACTIVE"
    EXPIRED = "EXPIRED"
    PENDING_LOGIN = "PENDING_LOGIN"
    AWAITING_MFA = "AWAITING_MFA"
    ERROR = "ERROR"


class TokenInfo(BaseModel):
    token: str
    extracted_at: datetime
    estimated_expiry: datetime
    source_url: str


class TokenResponse(BaseModel):
    token: str
    expires_at: str
    age_seconds: int


class SessionStatus(BaseModel):
    client_id: str
    state: SessionState
    has_token: bool = False
    last_activity: str | None = None
    last_token_extract: str | None = None
    novnc_url: str | None = None
    # MFA info (when state == AWAITING_MFA)
    mfa_type: str | None = None
    mfa_message: str | None = None
    mfa_number: str | None = None  # Number to approve in authenticator


class SessionInitRequest(BaseModel):
    login_url: str = "https://teams.microsoft.com"
    user_agent: str = (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/131.0.0.0 Safari/537.36"
    )
    # Capabilities from connection — determines which tabs to open
    capabilities: list[str] = []  # e.g. ["CHAT_READ", "EMAIL_READ", "CALENDAR_READ"]
    # Auto-login credentials (optional — if provided, Playwright fills the login form)
    username: str | None = None
    password: str | None = None


class SessionInitResponse(BaseModel):
    client_id: str
    state: SessionState
    novnc_url: str | None = None
    message: str
