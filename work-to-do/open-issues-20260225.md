# Otevřené issues — konsolidovaný přehled 2026-02-25

**Datum:** 2026-02-25
**Typ:** SOUHRN

---

## A. Orchestrátor — background handler

### A1. Context overflow — num_ctx=8192 nestačí pro background tasks (HIGH)

IDLE_REVIEW task s 31 tools + Jira results vyčerpá 8k kontext → Ollama vrátí "Operation not allowed"
jako text (ne exception) → handler to interpretuje jako final answer s nesmyslným výstupem.

**Existující vzor**: Chat a KB simple agent už používají dynamický context estimation + tier escalation.
Background handler to nedělá — hardcoded `local_fast` (8k).

**Řešení**: Aplikovat dynamický context estimation z `handler_agentic.py` do `background/handler.py`.

**Soubory**:
- `backend/service-orchestrator/app/background/handler.py`
- `backend/service-orchestrator/app/llm/provider.py`
- `backend/service-orchestrator/app/chat/handler_agentic.py` (reference)

**Dokumentace**: `docs/guidelines.md`, `docs/orchestrator-detailed.md`

### A2. IDLE_REVIEW opakuje stejný "Invalid traceId" query (MEDIUM)

KB-read loguje opakovaně stejný query "Invalid traceId error investigation" (08:35, 08:40, 09:01,
09:31, 10:04, 11:28). Orchestrátor se zasekl na investigaci halucinovaného termínu z dřívějšího běhu.

**Příčina**: Halucinovaná data v Jira issues (JI-1 až JI-15) obsahují "Invalid traceId" —
tyto issues vytvořil předchozí background task na základě halucinace z chat historie.

**Řešení**:
1. Ručně smazat/uzavřít halucinované Jira issues (JI-1 až JI-15)
2. IDLE_REVIEW by měl kontrolovat, zda issues nejsou duplikáty/halucinace
3. Zvážit rate limiting na IDLE_REVIEW pro stejné query

**Soubory**:
- `backend/service-orchestrator/app/background/handler.py` — IDLE_REVIEW logika
- Jira brain project — cleanup halucinovaných issues

### A3. WORKAROUND: Ollama tool_calls parsing (LOW)

`respond.py:282` — Ollama `qwen3-coder-tool:30b` nepodporuje nativní tool_calls, tak se
ručně parsuje JSON z content fieldu. Workaround, ne bug — bude potřeba přehodnotit při upgradu modelu.

**Soubor**: `backend/service-orchestrator/app/graph/nodes/respond.py`

---

## B. Server — Kotlin

### B1. Stale connection v GitRepositoryService (LOW)

`GitRepositoryService` loguje WARN pro 8 mazlusek/moneta/ufo_* zdrojů s neexistující
connection `6986002d8bf32b35197e2bf2`. Handling je graceful (skip + WARN), ale warningy se opakují
při každém polling cyklu.

**Řešení**: Vyčistit stale resource references v projektech přes UI (odebrat neexistující zdroje),
nebo přidat deduplikaci logů (jednou za hodinu místo každý cyklus).

**Soubor**: `backend/server/src/main/kotlin/com/jervis/service/indexing/git/GitRepositoryService.kt`

### B2. Silent KB purge failure při mazání meetingu (MEDIUM)

`MeetingRpcImpl.kt:277` — `knowledgeService.purge(sourceUrn)` má prázdný `catch (_: Exception) {}`.
Pokud KB purge selže při mazání meetingu, KB data zůstanou jako sirotci bez logování.

**Řešení**: Přidat logging: `logger.warn { "Failed to purge KB data for meeting: ${e.message}" }`.

**Soubor**: `backend/server/src/main/kotlin/com/jervis/rpc/MeetingRpcImpl.kt`

### B3. TODO: KB hybrid retriever — hardcoded graph distance (LOW)

`hybrid_retriever.py:328` — `TODO: Get actual distance from traversal`. Aktuálně hardcoded `distance=1`
pro graph score calculation. Ovlivňuje přesnost graph-based retrievalu.

**Soubor**: `backend/service-knowledgebase/app/services/hybrid_retriever.py`

### B4. TEMPORARY: JSON→SQLite migrace v KB extraction queue (INFO)

`llm_extraction_queue.py:581` — Jednorázová migrace z JSON do SQLite. Kód se označuje jako TEMPORARY
k odstranění po úspěšném nasazení. Ověřit, zda migrace proběhla na všech instancích a kód smazat.

**Soubor**: `backend/service-knowledgebase/app/services/llm_extraction_queue.py`

---

## C. Infrastruktura

### C1. Metrics server není nainstalovaný (LOW)

`kubectl top pods` vrací "Metrics API not available". Bez metrics serveru nelze monitorovat
spotřebu CPU/RAM podů.

**Řešení**: Nainstalovat metrics-server do K8s clusteru.

### C2. Guidelines EPIC — ObjectId value class gotcha (INFO)

Při merge EPIC 1 (Guidelines Engine) bylo nutné opravit `ClientId(string)` → `ClientId.fromString(string)`.
Toto je známý gotcha (value class obaluje ObjectId, ne String). Zdokumentováno v MEMORY.md.

Opraveno ve 4 souborech: `GuidelinesDocument.kt`, `GuidelinesRpcImpl.kt`,
`InternalGuidelinesRouting.kt`, `GuidelinesService.kt`.

---

## D. Dokumentace

### D1. Epic plán není v repo (MEDIUM)

`docs/epic-plan-autonomous.md` je jen stub ("Kompletní EPIC plán je uložen v Claude chat kontextu").
Plán by měl být verzovaný v repo.

**Řešení**: Zapsat plný EPIC plán z Claude session do souboru.

### D2. Executor timeouts by měly být v config.py (LOW)

`executor.py` má timeouty jako konstanty v kódu (`_TIMEOUT_WEB_SEARCH=15s`, `_TIMEOUT_KB_SEARCH=10s`,
`_TOOL_EXECUTION_TIMEOUT_S=120s`). Nově přidaný `config.py` Settings pattern umožňuje
env-konfigurovatelné hodnoty.

**Soubory**:
- `backend/service-orchestrator/app/tools/executor.py`
- `backend/service-orchestrator/app/config.py`

---

## E. Uzavřené work-to-do (reference)

| Soubor | Stav |
|--------|------|
| `chat-quality-issues-20260224.md` | ✅ Vše opraveno (Bug 1, 2, Feature 3, 4) |
| `meeting-group-expand-bug-20260225.md` | ✅ Opraveno |
| `orchestrator-bugs-20260225.md` | ✅ Bugs 1-4 opraveny, Bug 5 = A1 výše |

---

## Priorita implementace

1. **A1** — context overflow (HIGH, ovlivňuje všechny background tasks)
2. **A2** — IDLE_REVIEW halucinace cleanup (MEDIUM, ale zapleveluje logy + Jira)
3. **B2** — silent KB purge failure (MEDIUM, data integrity)
4. **D1** — epic plán do repo (MEDIUM, organizace práce)
5. **B1** — stale git connection cleanup (LOW, jen warningy)
6. **D2** — executor timeouts do config (LOW, nice-to-have)
7. **B3** — hybrid retriever graph distance (LOW, přesnost)
8. **B4** — smazat temporary migraci (INFO, cleanup)
9. **C1** — metrics server (LOW, monitoring)
10. **A3** — ollama tool_calls workaround (LOW, čeká na model upgrade)
