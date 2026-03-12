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
- **Authorized redirect URIs:**
  - `https://jervis-mcp.damek-soft.eu/oauth/callback` (MCP OAuth 2.1)
  - `https://jervis.damek-soft.eu/oauth2/callback` (Gmail IMAP OAuth2)
- **Application type:** Web application

## OAuth Consent Screen

- **User type:** External (Testing mode)
- **Test users:** `damekjan74@gmail.com`
- **Scopes:** `openid`, `email`, `profile`, `https://mail.google.com/` (Gmail IMAP)

## K8s Secrets (namespace: jervis)

Secret `jervis-secrets` obsahuje:
- `GOOGLE_CLIENT_ID` → GCP OAuth Client ID (Web application)
- `GOOGLE_CLIENT_SECRET` → GCP OAuth Client Secret

## Gmail IMAP OAuth2 (Kotlin Server)

Sdílí stejný GCP OAuth client jako MCP. Server čte credentials z `jervis-secrets` secret.

### Kroky k aktivaci

1. Otevři [GCP Credentials](https://console.cloud.google.com/apis/credentials?project=jervis-e3bd4)
2. Klikni na OAuth client **"Jervis MCP OAuth"**
3. V sekci **"Authorized redirect URIs"** klikni **"ADD URI"**
4. Vlož: `https://jervis.damek-soft.eu/oauth2/callback`
5. Klikni **"SAVE"**
6. Otevři [OAuth Consent Screen](https://console.cloud.google.com/apis/credentials/consent?project=jervis-e3bd4)
7. Klikni **"EDIT APP"** → pokračuj na krok **"Scopes"**
8. Klikni **"ADD OR REMOVE SCOPES"**
9. Do filtru napiš `mail.google.com` a zaškrtni scope `https://mail.google.com/`
10. Klikni **"UPDATE"** → **"SAVE AND CONTINUE"**

K8s secrets jsou sdílené — žádná další konfigurace není potřeba.

## Microsoft Teams OAuth2 (Azure Entra ID)

### Kroky k vytvoření aplikace

1. Otevři [Azure App Registrations](https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade)
2. Klikni **"New registration"**
3. Vyplň formulář:
   - **Name:** `Jervis`
   - **Supported account types:** zaškrtni **"Accounts in any organizational directory (Any Microsoft Entra ID tenant - Multitenant)"**
   - **Redirect URI:** vyber **"Web"** a vlož `https://jervis.damek-soft.eu/oauth2/callback`
4. Klikni **"Register"**
5. Na stránce nově vytvořené aplikace zkopíruj **"Application (client) ID"** z horního přehledu

### Přidání API permissions

6. V levém menu rozbal **"Manage"** → klikni **"API permissions"**
7. Klikni **"Add a permission"** → **"Microsoft Graph"** → **"Delegated permissions"**
8. Vyhledej a zaškrtni tyto permissions (všechny Delegated, žádné nevyžadují admin consent):
   - `User.Read`
   - `Chat.Read`
   - `Chat.ReadWrite`
   - `ChannelMessage.Send`
   - `Calendars.Read`
   - `Calendars.ReadWrite`
   - `Mail.Read`
   - `Mail.Send`
   - `Files.ReadWrite.All`
   - `Contacts.Read`
   - `People.Read`
   - `Sites.Read.All`
   - `offline_access`
9. Klikni **"Add permissions"**

### Vytvoření client secret

10. V levém menu klikni **"Certificates & secrets"**
11. Klikni **"New client secret"**
12. **Description:** `Jervis production`, **Expires:** 24 months
13. Klikni **"Add"**
14. **IHNED** zkopíruj **"Value"** (ne "Secret ID"!) — po opuštění stránky už nebude viditelný

### Uložení do K8s

15. Spusť na serveru:
```bash
kubectl -n jervis patch secret jervis-oauth2-secrets --type merge -p \
  '{"stringData":{"MICROSOFT_CLIENT_ID":"<application-client-id-z-kroku-5>","MICROSOFT_CLIENT_SECRET":"<value-z-kroku-14>"}}'
```

16. Restartuj server:
```bash
kubectl -n jervis rollout restart deployment jervis-server
```

### Aktuální hodnoty (2026-03-12)
- **App name:** J.E.R.V.I.S.
- **Application (client) ID:** `5888a141-1b61-4a9b-8cdc-cb15ee44b365`
- **Directory (tenant) ID:** `82c2ae9c-560c-40e1-be70-7d0c9fcb6388`
- **Client Secret Description:** JERVIS Productions
- **Client Secret Expiry:** 2028-03-11

### Poznámky
- Multi-tenant = funguje pro jakýkoli M365 tenant (klientské firmy)
- `ChannelMessage.Read.All` záměrně NEPŘIDÁNO — vyžaduje admin consent, klienti to neschválí
- Secret expiruje 2028-03-11 — nastavit reminder

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
