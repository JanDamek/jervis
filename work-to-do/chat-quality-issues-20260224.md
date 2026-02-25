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

**Update 2026-02-25**: Tento přístup je příliš striktní. Pravidlo by mělo být SCOPE-BASED:
- Pokud uživatel na začátku konverzace povolí akci (např. "vytvoř background task"), povolení platí dokud se nezmění scope.
- Totéž platí pro všechny akce (store_knowledge, brain_create_issue, dispatch_coding_agent, ...).
- **→ Řešení: Feature 4 (chat permissions scope) — viz níže.**

---

## ~~Feature 3: KB self-correction — smazání špatných dat~~ ✅ OPRAVENO

Opraveno:
- **definitions.py**: Nový `TOOL_KB_DELETE` — OpenAI function-calling schema pro mazání KB záznamů podle sourceUrn.
- **executor.py**: `_execute_kb_delete()` — volá KB write service `POST /purge` endpoint s `sourceUrn` + `clientId`.
- **tools.py**: `kb_delete` registrován v CORE kategorii (vždy dostupný), přidán do `CHAT_TOOLS` a `TOOL_DOMAINS`.
- **system_prompt.py**: Nová sekce "Self-correction" s pravidly pro mazání špatných dat.
- `kb_search` již vrací `sourceUrn` ve výsledcích — LLM ho může použít pro `kb_delete`.

---

## Feature 4: Chat permissions scope — scope-based akční oprávnění

### Motivace
Současný přístup (Bug 2 fix) je příliš striktní — chat se VŽDY ptá na souhlas. Ale pokud uživatel řekne
"vytvoř background task pro tohle" a pak v rámci téhož scope pokračuje s další prací, měl by chat mít
povolení vytvářet background tasky bez opětovného ptaní.

### Koncept
Chat si udržuje **permissions mapu** vázanou na **aktuální scope** (client_id + project_id):

```python
# V run_agentic_loop nebo na úrovni ChatRequest
chat_permissions = {
    "scope": {"client_id": "abc", "project_id": "xyz"},
    "granted": {
        "create_background_task": True,       # user povolil
        "store_knowledge": False,             # default: zakázáno
        "dispatch_coding_agent": False,
    }
}
```

### Pravidla
1. **Default: všechny write akce vyžadují souhlas** (create_background_task, dispatch_coding_agent, store_knowledge, brain_create_issue)
2. **Po udělení souhlasu**: oprávnění platí pro daný scope (client_id + project_id)
3. **Při změně scope** (switch_context): permissions se resetují
4. **Read akce** (kb_search, brain_search, web_search, memory_recall): vždy povolené
5. **Permissions jsou viditelné v UI** (viz níže)

### Implementace

#### Python (orchestrátor)
- `handler_agentic.py`: Nová struktura `ChatPermissions` — inicializována z `ChatRequest`
- Při `switch_context` → reset permissions
- Při pokusu o write akci → kontrola permissions → pokud nemá, vrátit LLM zprávu "Zeptej se uživatele na souhlas"
- Po souhlasu uživatele → aktualizovat permissions

#### Kotlin (server) — persistence
- `ChatSession` v MongoDB: nové pole `permissions: Map<String, Boolean>`
- Scope (client_id, project_id) součástí session state

#### UI — zobrazení permissions
- V chat panelu (sidebar nebo header): indikátor aktivních permissions
- Např. štítky: "BG tasks ✓", "KB write ✓"
- Klik na štítek → revoke permission

### Alternativní přístup (jednodušší)
Místo plné permissions mapy stačí **system prompt enhancement**:
- Chat si v conversation context udržuje jaké akce user povolil
- Při switch_context přidat system message "Permissions resetovány — nový scope"
- Toto nevyžaduje DB změny ani UI, jen system prompt + agentic loop logiku

### Soubory
- `backend/service-orchestrator/app/chat/handler_agentic.py` — permissions checking
- `backend/service-orchestrator/app/chat/system_prompt.py` — instructions
- `backend/service-orchestrator/app/chat/models.py` — ChatPermissions model
- `backend/server/.../entity/ChatSessionDocument.kt` — persistence (pokud plná verze)
- UI chat panel — permissions display

---

## Pořadí implementace

1. ~~**Bug 2** (system prompt — background task pravidla)~~ ✅ HOTOVO
2. ~~**Bug 1** (system prompt + context — označení historie)~~ ✅ HOTOVO
3. ~~**Feature 3** (kb_delete tool)~~ ✅ HOTOVO
4. **Feature 4** (chat permissions scope) — scope-based write permissions + UI indikátor

## Ověření

- **Bug 1**: Chat se zeptá na něco co dříve halucinoval → místo opakování halucinace ověří přes tool
- **Bug 2**: Chat dostane dotaz → NEvytvoří background task pokud o to user nepožádal
- **Feature 3**: User řekne "ta informace byla špatná" → chat ji najde v KB a smaže
- **Feature 4**: User povolí "vytvoř BG task" → chat v témže scope může vytvářet BG tasky bez ptaní → po switch_context se permissions resetují → UI zobrazuje aktivní permissions
