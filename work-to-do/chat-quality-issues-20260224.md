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

## Bug 2: create_background_task bez souhlasu uživatele

### Symptom
Chat vytvořil background task, aniž uživatel o to požádal. Uživatel napsal pouze dotaz, ne žádost o background zpracování.

### Příčina
System prompt (`system_prompt.py:102-109`) **aktivně nabádá** k vytváření background tasků:
```
Většina práce jde na BACKGROUND — default.
```
A dále:
```
Background TODO — "podívej se na...", "zkontroluj..." → create_background_task
```

LLM interpretuje jakýkoli dotaz jako potenciální "background TODO" a vytváří tasky proaktivně.

### Řešení
Zpřísnit pravidla v system prompt:
```
**create_background_task**: Použij POUZE když:
1. Uživatel EXPLICITNĚ požádá o background zpracování ("udělej to na pozadí", "vytvoř task")
2. Uživatel pošle seznam 5+ požadavků a ty mu to NAVRHUJEŠ — ale čekej na souhlas
NIKDY nevytvářej background task bez explicitního souhlasu uživatele.
```

Odstranit/přeformulovat řádky 102-109 kde je "Většina práce jde na BACKGROUND — default".

### Soubory
- `backend/service-orchestrator/app/chat/system_prompt.py` — zpřísnit pravidla pro create_background_task

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

## Pořadí implementace

1. **Bug 2** (system prompt — background task pravidla) — nejrychlejší, čistě textová změna
2. **Bug 1** (system prompt + context — označení historie) — malý scope
3. **Feature 3** (kb_delete tool) — nový endpoint + tool + executor

## Ověření

- **Bug 1**: Chat se zeptá na něco co dříve halucinoval → místo opakování halucinace ověří přes tool
- **Bug 2**: Chat dostane dotaz → NEvytvoří background task pokud o to user nepožádal
- **Feature 3**: User řekne "ta informace byla špatná" → chat ji najde v KB a smaže
