# OAuth2 Secrets Setup

## 📋 Před nasazením - Vytvoř OAuth2 aplikace

Pro každý provider vytvoř OAuth2 aplikaci s těmito údaji:

**Callback URL (pro všechny)**: `https://jervis.damek-soft.eu/oauth2/callback`

### 1. GitHub OAuth App
🔗 https://github.com/settings/developers

```
Application name:     Jervis
Homepage URL:         https://jervis.damek-soft.eu
Callback URL:         https://jervis.damek-soft.eu/oauth2/callback
```

Zkopíruj **Client ID** a **Client Secret**.

### 2. GitLab OAuth App
🔗 https://gitlab.com/-/profile/applications

```
Name:                 Jervis
Redirect URI:         https://jervis.damek-soft.eu/oauth2/callback
Confidential:         ✓
Scopes:               api, read_user, read_repository
```

Zkopíruj **Application ID** a **Secret**.

### 3. Bitbucket OAuth Consumer
🔗 https://bitbucket.org/YOUR_WORKSPACE/workspace/settings/oauth-consumers/new

```
Name:                 Jervis
Callback URL:         https://jervis.damek-soft.eu/oauth2/callback
Permissions:          Account: Read, Repositories: Read
```

Zkopíruj **Key** a **Secret**.

### 4. Atlassian OAuth 2.0 App
🔗 https://developer.atlassian.com/console/myapps/

```
App name:             Jervis
App type:             OAuth 2.0 integration
Callback URL:         https://jervis.damek-soft.eu/oauth2/callback
Permissions:
  - Jira: read:jira-work, read:jira-user
  - Confluence: read:confluence-space.summary, read:confluence-content.all
```

Zkopíruj **Client ID** a **Secret**.

---

## 🚀 Nasazení do Kubernetes

### 1. Uprav `oauth2-secrets.yaml`

Nahraď `your_*_here` skutečnými hodnotami:

```yaml
stringData:
  GITHUB_CLIENT_ID: "Iv1.1234567890abcdef"
  GITHUB_CLIENT_SECRET: "1234567890abcdef1234567890abcdef12345678"

  GITLAB_CLIENT_ID: "1234567890abcdef..."
  GITLAB_CLIENT_SECRET: "abcdef1234567890..."

  BITBUCKET_CLIENT_ID: "AbCdEfGhIjKlMnOpQr"
  BITBUCKET_CLIENT_SECRET: "1234567890abcdef..."

  ATLASSIAN_CLIENT_ID: "AbCdEfGhIjKlMnOpQrStUvWxYz"
  ATLASSIAN_CLIENT_SECRET: "1234567890abcdef..."
```

### 2. Aplikuj Secret do Kubernetes

```bash
kubectl apply -f k8s/secrets/oauth2-secrets.yaml
```

### 3. Ověř, že Secret existuje

```bash
kubectl get secret jervis-oauth2-secrets -n jervis
```

### 4. Restartuj Jervis server deployment

```bash
kubectl rollout restart deployment/jervis-server -n jervis
```

### 5. Zkontroluj logy

```bash
kubectl logs -f deployment/jervis-server -n jervis
```

Měl bys vidět:
```
INFO  c.j.c.p.OAuth2Properties - GitHub OAuth2: configured ✓
INFO  c.j.c.p.OAuth2Properties - GitLab OAuth2: configured ✓
INFO  c.j.c.p.OAuth2Properties - Bitbucket OAuth2: configured ✓
INFO  c.j.c.p.OAuth2Properties - Atlassian OAuth2: configured ✓
```

---

## 🔒 Bezpečnost

- **NIKDY necommituj `oauth2-secrets.yaml` s reálnými hodnotami do gitu!**
- Secret je v `.gitignore` (nebo by měl být)
- Pro produkci používej Sealed Secrets nebo externí secret management (Vault, AWS Secrets Manager)

---

## 🛠️ Troubleshooting

### "OAuth2 not configured for provider: github"
Secret není správně načten. Zkontroluj:
```bash
kubectl describe secret jervis-oauth2-secrets -n jervis
kubectl get pods -n jervis
```

### "Invalid redirect_uri"
Zkontroluj, že v OAuth aplikaci je přesně: `https://jervis.damek-soft.eu/oauth2/callback`

### Secret update se neprojevil
Restartuj pod:
```bash
kubectl delete pod -l app=jervis-server -n jervis
```

---

## 📚 Další dokumentace

- [OAuth2 Quick Start](../../docs/OAUTH2_QUICKSTART.md)
- [OAuth2 Providers Setup Guide](../../docs/oauth2-providers-setup.md)
