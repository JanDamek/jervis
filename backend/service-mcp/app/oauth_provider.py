"""OAuth 2.1 Authorization Server for Jervis MCP.

Implements the OAuth 2.1 flow required by Claude.ai MCP connectors:
- RFC 8414 Server Metadata (/.well-known/oauth-authorization-server)
- RFC 7591 Dynamic Client Registration (/oauth/register)
- Authorization endpoint with Google IdP redirect (/oauth/authorize)
- Google OAuth callback (/oauth/callback)
- Token endpoint with PKCE validation (/oauth/token)

Only whitelisted Google accounts (OAUTH_ALLOWED_EMAILS) can obtain tokens.
"""

from __future__ import annotations

import hashlib
import base64
import json
import logging
import secrets
import time
from typing import Any
from urllib.parse import urlencode

import httpx
from starlette.requests import Request
from starlette.responses import JSONResponse, RedirectResponse, Response
from starlette.routing import Route

from app.config import settings

logger = logging.getLogger("jervis-mcp.oauth")

# ── In-memory stores ─────────────────────────────────────────────────────

# Dynamic Client Registration store: client_id -> client info
_dcr_clients: dict[str, dict[str, Any]] = {}

# Authorization sessions: state -> session data
_auth_sessions: dict[str, dict[str, Any]] = {}

# Issued authorization codes: code -> session data
_auth_codes: dict[str, dict[str, Any]] = {}

# Issued refresh tokens: token -> token data
_refresh_tokens: dict[str, dict[str, Any]] = {}

# Issued access tokens: token -> token data (for validation)
_access_tokens: dict[str, dict[str, Any]] = {}


# ── Helpers ───────────────────────────────────────────────────────────────


def _generate_token(nbytes: int = 32) -> str:
    return secrets.token_hex(nbytes)


def _now() -> int:
    return int(time.time())


def _is_email_allowed(email: str) -> bool:
    allowed = settings.oauth_allowed_emails
    if not allowed:
        return False
    return email.lower() in {e.strip().lower() for e in allowed.split(",")}


def _validate_pkce(code_verifier: str, code_challenge: str) -> bool:
    """Validate PKCE S256: BASE64URL(SHA256(code_verifier)) == code_challenge."""
    digest = hashlib.sha256(code_verifier.encode("ascii")).digest()
    computed = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    return computed == code_challenge


def _cleanup_expired() -> None:
    """Remove expired entries from in-memory stores."""
    now = _now()
    # Auth sessions expire after 10 minutes
    expired_sessions = [
        k for k, v in _auth_sessions.items() if now - v.get("created", 0) > 600
    ]
    for k in expired_sessions:
        del _auth_sessions[k]

    # Auth codes expire after 5 minutes
    expired_codes = [
        k for k, v in _auth_codes.items() if now - v.get("created", 0) > 300
    ]
    for k in expired_codes:
        del _auth_codes[k]

    # Access tokens expire based on configured expiry
    expired_access = [
        k for k, v in _access_tokens.items() if now > v.get("expires_at", 0)
    ]
    for k in expired_access:
        del _access_tokens[k]

    # Refresh tokens expire based on configured expiry
    expired_refresh = [
        k for k, v in _refresh_tokens.items() if now > v.get("expires_at", 0)
    ]
    for k in expired_refresh:
        del _refresh_tokens[k]


# ── Token validation (used by auth middleware) ────────────────────────────


def validate_oauth_token(token: str) -> dict[str, Any] | None:
    """Validate an OAuth-issued access token. Returns token data or None."""
    _cleanup_expired()
    return _access_tokens.get(token)


# ── Endpoints ─────────────────────────────────────────────────────────────


