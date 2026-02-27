# Orchestrator bugy — 2026-02-27

## BUG 1: `get_mongo_db` neexistuje — topic_tracker a consolidation nefunkční

**Priorita**: HIGH
**Chyba**: `cannot import name 'get_mongo_db' from 'app.tools.kotlin_client'`

### Problém

`topic_tracker.py` (řádky 143-144) a `consolidation.py` (řádky 48-49, 267-268)
importují `get_mongo_db` z `kotlin_client.py`, ale tato funkce tam neexistuje.

Topic tracking a memory consolidation jsou tím zcela nefunkční (failují tiše, non-fatal).

### Řešení

Přidat `get_mongo_db()` do `app/tools/kotlin_client.py`:

```python
from pymongo import MongoClient  # nebo motor pro async

async def get_mongo_db():
    """Return MongoDB database handle for orchestrator collections."""
    from app.core.config import settings
    client = MongoClient(settings.mongodb_url)  # stejný pattern jako orchestrator.py:173
    return client.get_database()
```

Nebo lépe — použít motor (async) protože volající kód je async.

### Soubory

- `app/chat/topic_tracker.py:143` — import `get_mongo_db`
- `app/memory/consolidation.py:48,267` — import `get_mongo_db`
- `app/tools/kotlin_client.py` — chybí definice
- `app/graph/orchestrator.py:49-173` — existující MongoDB pattern (synchronní pymongo)


## BUG 2: `respond_to_user_task` — KeyError `'task_id'`

**Priorita**: MEDIUM
**Chyba**: `Chat tool respond_to_user_task failed: 'task_id'`

### Problém

LLM posílá argument `user_task_id` místo `task_id`. Handler v `handler_tools.py:164`
přistupuje k `args["task_id"]`, ale klíč neexistuje → KeyError.

### Řešení

Defensivní přístup — akceptovat oboje:

```python
async def _handle_respond_to_user_task(args, _client_id, _project_id, kotlin_client):
    task_id = args.get("task_id") or args.get("user_task_id")
    if not task_id:
        return "Error: task_id is required"
    result = await kotlin_client.respond_to_user_task(
        task_id=task_id,
        response=args["response"],
    )
    return f"User task responded: {result}"
```

### Soubory

- `app/chat/handler_tools.py:162-167` — handler
- `app/chat/tools.py:197-211` — tool definice (správně má `task_id`)


## BUG 3: `brain_create_issue` tool loop — detekce nefunguje jako stop

**Priorita**: HIGH
**Chyba**: `Tool loop detected: brain_create_issue called 6x with same args`

### Problém

Background handler detekuje tool loop (warning log), ale **nepřeruší iteraci**.
LLM opakovaně volá `brain_create_issue` se stejnými argumenty (6x za sebou,
iterace 11-16) a plýtvá GPU časem (~3 min per iterace × 6 = ~18 min).

### Řešení

Po detekci tool loop **vrátit error místo provedení toolu** a/nebo
**ukončit iterační smyčku**:

```python
if tool_loop_detected:
    # Nevolat tool znovu, vrátit chybu do LLM
    tool_result = "ERROR: Tool loop detected — this tool was already called with identical arguments. Choose a different action or finish."
    # Nebo rovnou break z iterační smyčky
```

### Soubory

- `app/graph/nodes/_helpers.py` — tool loop detection logic
- `app/background/handler.py` — iterační smyčka (iterace 11-15+)


## Poznámka: Přechodné chyby (neopravovat)

- `Correction LLM call failed: peer closed connection` — 1× při redeployi routeru, retry handled
- `LLM call failed: OllamaException - Server disconnected` — 1× při redeployi, transient
