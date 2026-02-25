# Chat Quality Issues — Halucinace z historie, neautorizované akce, chybějící KB self-correction

**Datum:** 2026-02-24
**Typ:** BUG / FEATURE
**Závažnost:** HIGH

## Kontext

Po opravě scope bugu (effective_client_id) chat sice hledá ve správném scope, ale odpovědi jsou stále špatné kvůli třem nezávislým problémům.

---

## Bug 1: Halucinace z chat historie

### Symptom
Chat odpovídá s termíny "traceId" a "Telemetry", které v datech NEEXISTUJÍ. Uživatel psal o "sessionId", nikdy ne "traceId".

### Příčina
- Předchozí chat zprávy (uložené v MongoDB) obsahují halucinované odpovědi z dřívějších iterací
- Kontext assembler (`context.py:165`) načte tyto zprávy jako historii
- LLM je vidí jako "fakta" a přebírá je do nové odpovědi
- Vzniká **self-reinforcing loop**: špatná odpověď → uložena → načtena → zopakována

### Řešení
1. **System prompt — kritická distance k historii**: Přidat do system_prompt.py varování:
   ```
   VAROVÁNÍ: Souhrny a předchozí zprávy mohou obsahovat nepřesnosti.
   VŽDY ověřuj fakta přes tools (kb_search, brain_search) místo spoléhání na historii.
   Pokud si nejsi jistý konkrétním termínem, hledej — neodpovídej z paměti.
   ```

2. **Kompresi/souhrny označit jako "neověřené"**: V `context.py` při komprimaci přidat prefix `[Neověřený souhrn]` k summarizovaným zprávám, aby LLM věděl, že to nejsou hard facts.

### Soubory
- `backend/service-orchestrator/app/chat/system_prompt.py` — varování o historii
- `backend/service-orchestrator/app/chat/context.py` — označení souhrnů

---

## ~~Bug 2: create_background_task bez souhlasu uživatele~~ ✅ OPRAVENO

Opraveno v `0dae82a9` — system prompt nyní vyžaduje explicitní souhlas uživatele před vytvořením background tasku. Nasazeno.

**Update 2026-02-25**: Tento přístup je příliš striktní. Pravidlo by mělo být SCOPE-BASED:
- Pokud uživatel na začátku konverzace povolí akci (např. "vytvoř background task"), povolení platí dokud se nezmění scope.
- Totéž platí pro všechny akce (store_knowledge, brain_create_issue, dispatch_coding_agent, ...).
- **→ Řešení: Feature 4 (chat permissions scope) — viz níže.**

---

## Feature 3: KB self-correction — smazání špatných dat

### Symptom
Když chat uloží špatnou informaci do KB (nebo ji tam vloží jiný tool), není způsob jak ji smazat. Špatná data zůstanou v KB navždy a ovlivňují budoucí odpovědi.

### Aktuální stav
- `store_knowledge` (executor.py:481) — ukládá s `sourceUrn: "user-knowledge:{category}:{subject}:{timestamp}"`
- `memory_store` (executor.py:1669) — ukládá s `source_urn: "memory:{client_id}:{subject}"`
- KB purge endpoint EXISTS (`service-knowledgebase/app/api/routes.py:510`) — `POST /purge` s `sourceUrn`
- **Žádný chat tool pro smazání neexistuje**

### Řešení
1. **Nový tool `kb_delete`**: Volá KB purge endpoint, maže podle sourceUrn
   ```python
   TOOL_KB_DELETE = {
       "name": "kb_delete",
       "description": "Smaž špatné/zastaralé záznamy z KB podle sourceUrn. "
                      "Použij když zjistíš, že informace v KB jsou chybné.",
       "parameters": {
           "properties": {
               "source_urn": {"type": "string", "description": "sourceUrn záznamu ke smazání"},
               "reason": {"type": "string", "description": "Důvod smazání (pro audit log)"}
           },
           "required": ["source_urn", "reason"]
       }
   }
   ```

2. **kb_search musí vracet sourceUrn**: Aktuálně `_execute_kb_search` (executor.py:455-475) vrací sourceUrn v results — ověřit že je to viditelné v tool output, aby chat mohl identifikovat co smazat.

3. **System prompt instrukce**:
   ```
   Pokud najdeš v KB chybnou informaci, smaž ji přes kb_delete.
   Pokud ti uživatel řekne, že tvá předchozí odpověď byla špatná,
   zkontroluj jestli špatná informace není v KB a pokud ano, smaž ji.
   ```

### Soubory
- `backend/service-orchestrator/app/tools/definitions.py` — nový TOOL_KB_DELETE
- `backend/service-orchestrator/app/tools/executor.py` — `_execute_kb_delete()` implementace
- `backend/service-orchestrator/app/chat/tools.py` — přidat do CORE kategorie
- `backend/service-orchestrator/app/chat/system_prompt.py` — instrukce pro self-correction

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
2. **Bug 1** (system prompt + context — označení historie) — malý scope
3. **Feature 3** (kb_delete tool) — nový endpoint + tool + executor
4. **Feature 4** (chat permissions scope) — scope-based write permissions + UI indikátor

## Ověření

- **Bug 1**: Chat se zeptá na něco co dříve halucinoval → místo opakování halucinace ověří přes tool
- **Bug 2**: Chat dostane dotaz → NEvytvoří background task pokud o to user nepožádal
- **Feature 3**: User řekne "ta informace byla špatná" → chat ji najde v KB a smaže
- **Feature 4**: User povolí "vytvoř BG task" → chat v témže scope může vytvářet BG tasky bez ptaní → po switch_context se permissions resetují → UI zobrazuje aktivní permissions
