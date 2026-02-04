# OAuth2 Setup for GitHub, GitLab, and Bitbucket

Poslední aktualizace: 2026-02-03

Tento dokument popisuje, jak nastavit OAuth2 připojení pro GitHub, GitLab a Bitbucket v aplikaci Jervis.

---

## Přehled OAuth2 Flow

1. **Uživatel vytvoří Connection** v UI (typu OAuth2)
2. **Uživatel klikne na "Připojit přes OAuth2"**
3. **Otevře se browser** s authorization page poskytovatele (GitHub/GitLab/Bitbucket)
4. **Uživatel povolí přístup** pro aplikaci
5. **Provider přesměruje zpět** do Jervis s authorization code
6. **Jervis vymění code za access token** a uloží ho do connection
7. **Connection je připraven k použití**

---

## Nastavení OAuth2 aplikací

### GitHub

1. Přejděte na https://github.com/settings/developers
2. Klikněte na "New OAuth App"
3. Vyplňte:
   - **Application name**: `Jervis` (nebo vlastní název)
   - **Homepage URL**: `http://localhost:8080` (nebo vaše doména)
   - **Authorization callback URL**: `http://localhost:8080/oauth2/callback`
4. Po vytvoření získáte:
   - **Client ID** - zkopírujte do Connection nastavení
   - **Client Secret** - vygenerujte a zkopírujte do Connection nastavení

**Doporučené scope:**
- `repo` - přístup k repositories
- `read:user` - čtení uživatelských informací
- `read:org` - čtení organizačních informací

### GitLab

1. Přejděte na https://gitlab.com/-/profile/applications
2. Klikněte na "Add new application"
3. Vyplňte:
   - **Name**: `Jervis`
   - **Redirect URI**: `http://localhost:8080/oauth2/callback`
   - **Scopes**: Zaškrtněte:
     - `api` - full API access
     - `read_user` - read user information
     - `read_repository` - read repositories
4. Po vytvoření získáte:
   - **Application ID** - zkopírujte jako Client ID
   - **Secret** - zkopírujte jako Client Secret

### Bitbucket (Atlassian)

1. Přejděte na https://bitbucket.org/account/settings/oauth-consumers/
2. Klikněte na "Add consumer"
3. Vyplňte:
   - **Name**: `Jervis`
   - **Callback URL**: `http://localhost:8080/api/oauth2/callback`
   - **Permissions**: Zaškrtněte:
     - `Account: Read`
     - `Repositories: Read, Write`
     - `Pull requests: Read, Write`
     - `Issues: Read, Write`
4. Po vytvoření získáte:
   - **Key** - zkopírujte jako Client ID
   - **Secret** - zkopírujte jako Client Secret

---

## Konfigurace OAuth2 v Jervis (aplikační konfigurace)

OAuth2 credentials (Client ID a Secret) jsou **globální pro celou Jervis aplikaci**, ne per-connection.

### Nastavení Environment Variables

Nastavte následující environment variables:

```bash
# GitHub OAuth2
export GITHUB_CLIENT_ID="your_github_client_id"
export GITHUB_CLIENT_SECRET="your_github_client_secret"

# GitLab OAuth2
export GITLAB_CLIENT_ID="your_gitlab_client_id"
export GITLAB_CLIENT_SECRET="your_gitlab_client_secret"

# Bitbucket OAuth2
export BITBUCKET_CLIENT_ID="your_bitbucket_client_id"
export BITBUCKET_CLIENT_SECRET="your_bitbucket_client_secret"
```

Nebo upravte `backend/server/src/main/resources/application.yml`:

```yaml
jervis:
  oauth2:
    redirect-uri: http://localhost:8080/oauth2/callback
    github:
      client-id: your_github_client_id
      client-secret: your_github_client_secret
      scopes: repo,read:user,read:org
    gitlab:
      client-id: your_gitlab_client_id
      client-secret: your_gitlab_client_secret
      scopes: api,read_user,read_repository
    bitbucket:
      client-id: your_bitbucket_client_id
      client-secret: your_bitbucket_client_secret
      scopes: repository,account
```

---

## Použití v UI

### 1. Vytvoření OAuth2 Connection

V **Settings → Connections**:

