"""VNC one-time token authentication manager.

Reuses same pattern as O365 browser pool — one-time tokens consumed on login,
session cookies for active VNC sessions.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone, timedelta

from app.config import settings

logger = logging.getLogger("whatsapp-browser.vnc-auth")


@dataclass
class VncAccessToken:
    """One-time VNC access token."""
    token: str
    client_id: str
    created_at: datetime
    expires_at: datetime


class VncAuthManager:
    """Manages one-time VNC access tokens and active sessions."""

    def __init__(self) -> None:
        self._tokens: dict[str, VncAccessToken] = {}
        self._sessions: dict[str, datetime] = {}

    def create_token(self, client_id: str) -> str:
        self._tokens = {
            k: v for k, v in self._tokens.items()
            if v.client_id != client_id
        }

        token = uuid.uuid4().hex
        now = datetime.now(timezone.utc)
        self._tokens[token] = VncAccessToken(
            token=token,
            client_id=client_id,
            created_at=now,
            expires_at=now + timedelta(seconds=settings.vnc_token_ttl),
        )
        logger.info("Created VNC token for client %s", client_id)
        return token

    def validate_and_consume_token(self, token: str) -> str | None:
        access_token = self._tokens.pop(token, None)
        if access_token is None:
            return None

        now = datetime.now(timezone.utc)
        if now > access_token.expires_at:
            return None

        return access_token.client_id

    def create_session(self, ttl_seconds: int = 3600) -> str:
        session_id = uuid.uuid4().hex
        self._sessions[session_id] = datetime.now(timezone.utc) + timedelta(seconds=ttl_seconds)
        self._cleanup_expired()
        return session_id

    def is_session_valid(self, session_id: str) -> bool:
        expiry = self._sessions.get(session_id)
        if expiry is None:
            return False
        if datetime.now(timezone.utc) > expiry:
            del self._sessions[session_id]
            return False
        return True

    def _cleanup_expired(self) -> None:
        now = datetime.now(timezone.utc)
        self._tokens = {k: v for k, v in self._tokens.items() if v.expires_at > now}
        self._sessions = {k: v for k, v in self._sessions.items() if v > now}
