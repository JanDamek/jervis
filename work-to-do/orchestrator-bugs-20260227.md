# Orchestrator bugy — 2026-02-27

> Všechny 3 bugy opraveny 2026-02-27.

## ~~BUG 1: `get_mongo_db` neexistuje — topic_tracker a consolidation nefunkční~~ ✅

**Fix:** Přidána `get_mongo_db()` do `kotlin_client.py` s async motor klientem.
Reuse singleton `AsyncIOMotorClient`, vrací default database z `settings.mongodb_url`.

## ~~BUG 2: `respond_to_user_task` — KeyError `'task_id'`~~ ✅

**Fix:** Handler v `handler_tools.py` nyní akceptuje jak `task_id` tak `user_task_id`.

## ~~BUG 3: `brain_create_issue` tool loop — detekce nefunguje jako stop~~ ✅

**Fix:** Dvě změny:
1. `background/handler.py`: Loop detection přesunuta PŘED tool execution.
   Při detekci se tool nespustí, vrátí se error do LLM, a `loop_break` flag
   ukončí i outer while-loop (ne jen inner for-loop).
2. `graph/nodes/respond.py`: Stejná oprava — detekce před execution.

## Poznámka: Přechodné chyby (neopravovat)

- `Correction LLM call failed: peer closed connection` — 1× při redeployi routeru, retry handled
- `LLM call failed: OllamaException - Server disconnected` — 1× při redeployi, transient
