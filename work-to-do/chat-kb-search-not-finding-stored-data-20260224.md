# Bug: Chat kb_search nenajde data uložená přes MCP kb_store

**Severity**: CRITICAL
**Date**: 2026-02-24

## Root Cause (ověřeno z logů KB read service)

**`handler_agentic.py:297` — po `switch_context` se tool scope neaktualizuje.**

Chat začal v Commerzbank/bms (`68a330231b04695a243e5adb`/`6915dcab2335684eaa4f1862`).
Uživatel napsal "jdeme na nufo" → `switch_context` → `effective_client_id` aktualizováno
na MMB/nUFO (`68a2df3e178dfe5b0f74a5a8`/`6899c2575ec8291c20f1a038`).

Ale následný `kb_search` volá:
```python
result = await execute_chat_tool(
    tool_name, arguments,
    request.active_client_id, request.active_project_id,  # ← STALE scope!
)
```

Místo:
```python
result = await execute_chat_tool(
    tool_name, arguments,
    effective_client_id, effective_project_id,  # ← CORRECT scope
)
```

**Důkaz z KB read logů:**
```
# switch_context v 13:33:58 → resolved MMB/nUFO (68a2df3e.../6899c257...)
# kb_search v 13:34:09 → hledá stále v Commerzbank (68a33023.../6915dcab...)

RETRIEVE_START query='nUFO tracing error Invalid traceId'
  clientId=68a330231b04695a243e5adb    ← WRONG (Commerzbank)
  projectId=6915dcab2335684eaa4f1862   ← WRONG (bms)
```

Data v KB EXISTUJÍ a jsou správně scopovaná na MMB/nUFO — ověřeno MCP search s plnými ID.

## Oprava

### A. Jednořádková oprava (KRITICKÁ)

`handler_agentic.py:297` — změnit `request.active_*` na `effective_*`:

```python
# PŘED:
result = await execute_chat_tool(
    tool_name, arguments,
    request.active_client_id, request.active_project_id,
)

# PO:
result = await execute_chat_tool(
    tool_name, arguments,
    effective_client_id, effective_project_id,
)
```

### B. Sémantická vzdálenost (sekundární problém)

I po opravě scope: chat hledal "nUFO tracing error Invalid traceId" —
termy, které v uložených bug reportech NEJSOU. Uložené bugy mluví o
"setSequence", "AtomicLong", "getSessionNoLock". LLM si "traceId" domyslel
(v nUFO se používá "sessionId"). S `minConfidence=0.5` výsledky projdou
(score ~0.54 pro meeting notes), ale relevance je nízká.

**Doporučení**: Snížit `minConfidence` na 0.3 v `executor.py:440`.
Nechat LLM rozhodnout relevanci z výsledků.

### C. Stejný bug v handler_decompose.py:354

`handler_decompose.py:354` — stejný pattern, `request.active_client_id` místo effective:

```python
result = await execute_chat_tool(
    tool_name, arguments,
    request.active_client_id, request.active_project_id,  # ← STALE
)
```

Decompose se volá před agentic loopem, takže `switch_context` ještě neproběhl.
Ale `switch_context` je v decompose explicitně ignorován (řádek 349-350), takže
pokud by se někdy decompose volal po switch_context, tool calls by jely ve špatném scope.

Oprava: předávat effective scope i sem, nebo zajistit, že `process_sub_topic`
přijímá explicitní `client_id`/`project_id` parametry místo celého `request`.

### D. Logging (diagnostika)

Přidat log do `executor.py` při kb_search: query, clientId, projectId, počet výsledků.

## Dotčené soubory

| Soubor | Změna |
|--------|-------|
| `backend/service-orchestrator/app/chat/handler_agentic.py:297` | **KRITICKÁ**: `request.active_*` → `effective_*` |
| `backend/service-orchestrator/app/chat/handler_decompose.py:354` | **KRITICKÁ**: stejný scope bug |
| `backend/service-orchestrator/app/tools/executor.py:440` | Snížit minConfidence 0.5 → 0.3 |
| `backend/service-orchestrator/app/tools/executor.py` | Přidat logging kb_search params + results |
