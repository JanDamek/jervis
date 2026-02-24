# Bug: orchestrateV2 fallback na legacy MUSÍ být odstraněn

**Severity**: MEDIUM
**Date**: 2026-02-24

## Vyřešeno
- ~~stream_url MissingFieldException~~ — pole odstraněno z StreamStartResponseDto
- ~~SSE /stream endpoint~~ — smazán z Python orchestrátoru

## Zbývá

### 1. Odstranit fallback pattern

`AgentOrchestratorService.kt:329-353` — catch block stále volá `dispatchLegacy()`:
- Řádek 332-333: v2 busy (429) → fallback na legacy
- Řádek 351-352: v2 exception → fallback na legacy

**Žádný fallback nesmí být.** Pokud v2 selže → error + task zpět do fronty.

### 2. Smazat legacy dispatch

`dispatchLegacy()` (řádek 362+) + `orchestrateStream()` v PythonOrchestratorClient
+ Python `/orchestrate/stream` endpoint — vše smazat, v2 je jediná cesta.

## Dotčené soubory

| Soubor | Změna |
|--------|-------|
| `backend/server/.../service/agent/coordinator/AgentOrchestratorService.kt` | Smazat fallback + dispatchLegacy |
| `backend/server/.../configuration/PythonOrchestratorClient.kt` | Smazat orchestrateStream |
| `backend/service-orchestrator/app/main.py` | Smazat /orchestrate/stream endpoint |
