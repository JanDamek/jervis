# Bug: orchestrateV2 selhává — fallback na legacy MUSÍ být odstraněn

**Severity**: HIGH
**Date**: 2026-02-24

## Problém

`AgentOrchestratorService.dispatchToOrchestrator()` volá `orchestrateV2()` (POST `/orchestrate/v2`),
ale Python orchestrátor nevrací pole `stream_url` → deserializace `StreamStartResponseDto` selhává.

Server pak tichým fallbackem volá `dispatchLegacy()` (POST `/orchestrate/stream`).

**Žádný fallback v aplikaci nesmí být.** Každý fallback maskuje skutečný problém a brání jeho detekci.

## Aktuální chybový řetězec

```
AgentOrchestratorService.dispatchToOrchestrator()
  → pythonOrchestratorClient.orchestrateV2(request)    # POST /orchestrate/v2
    → Python vrátí JSON BEZ stream_url
    → MissingFieldException: Field 'stream_url' is required for StreamStartResponseDto
  → catch (e: Exception) → WARN "ORCHESTRATE_V2_FAILED, falling back to legacy"
  → dispatchLegacy()                                    # POST /orchestrate/stream ← TOTO JE ŠPATNĚ
```

## Co opravit

### 1. Python `/orchestrate/v2` — vrátit `stream_url`

Endpoint musí vracet response se `stream_url` poli, nebo se musí dohodnout na novém DTO.
Zkontrolovat co Python skutečně vrací a sjednotit s Kotlin DTO.

### 2. Kotlin `StreamStartResponseDto` — sjednotit s Python response

```kotlin
// Aktuálně (PythonOrchestratorClient.kt:362)
@Serializable
data class StreamStartResponseDto(
    @SerialName("thread_id") val threadId: String,
    @SerialName("stream_url") val streamUrl: String,  // ← Python toto nevrací
)
```

Buď Python přidat `stream_url`, nebo Kotlin udělat pole nullable/optional, nebo
zrušit `stream_url` pokud se nepoužívá.

### 3. Odstranit fallback pattern

`AgentOrchestratorService.kt:329-353` — catch block nesmí volat `dispatchLegacy()`.
Pokud v2 selže, má to být ERROR a task se má vrátit do fronty (READY_FOR_GPU),
ne tiše přepnout na jinou code path.

Stejně tak `dispatchLegacy()` při 429 (řádek 332-333) — pokud je orchestrátor busy,
vrátit task do fronty, ne fallback.

### 4. Rozhodnout: v2 nebo legacy?

Pokud `/orchestrate/v2` je finální cesta → smazat `dispatchLegacy()` + `/orchestrate/stream`.
Pokud legacy je stále potřeba → opravit v2 a pak smazat legacy.
**Jedno z toho musí zmizet.**

## Dotčené soubory

| Soubor | Změna |
|--------|-------|
| `backend/server/.../service/agent/coordinator/AgentOrchestratorService.kt:329-353` | Odstranit fallback, error → retry |
| `backend/server/.../configuration/PythonOrchestratorClient.kt:275,362` | Sjednotit DTO s Python |
| `backend/service-orchestrator/app/api/routes.py` | Opravit `/orchestrate/v2` response |
