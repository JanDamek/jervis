# Confluence: Brain create page → 401 "scope does not match"

> **Datum:** 2026-02-23 09:13
> **Zjištěno:** Server logy po restartu
> **Chyba:** `Confluence create page failed with 401: {"code":401,"message":"Unauthorized; scope does not match"}`
> **Stacktrace:** `AtlassianApiClient.createConfluencePage(AtlassianApiClient.kt:1654)`

---

## 1. Problém

Brain (orchestrátor) se pokusí vytvořit stránku v Confluence přes OAuth2 cloud gateway. Atlassian vrátí 401 s "scope does not match" — token nemá potřebný scope pro vytváření stránek.

---

## 2. Call Chain

```
BrainWriteServiceImpl.createPage() (line 207-248)
  → wikiClient.createPage(WikiCreatePageRpcRequest)
    → AtlassianServiceImpl.createPage() (line 279-294)
      → atlassianApiClient.createConfluencePage() (AtlassianApiClient.kt:1607-1731)
        → POST /api/v2/pages (cloud gateway: api.atlassian.com/ex/confluence/{cloudId}/wiki)
        → 401 "Unauthorized; scope does not match"
```

---

## 3. Root Cause: Chybějící OAuth2 scope `write:confluence-pages`

### Aktuální scopes (hardcoded na 3 místech):

```
read:jira-user read:jira-work write:jira-work
read:confluence-content.all read:confluence-content.summary read:confluence-content.permission
read:confluence-props read:confluence-space.summary read:confluence-groups read:confluence-user
write:confluence-content write:confluence-space
search:confluence readonly:content.attachment:confluence
read:space:confluence read:page:confluence read:content:confluence
read:attachment:confluence read:content.metadata:confluence
offline_access
```

**Chybí:** `write:confluence-pages` — vyžadováno Atlassian cloud gateway pro `POST /api/v2/pages`.

Stávající `write:confluence-content` nestačí — cloud gateway (OAuth2 přes `api.atlassian.com`) vyžaduje granulární scope `write:confluence-pages` specificky pro vytváření/úpravu stránek.

---

## 4. Řešení

### 4.1 Přidat `write:confluence-pages` do OAuth2 scopes (P0)

Scope je hardcoded na **3 místech** (duplikace):

| Soubor | Řádky | Co |
|--------|-------|----|
| `backend/service-atlassian/.../AtlassianProviderService.kt` | 38 | `oauth2Scopes = "..."` |
| `backend/service-atlassian/.../AtlassianApplication.kt` | 62-67 | `/oauth2/scopes` endpoint |
| `backend/server/.../OAuth2Service.kt` | 261 | Fallback scopes |

Přidat `write:confluence-pages` do všech tří definic.

### 4.2 Po změně: re-autorizace (manuální krok)

Po přidání scope musí uživatel znovu autorizovat Atlassian connection — existující tokeny nemají nový scope.

### 4.3 Bonus: deduplikovat scope definici (P2)

3× stejný string na 3 místech → single source of truth (konstanta nebo config).

---

## 5. Relevantní soubory

| Soubor | Řádky | Co |
|--------|-------|----|
| `backend/service-atlassian/.../AtlassianProviderService.kt` | 38 | OAuth2 scopes definice #1 |
| `backend/service-atlassian/.../AtlassianApplication.kt` | 62-67 | OAuth2 scopes definice #2 |
| `backend/server/.../OAuth2Service.kt` | 261 | OAuth2 scopes definice #3 (fallback) |
| `backend/service-atlassian/.../AtlassianApiClient.kt` | 1607-1731 | `createConfluencePage()` — API volání |
| `backend/server/.../BrainWriteServiceImpl.kt` | 207-248 | `createPage()` — caller s retry wrapperem |
| `backend/service-atlassian/.../AtlassianServiceImpl.kt` | 279-294 | `createPage()` — prostředník |
