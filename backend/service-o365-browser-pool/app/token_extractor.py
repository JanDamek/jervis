"""Network interception – extracts Bearer tokens from browser traffic.

Three capture mechanisms:
1. Request interception: captures Authorization headers on graph.microsoft.com
2. Response interception: captures tokens from Azure AD token endpoint responses
3. Active acquisition: uses MSAL.js acquireTokenSilent() in the browser
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timedelta, timezone

from playwright.async_api import Page, Request, Response

from app.config import settings
from app.models import TokenInfo

logger = logging.getLogger("o365-browser-pool")

# Teams web client ID (well-known)
TEAMS_WEB_CLIENT_ID = "5e3ce6c0-2b1f-4285-8d4b-75ee78787346"

# MSAL.js script to silently acquire a Graph token using Teams' existing session
_ACQUIRE_GRAPH_TOKEN_JS = """
async () => {
    // Try to find Graph token in sessionStorage/localStorage (MSAL v2/v3 unencrypted)
    for (const storage of [sessionStorage, localStorage]) {
        for (let i = 0; i < storage.length; i++) {
            const key = storage.key(i);
            if (key && key.includes('-accesstoken-') && key.includes('graph.microsoft.com')) {
                try {
                    const val = JSON.parse(storage.getItem(key));
                    if (val && val.secret) {
                        const expiresOn = parseInt(val.expiresOn || '0', 10);
                        const now = Math.floor(Date.now() / 1000);
                        if (expiresOn > now) {
                            return {
                                token: val.secret,
                                expires_on: expiresOn,
                                source: 'msal_cache',
                                scopes: val.target || ''
                            };
                        }
                    }
                } catch(e) {}
            }
        }
    }

    // Try to use MSAL.js if available on the page
    if (typeof msal !== 'undefined' && msal.PublicClientApplication) {
        try {
            const config = {
                auth: {
                    clientId: '%s',
                    authority: 'https://login.microsoftonline.com/common'
                },
                cache: { cacheLocation: 'sessionStorage' }
            };
            const pca = new msal.PublicClientApplication(config);
            await pca.initialize();
            const accounts = pca.getAllAccounts();
            if (accounts.length > 0) {
                const response = await pca.acquireTokenSilent({
                    scopes: ['https://graph.microsoft.com/User.Read',
                             'https://graph.microsoft.com/Mail.Read',
                             'https://graph.microsoft.com/Chat.Read',
                             'https://graph.microsoft.com/Calendars.Read'],
                    account: accounts[0]
                });
                if (response && response.accessToken) {
                    return {
                        token: response.accessToken,
                        expires_on: Math.floor(response.expiresOn.getTime() / 1000),
                        source: 'msal_silent',
                        scopes: response.scopes.join(' ')
                    };
                }
            }
        } catch(e) {
            // acquireTokenSilent can fail — fall through
        }
    }

    return null;
}
""".replace("%s", TEAMS_WEB_CLIENT_ID)


class TokenExtractor:
    """Extracts Graph API and Skype tokens from browser traffic.

    Captures tokens from:
    - Outgoing requests to graph.microsoft.com (Authorization header)
    - Azure AD token endpoint responses (access_token in body)
    - MSAL.js cache in browser storage (active acquisition)
    """

    def __init__(self) -> None:
        self._tokens: dict[str, TokenInfo] = {}
        self._skype_tokens: dict[str, TokenInfo] = {}

    async def setup_interception(self, client_id: str, page: Page) -> None:
        """Set up request and response interception on a page."""

        async def _on_request(request: Request) -> None:
            url = request.url
            auth = request.headers.get("authorization", "")
            if not auth.startswith("Bearer "):
                return

            token = auth[7:]
            now = datetime.now(timezone.utc)

            if "graph.microsoft.com" in url:
                self._store_graph_token(client_id, token, now, url)

            elif "teams.microsoft.com" in url and "skype" in url.lower():
                self._skype_tokens[client_id] = TokenInfo(
                    token=token,
                    extracted_at=now,
                    estimated_expiry=now + timedelta(seconds=settings.skype_token_ttl),
                    source_url=url,
                )
                logger.debug("Captured Skype token for %s", client_id)

        async def _on_response(response: Response) -> None:
            """Intercept Azure AD token endpoint responses."""
            url = response.url
            if "login.microsoftonline.com" not in url:
                return
            if "/oauth2/v2.0/token" not in url and "/oauth2/token" not in url:
                return
            if response.status != 200:
                return

            try:
                body = await response.text()
                data = json.loads(body)
                access_token = data.get("access_token")
                if not access_token:
                    return

                # Check scope/resource to determine token audience
                scope = data.get("scope", "")
                resource = data.get("resource", "")

                now = datetime.now(timezone.utc)
                expires_in = int(data.get("expires_in", 3600))

                if "graph.microsoft.com" in scope or "graph.microsoft.com" in resource:
                    self._store_graph_token(
                        client_id, access_token, now, url, expires_in,
                    )
                    logger.info(
                        "Captured Graph token from Azure AD response for %s "
                        "(expires_in=%ds, scope=%s)",
                        client_id, expires_in, scope[:80],
                    )
            except Exception as e:
                logger.debug("Failed to parse token response: %s", e)

        page.on("request", _on_request)
        page.on("response", _on_response)

    async def acquire_graph_token(self, client_id: str, page: Page) -> bool:
        """Actively try to acquire a Graph token from the browser's MSAL cache.

        Call this after login is detected to extract Graph tokens that
        Teams web doesn't naturally send to graph.microsoft.com.

        Returns True if a token was successfully acquired.
        """
        try:
            result = await page.evaluate(_ACQUIRE_GRAPH_TOKEN_JS)
            if result and result.get("token"):
                now = datetime.now(timezone.utc)
                expires_on = result.get("expires_on", 0)
                if expires_on > 0:
                    expiry = datetime.fromtimestamp(expires_on, tz=timezone.utc)
                else:
                    expiry = now + timedelta(seconds=settings.token_ttl)

                self._store_graph_token(
                    client_id, result["token"], now,
                    f"msal:{result.get('source', 'unknown')}",
                    int((expiry - now).total_seconds()),
                )
                logger.info(
                    "Acquired Graph token via %s for %s (scopes: %s)",
                    result.get("source", "unknown"), client_id,
                    result.get("scopes", "")[:80],
                )
                return True
        except Exception as e:
            logger.warning("MSAL token acquisition failed for %s: %s", client_id, e)
        return False

    def _store_graph_token(
        self,
        client_id: str,
        token: str,
        now: datetime,
        source_url: str,
        expires_in: int | None = None,
    ) -> None:
        ttl = expires_in if expires_in else settings.token_ttl
        self._tokens[client_id] = TokenInfo(
            token=token,
            extracted_at=now,
            estimated_expiry=now + timedelta(seconds=ttl),
            source_url=source_url,
        )
        logger.info(
            "Stored Graph token for %s (ttl=%ds, source=%s)",
            client_id, ttl, source_url[:60],
        )

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

    def has_any_token(self, client_id: str) -> bool:
        """Check if any token was ever captured for this client (even expired)."""
        return client_id in self._tokens or client_id in self._skype_tokens
