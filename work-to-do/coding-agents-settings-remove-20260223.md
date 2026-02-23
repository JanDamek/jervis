# Coding Agents: Settings screen — smazat (API klíče patří do secrets)

> **Datum:** 2026-02-23
> **Zjištěno:** UI — zadané tokeny nejsou vidět + koncepční problém
> **Rozhodnutí:** Settings screen pro Coding Agents smazat — API klíče patří do K8s secrets, ne do UI

---

## 1. BUG: Zadané tokeny nejsou vidět (P1)

**Soubor:** `shared/ui-common/.../sections/CodingAgentsSettings.kt`

Po zadání API klíče/setup tokenu v UI není vidět, že je nastaven — token se uloží, ale UI nezobrazí jeho stav korektně.

---

## 2. ROZHODNUTÍ: Celý Coding Agents settings screen smazat (P0)

### Proč smazat

1. **API klíče patří do K8s secrets** — ne do UI, ne do MongoDB
   - `ANTHROPIC_API_KEY` → K8s secret `jervis-secrets` (`app_server.yaml:115-139`)
   - `JUNIE_API_KEY` → K8s secret `jervis-secrets`
   - `CLAUDE_CODE_OAUTH_TOKEN` → K8s secret
   - Secrets se spravují přes kubectl/infrastructure, ne přes UI

2. **Aider a OpenHands nepotřebují klíče** — používají lokální Ollama
   - UI pro ně zobrazuje "Používá lokální Ollama – API klíč není potřeba" — tak proč je tam vůbec ukazovat?

3. **Duplicitní konfigurace** — K8s secrets + MongoDB override je zbytečná komplexita
   - `CodingAgentSettingsRpcImpl.kt:33-48`: Logika "MongoDB OR env var" — zbytečný fallback řetěz
   - Konfigurace agentů (provider, model) je v ConfigMap — to stačí

4. **Bezpečnostní riziko** — API klíče v MongoDB (i šifrované) jsou slabší než K8s secrets
   - K8s secrets: RBAC, encryption at rest, audit log
   - MongoDB: přístup má každý kdo má connection string

### Co smazat

| Soubor | Co |
|--------|-----|
| `shared/ui-common/.../sections/CodingAgentsSettings.kt` | UI screen (267 řádků) |
| `shared/common-dto/.../CodingAgentSettingsDto.kt` | DTOs |
| `shared/common-api/.../ICodingAgentSettingsService.kt` | RPC interface |
| `backend/server/.../rpc/CodingAgentSettingsRpcImpl.kt` | RPC impl |
| `backend/server/.../entity/CodingAgentSettingsDocument.kt` | MongoDB entity |
| `backend/server/.../repository/CodingAgentSettingsRepository.kt` | MongoDB repository |
| Menu entry `CODING_AGENTS` v sidebar | Odkaz v navigaci |

### Co ponechat

| Soubor | Co | Proč |
|--------|-----|------|
| `k8s/configmap.yaml` (coding-tools) | Provider/model konfigurace | Potřebné pro routing agentů |
| `CodingToolsProperties.kt` | Spring properties class | Čte configmap |
| K8s secrets (`jervis-secrets`) | API klíče | Jediný správný zdroj credentials |
| `backend/server/.../rpc/CodingAgentSettingsRpcImpl.kt` interní metody | `getApiKey()`, `getSetupToken()` | Backend potřebuje číst klíče — ale jen z env vars, ne z MongoDB |

### Migrace interních metod

`CodingAgentSettingsRpcImpl.getApiKey()` a `getSetupToken()` (řádky 92-104) čtou z MongoDB. Po smazání UI:
- Přesunout do utility class
- Číst **jen** z environment variables (K8s secrets)
- Smazat MongoDB fallback

---

## 3. Relevantní soubory

| Soubor | Řádky | Co |
|--------|-------|----|
| `shared/ui-common/.../sections/CodingAgentsSettings.kt` | celý | UI screen — SMAZAT |
| `shared/common-dto/.../CodingAgentSettingsDto.kt` | celý | DTOs — SMAZAT |
| `shared/common-api/.../ICodingAgentSettingsService.kt` | celý | RPC interface — SMAZAT |
| `backend/server/.../rpc/CodingAgentSettingsRpcImpl.kt` | celý | RPC impl — SMAZAT (přesunout getApiKey do utility) |
| `backend/server/.../entity/CodingAgentSettingsDocument.kt` | celý | MongoDB entity — SMAZAT |
| `backend/server/.../repository/CodingAgentSettingsRepository.kt` | celý | Repository — SMAZAT |
| `k8s/app_server.yaml` | 115-139 | K8s secrets mount — PONECHAT |
| `k8s/configmap.yaml` | 103-125 | Agent provider/model config — PONECHAT |
| `backend/server/.../configuration/CodingToolsProperties.kt` | celý | Spring properties — PONECHAT |
| `docs/architecture.md` | §12.4 | Credential management — AKTUALIZOVAT |
