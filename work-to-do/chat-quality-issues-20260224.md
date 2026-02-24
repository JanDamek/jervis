# Chat Quality Issues — Halucinace z historie, neautorizované akce, chybějící KB self-correction

**Datum:** 2026-02-24
**Typ:** BUG / FEATURE
**Závažnost:** HIGH

## Kontext

Po opravě scope bugu (effective_client_id) chat sice hledá ve správném scope, ale odpovědi jsou stále špatné kvůli třem nezávislým problémům.

---

## ~~Bug 1: Halucinace z chat historie~~ ✅ OPRAVENO

Opraveno:
- **system_prompt.py**: Přidána sekce "KRITICKÁ DISTANCE K HISTORII" — LLM nesmí přebírat termíny/fakta ze souhrnů bez ověření přes tools.
- **context.py**: Souhrny označeny prefixem `[Neověřený souhrn]`, celá sekce souhrnů má varování o nepřesnostech.
- **guidelines.md**: Zdokumentována sekce "Chat Context Quality" s best practices.
- **orchestrator-detailed.md**: Nová sekce 16.4 "Summary trust level".

---

## ~~Bug 2: create_background_task bez souhlasu uživatele~~ ✅ OPRAVENO

Opraveno v `0dae82a9` — system prompt nyní vyžaduje explicitní souhlas uživatele před vytvořením background tasku. Nasazeno.

---

## ~~Feature 3: KB self-correction — smazání špatných dat~~ ✅ OPRAVENO

Opraveno:
- **definitions.py**: Nový `TOOL_KB_DELETE` — OpenAI function-calling schema pro mazání KB záznamů podle sourceUrn.
- **executor.py**: `_execute_kb_delete()` — volá KB write service `POST /purge` endpoint s `sourceUrn` + `clientId`.
- **tools.py**: `kb_delete` registrován v CORE kategorii (vždy dostupný), přidán do `CHAT_TOOLS` a `TOOL_DOMAINS`.
- **system_prompt.py**: Nová sekce "Self-correction" s pravidly pro mazání špatných dat.
- `kb_search` již vrací `sourceUrn` ve výsledcích — LLM ho může použít pro `kb_delete`.

---

## Pořadí implementace

1. ~~**Bug 2** (system prompt — background task pravidla)~~ ✅ HOTOVO
2. ~~**Bug 1** (system prompt + context — označení historie)~~ ✅ HOTOVO
3. ~~**Feature 3** (kb_delete tool)~~ ✅ HOTOVO

## Ověření

- **Bug 1**: Chat se zeptá na něco co dříve halucinoval → místo opakování halucinace ověří přes tool
- **Bug 2**: Chat dostane dotaz → NEvytvoří background task pokud o to user nepožádal
- **Feature 3**: User řekne "ta informace byla špatná" → chat ji najde v KB a smaže
