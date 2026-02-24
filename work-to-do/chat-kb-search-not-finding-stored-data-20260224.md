# Bug: Chat kb_search nenajde data uložená přes MCP kb_store

**Severity**: HIGH
**Date**: 2026-02-24

## Popis

MCP `kb_store` uložil 2 detailní bug reporty o tracing problémech v nUFO projektu
(clientId=`68a2df3e`, projectId=`6899c257`). MCP vrátil "Stored successfully."

Chat pak hledal `kb_search("nUFO tracing error Invalid traceId")` ale nic nenašel.
Po 3× kb_search drift detection zastavil loop a chat odpověděl bez nalezených dat.

## Uložená data (přes MCP kb_store)

- "BUG: Reset tracing sequence v getSessionNoLock" — SessionManagementSqlBean, AtomicLong, setSequence
- "BUG: DuplicateKeyException handler zahazuje celý chunk" — StorageService.kt, insertMany

## Search queries (chat kb_search)

1. `"nUFO tracing error Invalid traceId"`
2. `"nUFO ufo-core module tracing"`
3. `"nUFO tracing error traceId spanId"`
4. `"nUFO ufo-core module tracing error IllegalArgumentException"`

**Poznámka**: "traceId" se v nUFO tracingu vůbec nepoužívá — skutečný termín je "sessionId".
LLM si "traceId" domyslel (intuice). Tím je sémantická vzdálenost ještě větší.

## Možné příčiny

### 1. Sémantická vzdálenost (LIKELY)
Queries obsahují "Invalid traceId", "IllegalArgumentException" — termy, které v uložených
bug reportech NEJSOU. Uložené bugy mluví o "setSequence", "AtomicLong", "getSessionNoLock".
Embedding model nemusí propojit "tracing error" se "Reset tracing sequence".

**Ověření**: Zavolat kb_search s query "setSequence tracing sequence reset" → mělo by najít.

### 2. Executor nepředává scope (TO VERIFY)
Chat executor (`executor.py`) volá KB API s clientId/projectId.
Potřeba ověřit, že se tyto hodnoty skutečně předávají z chat kontextu.

**Ověření**: Přidat logging do executoru — logovat clientId/projectId při kb_search volání.

### 3. minConfidence filtr (POSSIBLE)
KB retrieve API má `minConfidence=0.5`. Výsledky mohou existovat ale s nižším skóre.

**Ověření**: Snížit minConfidence na 0.1 a zkusit znovu.

### 4. Weaviate indexing delay (RULED OUT)
Data do KB byla vložena před několika dny — dávno zaindexovaná.

## Doporučená oprava

### A. Logging v chat executoru
Přidat log do `executor.py` při kb_search: query, clientId, projectId, počet výsledků.
Umožní rychlou diagnostiku scope problémů.

### B. Fallback na širší search
Pokud kb_search vrátí 0 výsledků s projectId, zkusit znovu jen s clientId (bez projectId).
Některá data mohou být uložena na úrovni klienta.

### C. Snížit minConfidence pro první pokus
0.5 je dost vysoký práh pro sémantické vyhledávání. Zkusit 0.3 a nechat LLM
rozhodnout relevanci z výsledků.

## Dotčené soubory

| Soubor | Změna |
|--------|-------|
| `backend/service-orchestrator/app/tools/executor.py` | Logging kb_search params + results, scope fallback |
| `backend/service-orchestrator/app/chat/handler_agentic.py` | Případně retry s širším scope |
