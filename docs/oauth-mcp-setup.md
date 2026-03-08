# OAuth 2.1 MCP Gateway – Setup & Configuration

**Status:** Implementováno (2026-03-08)
**Účel:** OAuth 2.1 autorizace pro Jervis MCP server – umožňuje připojení z Claude.ai (web) a Claude iOS/Android

## GCP Project

- **Projekt:** `jervis-e3bd4` (Firebase projekt, sdílený s Android FCM notifikacemi)
- **Účet:** `damek@mazlusek.com` (firemní účet)
- **Console:** [console.cloud.google.com](https://console.cloud.google.com) → projekt jervis-e3bd4

## OAuth 2.0 Client ID (Web application)

- **Název:** Jervis MCP OAuth
- **Client ID:** stored in K8s secret `jervis-secrets` → key `GOOGLE_CLIENT_ID`
- **Client Secret:** stored in K8s secret `jervis-secrets` → key `GOOGLE_CLIENT_SECRET`
- **Authorized redirect URI:** `https://jervis-mcp.damek-soft.eu/oauth/callback`
- **Application type:** Web application

## OAuth Consent Screen

- **User type:** External (Testing mode)
- **Test users:** `damekjan74@gmail.com`
- **Scopes:** `openid`, `email`, `profile`

## K8s Secrets (namespace: jervis)

Secret `jervis-secrets` obsahuje:
- `GOOGLE_CLIENT_ID` → GCP OAuth Client ID (Web application)
- `GOOGLE_CLIENT_SECRET` → GCP OAuth Client Secret

## Environment Variables (MCP Service)

| Proměnná | Zdroj |
|----------|-------|
| `MCP_GOOGLE_CLIENT_ID` | K8s secret `GOOGLE_CLIENT_ID` |
| `MCP_GOOGLE_CLIENT_SECRET` | K8s secret `GOOGLE_CLIENT_SECRET` |
| `MCP_OAUTH_ISSUER` | `https://jervis-mcp.damek-soft.eu` (hardcoded in deployment) |
| `MCP_OAUTH_ALLOWED_EMAILS` | `damekjan74@gmail.com` (hardcoded in deployment) |

## OAuth Flow Endpointy

| Endpoint | Popis |
|----------|-------|
| `GET /.well-known/oauth-authorization-server` | RFC 8414 Server Metadata |
| `POST /oauth/register` | RFC 7591 Dynamic Client Registration |
| `GET /oauth/authorize` | Redirect na Google login |
| `GET /oauth/callback` | Google callback → email whitelist → auth code |
| `POST /oauth/token` | Token exchange (PKCE S256) |

## Připojení z Claude.ai

1. Claude.ai → Settings → Connectors → Add Custom Connector
2. URL: `https://jervis-mcp.damek-soft.eu/mcp`
3. Claude detekuje OAuth → Google login → `damekjan74@gmail.com`
4. Automaticky se synchronizuje na Claude iOS/Android

## Zpětná kompatibilita

Stávající Claude Desktop konfigurace (npx mcp-remote s Bearer tokenem) funguje beze změn.
Auth middleware (`HybridTokenVerifier`) akceptuje oba typy:
1. Legacy static Bearer token (`MCP_API_TOKENS`)
2. OAuth-issued access token (1h expiry, refresh 30d)

## Implementační soubory

- `backend/service-mcp/app/oauth_provider.py` – OAuth 2.1 server
- `backend/service-mcp/app/config.py` – Settings (OAuth config)
- `backend/service-mcp/app/main.py` – HybridTokenVerifier + ASGI routing
- `k8s/app_mcp.yaml` – K8s deployment s OAuth env vars
