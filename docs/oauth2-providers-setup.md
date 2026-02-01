# OAuth2 Providers Setup - Kompletní Průvodce

Tento průvodce tě provede vytvořením OAuth2 aplikací pro všechny podporované providery a jejich konfigurací v Jervis.

---

## 📋 Přehled

Jervis podporuje OAuth2 pro následující providery:
- ✅ **GitHub** - pro přístup k repozitářům, issues, pull requests
- ✅ **GitLab** - pro přístup k repozitářům, merge requests, issues
- ✅ **Bitbucket** - pro přístup k repozitářům a projektům
- ✅ **Atlassian (Jira, Confluence)** - pro přístup k issues, projektům, dokumentaci

---

## 1️⃣ GitHub OAuth App

### Vytvoření OAuth App

1. **Přejdi na GitHub Settings**:
   - Osobní účet: https://github.com/settings/developers
   - Organizace: `https://github.com/organizations/YOUR_ORG/settings/applications`

2. **Klikni na "New OAuth App"** nebo "Register a new application"

3. **Vyplň údaje**:
   ```
   Application name:     Jervis
   Homepage URL:         http://localhost:8080
   Authorization callback URL: http://localhost:8080/oauth2/callback
   ```

4. **Po vytvoření získáš**:
   - **Client ID** - zkopíruj si ho
   - Klikni na **"Generate a new client secret"**
   - **Client Secret** - zkopíruj si ho (už se nezobrazí!)

### Konfigurace v Jervis

```bash
export GITHUB_CLIENT_ID="Iv1.1234567890abcdef"
export GITHUB_CLIENT_SECRET="1234567890abcdef1234567890abcdef12345678"
```

Nebo v `application.yml`:
```yaml
jervis:
  oauth2:
    github:
      client-id: Iv1.1234567890abcdef
      client-secret: 1234567890abcdef1234567890abcdef12345678
      scopes: repo,read:user,read:org
```

### Scopes (výchozí: `repo,read:user,read:org`)

- `repo` - přístup k repozitářům (čtení i zápis)
- `read:user` - čtení základních informací o uživateli
- `read:org` - čtení informací o organizaci
- Další scopes: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps

---

## 2️⃣ GitLab OAuth App

### Vytvoření OAuth App

1. **Přejdi na GitLab Applications**:
   - Osobní účet: https://gitlab.com/-/profile/applications
   - Skupina: `https://gitlab.com/groups/YOUR_GROUP/-/settings/applications`
   - Self-hosted: `https://your-gitlab.com/-/profile/applications`

2. **Klikni na "Add new application"**

3. **Vyplň údaje**:
   ```
   Name:                 Jervis
   Redirect URI:         http://localhost:8080/oauth2/callback
   Confidential:         ✓ (zaškrtnuté)
   Scopes:
     - api                ✓
     - read_user          ✓
     - read_repository    ✓
   ```

4. **Po vytvoření získáš**:
   - **Application ID** (Client ID)
   - **Secret** (Client Secret)

### Konfigurace v Jervis

```bash
export GITLAB_CLIENT_ID="1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
export GITLAB_CLIENT_SECRET="abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
```

Nebo v `application.yml`:
```yaml
jervis:
  oauth2:
    gitlab:
      client-id: 1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef
      client-secret: abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890
      scopes: api,read_user,read_repository
```

### Scopes (výchozí: `api,read_user,read_repository`)

- `api` - plný přístup k API
- `read_user` - čtení profilu uživatele
- `read_repository` - čtení repozitářů
- Další scopes: https://docs.gitlab.com/ee/integration/oauth_provider.html#authorized-applications

---

## 3️⃣ Bitbucket OAuth Consumer

### Vytvoření OAuth Consumer

1. **Přejdi na Bitbucket OAuth**:
   - Workspace Settings: `https://bitbucket.org/YOUR_WORKSPACE/workspace/settings/oauth-consumers/new`
   - Nebo přes: Settings → OAuth consumers → Add consumer

