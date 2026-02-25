# Otevřené issues — konsolidovaný přehled 2026-02-25

**Datum:** 2026-02-25
**Aktualizace:** 2026-02-25 — batch fix A1, A2, B1, B2, B3, B4, D2
**Typ:** SOUHRN

---

## A. Orchestrátor — background handler

### ~~A1. Context overflow — num_ctx=8192 nestačí pro background tasks (HIGH)~~ ✅ OPRAVENO

Aplikován dynamický context estimation z `handler_agentic.py` do `background/handler.py`:
- Odhad tokenů před prvním i před každým dalším LLM voláním
- Auto-eskalace tieru při překročení 85% num_ctx
- Detekce context overflow v response ("Operation not allowed" jako text)
- Background tasks mohou používat až LOCAL_XLARGE (128k)

### ~~A2. IDLE_REVIEW opakuje stejný "Invalid traceId" query (MEDIUM)~~ ✅ OPRAVENO (code)

Přidán rate limiting pro search tools v background handleru:
- Každý search tool (brain_search_issues, kb_search, web_search, brain_search_pages) max 3× za task
- Po dosažení limitu se vynutí závěr s dostupnými výsledky

**Zbývá**: Ručně smazat/uzavřít halucinované Jira issues (JI-1 až JI-15) v brain projektu.

### A3. WORKAROUND: Ollama tool_calls parsing (LOW)

`respond.py:282` — Ollama `qwen3-coder-tool:30b` nepodporuje nativní tool_calls, tak se
ručně parsuje JSON z content fieldu. Workaround, ne bug — bude potřeba přehodnotit při upgradu modelu.

**Soubor**: `backend/service-orchestrator/app/graph/nodes/respond.py`

---

## B. Server — Kotlin

### ~~B1. Stale connection v GitRepositoryService (LOW)~~ ✅ OPRAVENO

Přidána deduplikace logů — ConcurrentHashSet sleduje již varované connection:resource páry.
Warning se zobrazí jen jednou za JVM lifetime místo každý polling cyklus.

### ~~B2. Silent KB purge failure při mazání meetingu (MEDIUM)~~ ✅ OPRAVENO

Přidán `logger.warn` do catch bloku v `MeetingRpcImpl.kt` — KB purge failures se nyní logují.

### ~~B3. TODO: KB hybrid retriever — hardcoded graph distance (LOW)~~ ✅ OPRAVENO

`graph_service.traverse()` AQL nyní vrací `{vertex, depth}` přes path proměnnou.
Hloubka se ukládá do `properties["_depth"]` a `hybrid_retriever` ji používá místo hardcoded `distance=1`.

### ~~B4. TEMPORARY: JSON→SQLite migrace v KB extraction queue (INFO)~~ ✅ OPRAVENO

Dočasný migrační kód (`_migrate_from_json_if_needed`, `_backup_and_delete_json`) odstraněn.
Migrace byla úspěšně nasazena.

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

### ~~D2. Executor timeouts by měly být v config.py (LOW)~~ ✅ OPRAVENO

Timeouty přesunuty z hardcoded konstant v `executor.py` do `config.py` Settings:
- `timeout_web_search`, `timeout_kb_search`, `max_tool_result_chars`, `tool_execution_timeout`
- Konfigurovatelné přes env proměnné `ORCHESTRATOR_*`

---

## E. Uzavřené work-to-do (reference)

| Soubor | Stav |
|--------|------|
| `chat-quality-issues-20260224.md` | ✅ Vše opraveno (Bug 1, 2, Feature 3, 4) |
| `meeting-group-expand-bug-20260225.md` | ✅ Opraveno |
| `orchestrator-bugs-20260225.md` | ✅ Bugs 1-5 opraveny |

---

## Zbývající otevřené issues

1. **A2** (částečně) — Ruční cleanup halucinovaných Jira issues JI-1 až JI-15
2. **D1** — Epic plán do repo (MEDIUM, vyžaduje obsah z Claude session)
3. **C1** — Metrics server (LOW, infrastruktura)
4. **A3** — Ollama tool_calls workaround (LOW, čeká na model upgrade)
