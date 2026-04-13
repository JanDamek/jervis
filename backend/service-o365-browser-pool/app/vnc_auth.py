"""VNC one-time token authentication manager.

Manages one-time access tokens for noVNC. Each token is consumed on first use
(when user opens VNC login page), and a new token is required for each access.

Token format: {podOrdinal}_{randomHex}
This allows any pod in the StatefulSet to determine which pod owns the token
without shared state (MongoDB). The owning pod validates/consumes from its
in-memory store; other pods redirect to the owner via StatefulSet DNS.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone, timedelta

from app.config import settings

logger = logging.getLogger("o365-browser-pool.vnc-auth")


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
        """Create a one-time VNC access token for a client.

        Token format: {podOrdinal}_{randomHex}
        Returns the full token string. Token is valid for vnc_token_ttl seconds.
        """
        # Invalidate any previous tokens for this client
        self._tokens = {
            k: v for k, v in self._tokens.items()
            if v.client_id != client_id
        }

        random_part = uuid.uuid4().hex
        token = f"{settings.pod_ordinal}_{random_part}"
        now = datetime.now(timezone.utc)
        self._tokens[token] = VncAccessToken(
            token=token,
            client_id=client_id,
            created_at=now,
            expires_at=now + timedelta(seconds=settings.vnc_token_ttl),
        )
        logger.info(
            "Created VNC token for client %s on pod %d (expires in %ds)",
            client_id, settings.pod_ordinal, settings.vnc_token_ttl,
        )
        return token

    @staticmethod
    def parse_pod_ordinal(token: str) -> int | None:
        """Extract pod ordinal from token. Returns None if format invalid."""
        parts = token.split("_", 1)
        if len(parts) != 2:
            return None
        try:
            return int(parts[0])
        except ValueError:
            return None

    def validate_and_consume_token(self, token: str) -> str | None:
        """Validate a one-time token and consume it.

        Returns client_id if valid, None if invalid/expired.
        Token is removed after validation (one-time use).
        """
        access_token = self._tokens.pop(token, None)
        if access_token is None:
            logger.warning("VNC token not found or already consumed")
            return None

        now = datetime.now(timezone.utc)
        if now > access_token.expires_at:
            logger.warning("VNC token expired for client %s", access_token.client_id)
            return None

        logger.info("VNC token consumed for client %s", access_token.client_id)
        return access_token.client_id

    def create_session(self, ttl_seconds: int = 3600) -> str:
        """Create a VNC session (cookie-based) after token validation.

        Returns session_id. Caller stores cookie as '{ordinal}_{session_id}'.
        Session is stored under BOTH keys (plain + composite) so validation
        works regardless of format.
        """
        session_id = uuid.uuid4().hex
        expiry = datetime.now(timezone.utc) + timedelta(seconds=ttl_seconds)
        self._sessions[session_id] = expiry
        # Also store composite key (ordinal_sessionId) for direct cookie lookup
        composite = f"{settings.pod_ordinal}_{session_id}"
        self._sessions[composite] = expiry
        self._cleanup_expired()
        logger.info("Created VNC session (ttl=%ds, active=%d)", ttl_seconds, len(self._sessions))
        return session_id

    def is_session_valid(self, session_id: str) -> bool:
        """Check if a VNC session is still valid."""
        expiry = self._sessions.get(session_id)
        if expiry is None:
            return False
        if datetime.now(timezone.utc) > expiry:
            del self._sessions[session_id]
            return False
        return True

    def invalidate_session(self, session_id: str) -> None:
        """Invalidate a specific VNC session."""
        self._sessions.pop(session_id, None)

    def _cleanup_expired(self) -> None:
        """Remove expired tokens and sessions."""
        now = datetime.now(timezone.utc)
        self._tokens = {k: v for k, v in self._tokens.items() if v.expires_at > now}
        self._sessions = {k: v for k, v in self._sessions.items() if v > now}
