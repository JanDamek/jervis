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

## Bug 5: Context overflow — num_ctx=8192 nestačí pro background tasks

### Symptom
IDLE_REVIEW task volá `brain_search_issues` → vrací velký JSON → kontext naroste na 8192 tokenů
(přesně `num_ctx` limit) → Ollama vrátí "Operation not allowed" jako **text odpovědi LLM**
(ne jako exception). Background handler to interpretuje jako "final answer" a task skončí
s nesmyslným výstupem.

### Příčina
- Background handler používá `tier=local_fast` s `num_ctx: 8192`
- 31 tool definitions + system prompt + Jira results = kontext se rychle zaplní
- Ollama při překročení num_ctx nevrací HTTP error, ale **generuje chybový JSON jako text**:
  ```json
  {"error": {"type": "llm_call_failed", "message": "{\"message\":\"Operation not allowed\"}\n"}}
  ```
- `prompt_tokens: 8192` v response potvrzuje context overflow
- Retry logika v `_call_with_retry()` tohle nezachytí — je to úspěšná LLM response, ne exception

### Dopad
- Background tasks (zejména IDLE_REVIEW) s velkými tool results selhávají tiše
- Výsledek tasku je chybový JSON místo smysluplné odpovědi

### Možná řešení
1. **Zvýšit num_ctx pro background tier** — 8192 → 16384 nebo 32768 v TIER_CONFIG
2. **Detekce context overflow v handler** — pokud response content obsahuje `"Operation not allowed"`,
   nepoužívat jako final answer ale logovat warning a ukončit task s chybou
3. **Redukce tool definitions** — pro background tasks posílat jen relevantní subset tools (ne všech 31)
4. **Truncate tool results** — omezit velikost JSON výsledků z brain_search_issues

### Soubory
- `backend/service-orchestrator/app/llm/provider.py` — TIER_CONFIG, num_ctx
- `backend/service-orchestrator/app/background/handler.py` — detekce overflow v response
- `backend/service-orchestrator/app/chat/tools.py` — tool subset pro background

---

## Pořadí oprav

1. ~~**Bug 1** (save_message task_id)~~ ✅ HOTOVO
2. ~~**Bug 2** (tool loop)~~ ✅ HOTOVO
3. ~~**Bug 3** (Operation not allowed — exception retry)~~ ✅ HOTOVO
4. ~~**Info** (stale connection)~~ ✅ HOTOVO
5. **Bug 5** (context overflow num_ctx) — OTEVŘENO
