# Bug: GitLab expirovaný token — zbývající UX práce

**Severity**: LOW (crash opraven, zbývá UX)
**Date**: 2026-02-23

## Vyřešeno (c4b90aac)
- ~~UI crash~~ — `checkProviderResponse()` přidáno na všechny `GitLabClient` metody
- ~~CancellationException~~ — re-throw v ClientEditForm, ProjectEditForm, ProjectGroupEditForm

## Zbývá

### AUTH_EXPIRED stav na připojení
- Při 401 v `ConnectionRpcImpl.listAvailableResources` aktualizovat `ConnectionDocument.state`
  na nový stav `AUTH_EXPIRED`
- V UI zobrazit badge/warning na connection kartě: "Token expiroval — obnovte v nastavení připojení"
- Uživatel řeší v nastavení připojení (Connections), NE v nastavení klienta

### Dotčené soubory
| Soubor | Změna |
|--------|-------|
| `backend/.../rpc/ConnectionRpcImpl.kt:216-246` | Při catch ProviderAuthException(401) → update state |
| `backend/.../entity/connection/ConnectionDocument.kt` | Nový stav AUTH_EXPIRED |
| `shared/.../settings/sections/ConnectionsSettings.kt` | Badge pro AUTH_EXPIRED |