async def well_known_metadata(request: Request) -> JSONResponse:
    """RFC 8414 – OAuth 2.0 Authorization Server Metadata."""
    issuer = settings.oauth_issuer
    return JSONResponse(
        {
            "issuer": issuer,
            "authorization_endpoint": f"{issuer}/oauth/authorize",
            "token_endpoint": f"{issuer}/oauth/token",
            "registration_endpoint": f"{issuer}/oauth/register",
            "response_types_supported": ["code"],
            "grant_types_supported": ["authorization_code", "refresh_token"],
            "code_challenge_methods_supported": ["S256"],
            "token_endpoint_auth_methods_supported": [
                "client_secret_post",
            ],
            "scopes_supported": ["mcp:tools"],
        },
        headers={"Cache-Control": "no-store"},
    )


async def register_client(request: Request) -> JSONResponse:
    """RFC 7591 – Dynamic Client Registration."""
    try:
        body = await request.json()
    except Exception:
        return JSONResponse({"error": "invalid_request"}, status_code=400)

    redirect_uris = body.get("redirect_uris", [])
    if not redirect_uris:
        return JSONResponse(
            {"error": "invalid_request", "error_description": "redirect_uris required"},
            status_code=400,
        )

    client_id = _generate_token(16)
    client_secret = _generate_token(32)

    client_info = {
        "client_id": client_id,
        "client_secret": client_secret,
        "client_name": body.get("client_name", "unknown"),
        "redirect_uris": redirect_uris,
        "grant_types": body.get("grant_types", ["authorization_code"]),
        "response_types": body.get("response_types", ["code"]),
        "token_endpoint_auth_method": body.get(
            "token_endpoint_auth_method", "client_secret_post"
        ),
        "created_at": _now(),
    }
    _dcr_clients[client_id] = client_info

    logger.info("DCR: registered client %s (%s)", client_id[:8], client_info["client_name"])

    return JSONResponse(
        {
            "client_id": client_id,
            "client_secret": client_secret,
            "client_name": client_info["client_name"],
            "redirect_uris": redirect_uris,
            "grant_types": client_info["grant_types"],
            "response_types": client_info["response_types"],
            "token_endpoint_auth_method": client_info["token_endpoint_auth_method"],
        },
        status_code=201,
    )


async def authorize(request: Request) -> Response:
    """Authorization endpoint – validates params, redirects to Google OAuth."""
    client_id = request.query_params.get("client_id", "")
    redirect_uri = request.query_params.get("redirect_uri", "")
    state = request.query_params.get("state", "")
    code_challenge = request.query_params.get("code_challenge", "")
    code_challenge_method = request.query_params.get("code_challenge_method", "")
    response_type = request.query_params.get("response_type", "code")
    scope = request.query_params.get("scope", "")

    # Validate client
    if client_id not in _dcr_clients:
        return JSONResponse(
            {"error": "invalid_client", "error_description": "Unknown client_id"},
            status_code=400,
        )

    client = _dcr_clients[client_id]
    if redirect_uri not in client["redirect_uris"]:
        return JSONResponse(
            {"error": "invalid_request", "error_description": "redirect_uri mismatch"},
            status_code=400,
        )

    if response_type != "code":
        return JSONResponse(
            {"error": "unsupported_response_type"},
            status_code=400,
        )

    if code_challenge_method and code_challenge_method != "S256":
        return JSONResponse(
            {"error": "invalid_request", "error_description": "Only S256 supported"},
            status_code=400,
        )

    # Store session
    internal_state = _generate_token(16)
    _auth_sessions[internal_state] = {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "state": state,
        "code_challenge": code_challenge,
        "scope": scope,
        "created": _now(),
    }

    # Redirect to Google OAuth
    google_params = {
        "client_id": settings.google_client_id,
        "redirect_uri": f"{settings.oauth_issuer}/oauth/callback",
        "response_type": "code",
        "scope": "openid email profile",
        "state": internal_state,
        "access_type": "offline",
        "prompt": "consent",
    }
    google_url = "https://accounts.google.com/o/oauth2/v2/auth?" + urlencode(google_params)

    logger.info("OAuth authorize: redirecting to Google (client=%s)", client_id[:8])
    return RedirectResponse(url=google_url, status_code=302)