1. Klikněte na **"Přidat připojení"**
2. Vyberte typ: **OAUTH2**
3. Zadejte **název** connection (např. "GitHub Organization")
4. Vyplňte **Base URL** (např. `https://api.github.com`)
5. Vyplňte OAuth2 specifická pole:
   - **Authorization URL**: URL pro zahájení autorizace (např. `https://github.com/login/oauth/authorize`)
   - **Token URL**: URL pro výměnu kódu za token (např. `https://github.com/login/oauth/access_token`)
   - **Client Secret**: Secret získaný z nastavení poskytovatele
   - **Redirect URI**: Musí odpovídat nastavení u poskytovatele (např. `http://localhost:8080/oauth2/callback`)
   - **Scope**: Volitelné scopes oddělené mezerou (např. `repo read:user`)
6. Klikněte **"Vytvořit"**

Connection je vytvořené ve stavu `NEW` (není ještě autorizované).

**Poznámka**: Ačkoliv lze OAuth2 konfigurovat globálně v `application.yml`, UI nyní umožňuje plnou konfiguraci per-connection pro maximální flexibilitu.

### 2. OAuth2 Autorizace

Po vytvoření OAuth2 connection se zobrazí tlačítko **"🔐 OAuth2"**:

1. V seznamu připojení najděte nově vytvořené connection
2. Klikněte na tlačítko **"🔐 OAuth2"**
3. Automaticky se otevře prohlížeč s autorizační stránkou (GitHub/GitLab/Bitbucket)
4. Přihlaste se svým účtem (pokud ještě nejste)
5. Povolte přístup aplikaci Jervis k požadovaným scopům
6. Po úspěchu se zobrazí potvrzení a okno se za 2s automaticky zavře
7. Connection status se změní na `VALID` - je připravené k použití

### 3. Přiřazení Connection ke klientovi

V **Settings → Clients**:

1. Vyberte nebo vytvořte klienta
2. V sekci "Připojení klienta" přiřaďte vytvořený Connection
3. Všechny projekty tohoto klienta nyní mohou používat tento Connection
4. V projektech pak vyberete konkrétní repository, Jira project, atd. z tohoto Connection

---

## Technické detaily

### Backend Endpoints

- `GET /oauth2/authorize/{connectionId}` - iniciuje OAuth2 flow
  - Path param: `connectionId` - ID connection dokumentu
  - Response: HTTP 302 redirect na authorization URL poskytovatele

- `GET /oauth2/callback` - callback endpoint
  - Query params: `code`, `state`
  - Response: HTML stránka s potvrzením

### Security Considerations

- **State parameter**: Používá se UUID pro ochranu proti CSRF
- **Plaintext credentials**: V dev módu jsou Client ID a Secret uloženy v plaintext (OK pro interní použití)
- **Token storage**: Access token je uložen v MongoDB v plaintext (development mode)

### Refresh Tokens

Pro refresh tokenů (když vyprší access token):
- GitHub: Nepodporuje refresh tokeny, access tokeny nevypršují
- GitLab: Podporuje refresh tokeny (automaticky uložen)
- Bitbucket: Podporuje refresh tokeny (automaticky uložen)

---

## Troubleshooting

### "Invalid redirect URI"
- Zkontrolujte, že redirect URI v OAuth2 aplikaci přesně odpovídá `http://localhost:8080/oauth2/callback`
- Pro produkci změňte na vaši doménu

### "Invalid state"
- State může expirovat nebo být už použit
- Zkuste znovu kliknout na "Připojit"

### "Authorization failed"
- Zkontrolujte Client ID a Client Secret
- Zkontrolujte, že máte správné scope

---

## Příklad: GitHub Connection

```kotlin
// Connection Configuration
{
  "type": "OAUTH2",
  "name": "GitHub MyOrg",
  "gitProvider": "GITHUB",
  "credentials": {
    "clientId": "Iv1.a1b2c3d4e5f6g7h8",
    "clientSecret": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0",
    "redirectUri": "http://localhost:8080/oauth2/callback",
    "scope": "repo,read:user,read:org"
  }
}
```

Po autorizaci:
```kotlin
{
  "credentials": {
    // ... original fields ...
    "accessToken": "ghp_1234567890abcdefghijklmnopqrstuvwxyz",
    "tokenType": "Bearer"
  },
  "state": "CONNECTED"
}
```
