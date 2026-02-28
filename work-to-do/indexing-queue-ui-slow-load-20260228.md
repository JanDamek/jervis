# UI — fronta indexace se načítá extrémně dlouho

**Priorita**: MEDIUM
**Status**: DONE

---

## Problém

Obrazovka "Indexace" (fronta indexace KB) se načítá velmi dlouho. UI zůstává na loading spinneru desítky sekund než se zobrazí data.

## Potvrzená příčina (2026-03-01)

### 1. KB Python API nemá offset/paginaci

`llm_extraction_queue.py:451-473` — `list_queue(limit)` vrací jen prvních N položek, nemá `offset`:
```python
async def list_queue(self, limit: int = 100) -> list[dict]:
    rows = conn.execute("""
        SELECT ... FROM tasks
        WHERE status IN ('pending', 'in_progress')
        ORDER BY ... LIMIT ?
    """, (limit,)).fetchall()
```
SQLite má indexy na `status, priority, created_at` — query samotná je rychlá.

### 2. Kotlin RPC hardcoduje limit=200 a paginuje v paměti

`IndexingQueueRpcImpl.kt:246`:
```kotlin
val kbQueueResponse = kbClient.getExtractionQueue(limit = 200)
```

Řádky 269-270 — in-memory paginace:
```kotlin
val start = safePage * safePageSize
val paged = kbWaitingAll.subList(start, ...)
```

S 1247+ pending položkami: stáhne 200 do paměti, ale UI zobrazuje "1247 total" (ze stats endpointu). Paginace nefunguje za 200. položku.

### 3. Dashboard load stahuje QUALIFYING tasks bez limitu

`IndexingQueueRpcImpl.kt:442-449` — `collectPipelineTasks()` načte VŠECHNY qualifying tasky do paměti. Se stovkami tasků může být pomalé.

### Pozitivní nálezy

- UI paginace (pageSize=20) je správně implementovaná s lazy load
- DONE tasky používají MongoDB skip/limit (správná DB-level paginace)
- Reference data (connections/clients/projects) cachovaná 45s TTL
- KB stats endpoint vrací přesný `COUNT(*)` (řádky 349-379)

## Řešení

### 1. Přidat offset do KB Python API

```python
async def list_queue(self, limit: int = 100, offset: int = 0) -> list[dict]:
    rows = conn.execute("""
        SELECT ... FROM tasks
        WHERE status IN ('pending', 'in_progress')
        ORDER BY ...
        LIMIT ? OFFSET ?
    """, (limit, offset)).fetchall()
```

### 2. Kotlin RPC — předat offset do KB API

```kotlin
val kbQueueResponse = kbClient.getExtractionQueue(
    limit = pageSize,
    offset = page * pageSize,
)
```

### 3. QUALIFYING tasks — přidat limit

```kotlin
fun collectPipelineTasks(limit: Int = 100): List<TaskDto> { ... }
```

## Soubory

- `backend/service-knowledgebase/app/services/llm_extraction_queue.py` — přidat offset parametr
- `backend/service-knowledgebase/app/api/routes.py` — předat offset z HTTP query
- `backend/server/.../rpc/IndexingQueueRpcImpl.kt` — DB-level paginace místo in-memory
- `backend/server/.../service/kb/KnowledgeServiceRestClient.kt` — přidat offset do HTTP callu

## Ověření

1. Otevřít Indexace → data se zobrazí do 2-3 sekund
2. S velkým množstvím položek (1000+) → první stránka rychle, zbytek lazy load
3. Scrollovat za 200. položku → stále funkční paginace