2. **Vyplň údaje**:
   ```
   Name:                 Jervis
   Callback URL:         http://localhost:8080/oauth2/callback
   URL:                  http://localhost:8080
   Permissions:
     - Account: Read     ✓
     - Repositories: Read ✓
     - Pull requests: Read ✓
     - Issues: Read      ✓
   ```

3. **Po vytvoření získáš**:
   - **Key** (Client ID)
   - **Secret** (Client Secret)

### Konfigurace v Jervis

```bash
export BITBUCKET_CLIENT_ID="AbCdEfGhIjKlMnOpQr"
export BITBUCKET_CLIENT_SECRET="1234567890abcdef1234567890abcdef"
```

Nebo v `application.yml`:
```yaml
jervis:
  oauth2:
    bitbucket:
      client-id: AbCdEfGhIjKlMnOpQr
      client-secret: 1234567890abcdef1234567890abcdef
      scopes: repository,account
```

### Scopes (výchozí: `repository,account`)

- `account` - přístup k účtu
- `repository` - čtení repozitářů
- `pullrequest` - čtení pull requestů
- Další scopes: https://developer.atlassian.com/cloud/bitbucket/bitbucket-cloud-rest-api-scopes/

---

## 4️⃣ Atlassian OAuth 2.0 (3LO) - Jira & Confluence

### Vytvoření OAuth 2.0 App

1. **Přejdi na Atlassian Developer Console**:
   - https://developer.atlassian.com/console/myapps/

2. **Klikni na "Create" → "OAuth 2.0 integration"**

3. **Vyplň údaje**:
   ```
   App name:             Jervis
   ```

4. **Po vytvoření klikni na "Permissions"**:
   - **Jira API**:
     - `read:jira-work` - čtení issues, projektů
     - `read:jira-user` - čtení uživatelů
   - **Confluence API**:
     - `read:confluence-space.summary` - čtení prostorů
     - `read:confluence-content.all` - čtení obsahu

5. **Přejdi na "Authorization"**:
   ```
   Callback URL:         http://localhost:8080/oauth2/callback
   ```

6. **Přejdi na "Settings"** a získej:
   - **Client ID**
   - **Secret** (vygeneruj nový, pokud není)

### Konfigurace v Jervis

```bash
export ATLASSIAN_CLIENT_ID="AbCdEfGhIjKlMnOpQrStUvWxYz"
export ATLASSIAN_CLIENT_SECRET="1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
```

Nebo v `application.yml`:
```yaml
jervis:
  oauth2:
    atlassian:
      client-id: AbCdEfGhIjKlMnOpQrStUvWxYz
      client-secret: 1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef
      scopes: read:jira-work,read:jira-user,read:confluence-space.summary,read:confluence-content.all
```

### Scopes

**Jira:**
- `read:jira-work` - čtení issues, projektů, boards
- `read:jira-user` - čtení uživatelů
- `write:jira-work` - vytváření/editace issues (pokud potřebuješ)

**Confluence:**
- `read:confluence-space.summary` - čtení prostorů
- `read:confluence-content.all` - čtení stránek a obsahu
- `write:confluence-content` - vytváření/editace obsahu (pokud potřebuješ)

Další scopes: https://developer.atlassian.com/cloud/jira/platform/scopes-for-oauth-2-3LO-and-forge-apps/

---

## 🚀 Finální Konfigurace

### Kompletní Environment Variables

Pro všechny providery najednou:

