# Orchestrator Bugs — nálezy z logů 2026-02-25

**Datum:** 2026-02-25
**Typ:** BUG
**Závažnost:** HIGH

## Kontext

Nálezy z logů orchestrátoru a serveru po nasazení (25.2.2025).

---

## ~~Bug 1: save_message() — neočekávaný keyword argument 'task_id'~~ ✅ OPRAVENO

Opraveno: 3 callers používaly `task_id=` místo správného `conversation_id=`:
- `background/handler.py` (background task result saving)
- `graph/nodes/respond.py` (user message + assistant message saving)

---

## ~~Bug 2: Tool loop — brain_search_issues + brain_update_issue~~ ✅ OPRAVENO

Opraveno:
- **handler_agentic.py**: Nový Signal 3 v drift detection — detekuje alternující tool pair pattern (A→B→A→B) kde A != B.
- **system_prompt.py**: Přidána instrukce pro brain tools — max 1 hledání + 1 akce, pak odpověz uživateli.

---

## ~~Bug 3: LLM "Operation not allowed" error~~ ✅ OPRAVENO

Opraveno:
- **provider.py**: Nová metoda `_call_with_retry()` s exponential backoff (2s, 4s) pro transient errory:
  - Connection errors (OSError, ConnectionError)
  - "Operation not allowed" (Ollama VRAM pressure)
  - HTTP 503 (service unavailable) a 429 (rate limit)
- Non-retryable errory propagují okamžitě.
- Aplikováno na streaming i blocking completion paths.

---

## ~~Info: Stale connection reference~~ ✅ OPRAVENO

Opraveno:
- **BugTrackerContinuousIndexer.kt**: `throw IllegalStateException` → skip + warn log
- **WikiContinuousIndexer.kt**: `throw IllegalStateException` → skip + warn log
- Chybějící connection nyní nepřeruší celý indexing batch — jen přeskočí dokument.

---

## Pořadí oprav

1. ~~**Bug 1** (save_message task_id)~~ ✅ HOTOVO
2. ~~**Bug 2** (tool loop)~~ ✅ HOTOVO
3. ~~**Bug 3** (Operation not allowed)~~ ✅ HOTOVO
4. ~~**Info** (stale connection)~~ ✅ HOTOVO