async def google_callback(request: Request) -> Response:
    """Google OAuth callback – exchange code, verify email, issue Jervis auth code."""
    error = request.query_params.get("error")
    if error:
        logger.warning("Google OAuth error: %s", error)
        return JSONResponse(
            {"error": "access_denied", "error_description": f"Google error: {error}"},
            status_code=403,
        )

    google_code = request.query_params.get("code", "")
    internal_state = request.query_params.get("state", "")

    if internal_state not in _auth_sessions:
        return JSONResponse(
            {"error": "invalid_request", "error_description": "Invalid or expired state"},
            status_code=400,
        )

    session = _auth_sessions.pop(internal_state)

    # Exchange Google code for tokens
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            "https://oauth2.googleapis.com/token",
            data={
                "code": google_code,
                "client_id": settings.google_client_id,
                "client_secret": settings.google_client_secret,
                "redirect_uri": f"{settings.oauth_issuer}/oauth/callback",
                "grant_type": "authorization_code",
            },
        )

    if resp.status_code != 200:
        logger.error("Google token exchange failed: %s", resp.text)
        return JSONResponse(
            {"error": "server_error", "error_description": "Google token exchange failed"},
            status_code=500,
        )

    google_tokens = resp.json()

    # Decode id_token to get email (Google id_tokens are JWTs but we just
    # need the payload – no need to verify signature since we just got it
    # from Google over HTTPS)
    id_token = google_tokens.get("id_token", "")
    try:
        # JWT payload is the second segment
        payload_b64 = id_token.split(".")[1]
        # Add padding
        payload_b64 += "=" * (4 - len(payload_b64) % 4)
        payload = json.loads(base64.urlsafe_b64decode(payload_b64))
        email = payload.get("email", "")
    except Exception:
        logger.error("Failed to decode Google id_token")
        return JSONResponse(
            {"error": "server_error", "error_description": "Failed to decode identity"},
            status_code=500,
        )

    # Check email whitelist
    if not _is_email_allowed(email):
        logger.warning("OAuth denied for email: %s", email)
        return JSONResponse(
            {"error": "access_denied", "error_description": "Email not authorized"},
            status_code=403,
        )

    logger.info("OAuth authorized for email: %s", email)

    # Issue Jervis authorization code
    auth_code = _generate_token(32)
    _auth_codes[auth_code] = {
        "email": email,
        "client_id": session["client_id"],
        "redirect_uri": session["redirect_uri"],
        "code_challenge": session["code_challenge"],
        "scope": session.get("scope", ""),
        "created": _now(),
    }

    # Redirect back to Claude with auth code
    redirect_uri = session["redirect_uri"]
    sep = "&" if "?" in redirect_uri else "?"
    redirect_url = f"{redirect_uri}{sep}code={auth_code}"
    if session.get("state"):
        redirect_url += f"&state={session['state']}"

    return RedirectResponse(url=redirect_url, status_code=302)


async def token_exchange(request: Request) -> JSONResponse:
    """Token endpoint – exchange authorization code for access + refresh token."""
    try:
        if request.headers.get("content-type", "").startswith("application/json"):
            body = await request.json()
        else:
            form = await request.form()
            body = dict(form)
    except Exception:
        return JSONResponse({"error": "invalid_request"}, status_code=400)

    grant_type = body.get("grant_type", "")

    if grant_type == "authorization_code":
        return await _handle_auth_code_exchange(body)
    elif grant_type == "refresh_token":
        return await _handle_refresh_token(body)
    else:
        return JSONResponse(
            {"error": "unsupported_grant_type"},
            status_code=400,
        )


