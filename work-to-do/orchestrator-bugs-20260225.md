# Orchestrator Bugs — nálezy z logů 2026-02-25

**Datum:** 2026-02-25
**Typ:** BUG
**Závažnost:** HIGH

## Kontext

Nálezy z logů orchestrátoru a serveru po nasazení (25.2.2025).

---

## Bug 1: save_message() — neočekávaný keyword argument 'task_id'

### Symptom
```
Failed to save background result: ChatContextAssembler.save_message() got an unexpected keyword argument 'task_id'
```

### Příčina
Background handler volá `save_message()` s parametrem `task_id`, ale `ChatContextAssembler.save_message()` tento parametr nepřijímá. Buď se signatura změnila a caller nebyl aktualizován, nebo caller předává extra argument.

### Dopad
Background task výsledky se neukládají do chat historie — uživatel nevidí výsledky backgroundu.

### Soubory k prozkoumání
- `backend/service-orchestrator/app/chat/context.py` — `ChatContextAssembler.save_message()` signatura
- `backend/service-orchestrator/app/chat/handler_background.py` — volání `save_message()`

---

## Bug 2: Tool loop — brain_search_issues + brain_update_issue

### Symptom
Orchestrátor detekuje tool loop pro `brain_search_issues` a `brain_update_issue` — LLM opakovaně volá tytéž tools v cyklu.

### Příčina
LLM se zasekne v cyklu hledání + aktualizace Jira issues. Drift detection se aktivuje ale nemusí problém zastavit včas.

### Dopad
Zbytečné API volání na Jira, spotřeba GPU tokenu, potenciálně nežádoucí změny v Jira.

### Řešení
- Zlepšit drift detection — ukončit loop dříve
- System prompt: instrukce pro brain tools — max 2 opakování stejného volání

### Soubory
- `backend/service-orchestrator/app/chat/handler_agentic.py` — drift detection
- `backend/service-orchestrator/app/chat/system_prompt.py` — brain tool instrukce

---

## Bug 3: LLM "Operation not allowed" error

### Symptom
```
{"error": {"type": "llm_call_failed", "message": "Operation not allowed"}}
```

### Příčina
Ollama vrací "Operation not allowed" — pravděpodobně:
1. Model není načtený a nelze načíst (VRAM plná)
2. Rate limiting / concurrent request limit
3. Nově přidaný security check v Ollama

### Dopad
Chat request selže bez smysluplné chybové zprávy pro uživatele.

### Řešení
- Přidat retry logiku pro tento typ chyby
- Logovat plný response body pro diagnostiku
- Zobrazit uživateli srozumitelnou chybu ("LLM je momentálně zaneprázdněn")

### Soubory
- `backend/service-orchestrator/app/llm/provider.py` — LLM volání, error handling

---

## Info: Stale connection reference (LOW priority)

### Symptom
```
Connection 6986002d8bf32b35197e2bf2 not found
```
Pro 8 zdrojů mazlusek/moneta/ufo_* projektů.

### Příčina
Projekty odkazují na connection ID, která byla smazána nebo přejmenována.

### Řešení
- Vyčistit stale reference v projektech přes UI (odebrat neexistující zdroje)
- Nebo přidat graceful handling — skip missing connections místo error logu

---

## Pořadí oprav

1. **Bug 1** (save_message task_id) — jednoduchý fix, vysoký dopad
2. **Bug 2** (tool loop) — vylepšit drift detection
3. **Bug 3** (Operation not allowed) — diagnostika + retry
4. **Info** (stale connection) — cleanup
