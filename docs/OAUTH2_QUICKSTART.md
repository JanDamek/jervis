# OAuth2 Quick Start

**Status:** Production Documentation
**Last updated:** 2026-02-04

Rychlý průvodce pro nastavení OAuth2 v Jervis.

---

## 🚀 Rychlé Odkazy na Vytvoření OAuth Apps

### GitHub
**Vytvořit OAuth App**: https://github.com/settings/developers

```bash
Application name:     Jervis
Homepage URL:         https://jervis.damek-soft.eu
Callback URL:         https://jervis.damek-soft.eu/oauth2/callback
```

Po vytvoření zkopíruj **Client ID** a **Client Secret** a vlož do Kubernetes Secret.

---

### GitLab
**Vytvořit OAuth App**: https://gitlab.com/-/profile/applications

```bash
Name:                 Jervis
Redirect URI:         https://jervis.damek-soft.eu/oauth2/callback
Scopes:               api, read_user, read_repository
```

Po vytvoření zkopíruj **Application ID** a **Secret** a vlož do Kubernetes Secret.

---

### Bitbucket
**Vytvořit OAuth Consumer**: `https://bitbucket.org/YOUR_WORKSPACE/workspace/settings/oauth-consumers/new`

```bash
Name:                 Jervis
Callback URL:         https://jervis.damek-soft.eu/oauth2/callback
Permissions:          Account: Read, Repositories: Read
```

Po vytvoření zkopíruj **Key** a **Secret** a vlož do Kubernetes Secret.

---

### Atlassian (Jira + Confluence)
**Vytvořit OAuth App**: https://developer.atlassian.com/console/myapps/

```bash
App name:             Jervis
App type:             OAuth 2.0 integration
Callback URL:         https://jervis.damek-soft.eu/oauth2/callback
Permissions:
  - Jira: read:jira-work, read:jira-user
  - Confluence: read:confluence-space.summary, read:confluence-content.all
```

Po vytvoření zkopíruj **Client ID** a **Secret** a vlož do Kubernetes Secret.

---

## ✅ Nasazení do Kubernetes

### 1. Uprav Kubernetes Secret

Vlož zkopírované credentials do `k8s/secrets/oauth2-secrets.yaml`:

```yaml
stringData:
  GITHUB_CLIENT_ID: "Iv1.1234567890abcdef"
  GITHUB_CLIENT_SECRET: "1234567890abcdef..."

  GITLAB_CLIENT_ID: "1234567890abcdef..."
  GITLAB_CLIENT_SECRET: "abcdef1234567890..."

  BITBUCKET_CLIENT_ID: "AbCdEfGhIjKlMnOpQr"
  BITBUCKET_CLIENT_SECRET: "1234567890abcdef..."

  ATLASSIAN_CLIENT_ID: "AbCdEfGhIjKlMnOpQrStUvWxYz"
  ATLASSIAN_CLIENT_SECRET: "1234567890abcdef..."
```

### 2. Aplikuj do Kubernetes

```bash
kubectl apply -f k8s/secrets/oauth2-secrets.yaml
kubectl rollout restart deployment/jervis-server -n jervis
```

### 3. Ověř nasazení

```bash
kubectl logs -f deployment/jervis-server -n jervis
```

Měl bys vidět:
```
INFO  OAuth2Properties - GitHub OAuth2: configured ✓
INFO  OAuth2Properties - GitLab OAuth2: configured ✓
INFO  OAuth2Properties - Bitbucket OAuth2: configured ✓
INFO  OAuth2Properties - Atlassian OAuth2: configured ✓
```

---

## 📖 Detailní Dokumentace

Pro detailní návod s obrázky a troubleshooting viz:
- **[OAuth2 Providers Setup Guide](oauth2-providers-setup.md)** - kompletní průvodce
- **[OAuth2 Setup](oauth2-setup.md)** - technická dokumentace

---

## 🎯 Použití v UI

Po nastavení credentials:

1. **Settings → Connections → Přidat připojení**
2. Vyber typ: **GITHUB** / **GITLAB** / **BITBUCKET** / **ATLASSIAN**
3. Zadej název
4. V sekci "Autentizace" vyber **OAUTH2**
5. Ulož
6. Klikni na tlačítko **🔐 OAuth2** → autorizuj v browseru
7. Hotovo! Connection je připravené k použití 🎉
