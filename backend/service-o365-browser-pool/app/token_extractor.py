"""Network interception – extracts Bearer tokens from browser requests."""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from playwright.async_api import Page, Request

from app.config import settings
from app.models import TokenInfo

logger = logging.getLogger("o365-browser-pool")


class TokenExtractor:
    """Intercepts outgoing requests to ``graph.microsoft.com`` and captures
    the ``Authorization: Bearer ...`` header.

    Also captures Skype tokens from ``*.teams.microsoft.com`` requests.
    """

    def __init__(self) -> None:
        self._tokens: dict[str, TokenInfo] = {}
        self._skype_tokens: dict[str, TokenInfo] = {}

    async def setup_interception(self, client_id: str, page: Page) -> None:
        async def _on_request(request: Request) -> None:
            url = request.url
            auth = request.headers.get("authorization", "")
            if not auth.startswith("Bearer "):
                return

            token = auth[7:]
            now = datetime.now(timezone.utc)

            if "graph.microsoft.com" in url:
                self._tokens[client_id] = TokenInfo(
                    token=token,
                    extracted_at=now,
                    estimated_expiry=now + timedelta(seconds=settings.token_ttl),
                    source_url=url,
                )
                logger.debug("Captured Graph token for %s from %s", client_id, url)

            elif "teams.microsoft.com" in url and "skype" in url.lower():
                self._skype_tokens[client_id] = TokenInfo(
                    token=token,
                    extracted_at=now,
                    estimated_expiry=now + timedelta(seconds=settings.skype_token_ttl),
                    source_url=url,
                )
                logger.debug("Captured Skype token for %s", client_id)

        page.on("request", _on_request)

    def get_graph_token(self, client_id: str) -> TokenInfo | None:
        info = self._tokens.get(client_id)
        if info and info.estimated_expiry > datetime.now(timezone.utc):
            return info
        return None

    def get_skype_token(self, client_id: str) -> TokenInfo | None:
        info = self._skype_tokens.get(client_id)
        if info and info.estimated_expiry > datetime.now(timezone.utc):
            return info
        return None

    def invalidate(self, client_id: str) -> None:
        self._tokens.pop(client_id, None)
        self._skype_tokens.pop(client_id, None)

    def has_valid_token(self, client_id: str) -> bool:
        return self.get_graph_token(client_id) is not None
