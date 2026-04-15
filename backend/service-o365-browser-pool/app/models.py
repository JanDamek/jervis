"""Pydantic models for O365 Browser Pool API.

Pod state lives in PodStateManager (pod_state.py) — these models just
serialize the state value as string for HTTP responses.
"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel


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
    state: str  # PodState value (STARTING, AUTHENTICATING, ACTIVE, AWAITING_MFA, ERROR, ...)
    has_token: bool = False
    last_activity: str | None = None
    last_token_extract: str | None = None
    novnc_url: str | None = None
    mfa_type: str | None = None
    mfa_message: str | None = None
    mfa_number: str | None = None


class SessionInitRequest(BaseModel):
    login_url: str = "https://teams.microsoft.com"
    user_agent: str = (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/131.0.0.0 Safari/537.36"
    )
    capabilities: list[str] = []
    username: str | None = None
    password: str | None = None


class SessionInitResponse(BaseModel):
    client_id: str
    state: str  # PodState value
    novnc_url: str | None = None
    message: str