```bash
# GitHub
export GITHUB_CLIENT_ID="Iv1.1234567890abcdef"
export GITHUB_CLIENT_SECRET="1234567890abcdef1234567890abcdef12345678"

# GitLab
export GITLAB_CLIENT_ID="1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
export GITLAB_CLIENT_SECRET="abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"

# Bitbucket
export BITBUCKET_CLIENT_ID="AbCdEfGhIjKlMnOpQr"
export BITBUCKET_CLIENT_SECRET="1234567890abcdef1234567890abcdef"

# Atlassian (Jira + Confluence)
export ATLASSIAN_CLIENT_ID="AbCdEfGhIjKlMnOpQrStUvWxYz"
export ATLASSIAN_CLIENT_SECRET="1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
```

### Kompletní application.yml

```yaml
jervis:
  oauth2:
    redirect-uri: http://localhost:8080/oauth2/callback
    github:
      client-id: ${GITHUB_CLIENT_ID:}
      client-secret: ${GITHUB_CLIENT_SECRET:}
      scopes: repo,read:user,read:org
    gitlab:
      client-id: ${GITLAB_CLIENT_ID:}
      client-secret: ${GITLAB_CLIENT_SECRET:}
      scopes: api,read_user,read_repository
    bitbucket:
      client-id: ${BITBUCKET_CLIENT_ID:}
      client-secret: ${BITBUCKET_CLIENT_SECRET:}
      scopes: repository,account
    atlassian:
      client-id: ${ATLASSIAN_CLIENT_ID:}
      client-secret: ${ATLASSIAN_CLIENT_SECRET:}
      scopes: read:jira-work,read:jira-user,read:confluence-space.summary,read:confluence-content.all
```

---

## 🔒 Bezpečnostní Tipy

1. **Nikdy necommituj secrets do gitu** - používej environment variables
2. **Pro produkci použij HTTPS** - změň `redirect-uri` na `https://your-domain.com/oauth2/callback`
3. **Rotuj secrets pravidelně** - minimálně každých 6 měsíců
4. **Omezte scopes** - používej pouze scopes, které opravdu potřebuješ
5. **Používej .env soubor** - pro lokální development

---

## ✅ Ověření Konfigurace

Po nastavení restartuj Jervis server a zkontroluj logy:

```bash
./gradlew :backend:server:bootRun
```

V logu by mělo být:
```
INFO  c.j.c.p.OAuth2Properties - GitHub OAuth2: configured ✓
INFO  c.j.c.p.OAuth2Properties - GitLab OAuth2: configured ✓
INFO  c.j.c.p.OAuth2Properties - Bitbucket OAuth2: configured ✓
INFO  c.j.c.p.OAuth2Properties - Atlassian OAuth2: configured ✓
```

---

## 📚 Další Zdroje

- **GitHub OAuth Apps**: https://docs.github.com/en/apps/oauth-apps
- **GitLab OAuth 2.0**: https://docs.gitlab.com/ee/api/oauth2.html
- **Bitbucket OAuth**: https://developer.atlassian.com/cloud/bitbucket/oauth-2/
- **Atlassian OAuth 2.0 (3LO)**: https://developer.atlassian.com/cloud/jira/platform/oauth-2-3lo-apps/

---

## 🐛 Troubleshooting

### "OAuth2 not configured for provider: github"
- Zkontroluj, že máš nastavené `GITHUB_CLIENT_ID` a `GITHUB_CLIENT_SECRET`
- Restartuj Jervis server

### "Invalid redirect_uri"
- URL musí **přesně odpovídat** tomu, co je nastavené v OAuth aplikaci
- Zkontroluj, že je `http://localhost:8080/oauth2/callback` (bez lomítka na konci)

### "Invalid client_id" nebo "Invalid client_secret"
- Zkontroluj, že credentials jsou správně zkopírované (bez mezer)
- Client Secret z GitHub se zobrazí jen jednou - pokud jsi ho nezkopíroval, musíš vygenerovat nový

### "Insufficient permissions" nebo "Access denied"
- Zkontroluj, že máš nastavené správné scopes
- Některé scopes vyžadují admin oprávnění v organizaci

---

**Potřebuješ pomoct?** Otevři issue na https://github.com/your-org/jervis/issues