async def _handle_auth_code_exchange(body: dict) -> JSONResponse:
    """Exchange authorization code for tokens."""
    code = body.get("code", "")
    client_id = body.get("client_id", "")
    client_secret = body.get("client_secret", "")
    redirect_uri = body.get("redirect_uri", "")
    code_verifier = body.get("code_verifier", "")

    if code not in _auth_codes:
        return JSONResponse(
            {"error": "invalid_grant", "error_description": "Invalid or expired code"},
            status_code=400,
        )

    code_data = _auth_codes.pop(code)

    # Validate client
    if client_id not in _dcr_clients:
        return JSONResponse({"error": "invalid_client"}, status_code=401)

    dcr_client = _dcr_clients[client_id]
    if dcr_client["client_secret"] != client_secret:
        return JSONResponse({"error": "invalid_client"}, status_code=401)

    if code_data["client_id"] != client_id:
        return JSONResponse({"error": "invalid_grant"}, status_code=400)

    if code_data["redirect_uri"] != redirect_uri:
        return JSONResponse(
            {"error": "invalid_grant", "error_description": "redirect_uri mismatch"},
            status_code=400,
        )

    # Validate PKCE
    if code_data.get("code_challenge") and code_verifier:
        if not _validate_pkce(code_verifier, code_data["code_challenge"]):
            return JSONResponse(
                {"error": "invalid_grant", "error_description": "PKCE validation failed"},
                status_code=400,
            )

    # Issue tokens
    now = _now()
    access_token = _generate_token(32)
    refresh_token = _generate_token(32)

    token_data = {
        "email": code_data["email"],
        "client_id": client_id,
        "scope": code_data.get("scope", ""),
        "issued_at": now,
        "expires_at": now + settings.oauth_token_expiry,
    }

    _access_tokens[access_token] = token_data
    _refresh_tokens[refresh_token] = {
        "email": code_data["email"],
        "client_id": client_id,
        "scope": code_data.get("scope", ""),
        "issued_at": now,
        "expires_at": now + settings.oauth_refresh_expiry,
    }

    logger.info(
        "Token issued for %s (client=%s, expires=%ds)",
        code_data["email"],
        client_id[:8],
        settings.oauth_token_expiry,
    )

    return JSONResponse({
        "access_token": access_token,
        "token_type": "Bearer",
        "expires_in": settings.oauth_token_expiry,
        "refresh_token": refresh_token,
        "scope": code_data.get("scope", ""),
    })


async def _handle_refresh_token(body: dict) -> JSONResponse:
    """Exchange refresh token for new access token."""
    refresh_token = body.get("refresh_token", "")
    client_id = body.get("client_id", "")
    client_secret = body.get("client_secret", "")

    if refresh_token not in _refresh_tokens:
        return JSONResponse(
            {"error": "invalid_grant", "error_description": "Invalid or expired refresh token"},
            status_code=400,
        )

    rt_data = _refresh_tokens[refresh_token]

    # Validate client
    if client_id and client_id in _dcr_clients:
        if _dcr_clients[client_id]["client_secret"] != client_secret:
            return JSONResponse({"error": "invalid_client"}, status_code=401)

    # Issue new access token
    now = _now()
    new_access_token = _generate_token(32)

    _access_tokens[new_access_token] = {
        "email": rt_data["email"],
        "client_id": rt_data["client_id"],
        "scope": rt_data.get("scope", ""),
        "issued_at": now,
        "expires_at": now + settings.oauth_token_expiry,
    }

    # Optionally rotate refresh token
    new_refresh_token = _generate_token(32)
    _refresh_tokens[new_refresh_token] = {
        "email": rt_data["email"],
        "client_id": rt_data["client_id"],
        "scope": rt_data.get("scope", ""),
        "issued_at": now,
        "expires_at": now + settings.oauth_refresh_expiry,
    }
    del _refresh_tokens[refresh_token]

    logger.info("Token refreshed for %s", rt_data["email"])

    return JSONResponse({
        "access_token": new_access_token,
        "token_type": "Bearer",
        "expires_in": settings.oauth_token_expiry,
        "refresh_token": new_refresh_token,
        "scope": rt_data.get("scope", ""),
    })


# ── Starlette routes ─────────────────────────────────────────────────────

oauth_routes = [
    Route(
        "/.well-known/oauth-authorization-server",
        well_known_metadata,
        methods=["GET"],
    ),
    Route("/oauth/register", register_client, methods=["POST"]),
    Route("/oauth/authorize", authorize, methods=["GET"]),
    Route("/oauth/callback", google_callback, methods=["GET"]),
    Route("/oauth/token", token_exchange, methods=["POST"]),
]
