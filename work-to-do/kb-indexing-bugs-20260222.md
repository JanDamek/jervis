# KB Indexing: 3 tasky ve zpracování + fronta vždy 199

> **Datum:** 2026-02-22 ~23:00
> **Zjištěno:** Screenshot UI "Fronta indexace"
> **Stav:** KB zpracování=3 (má být 1), KB fronta=199 (hardcoded limit)

---

## 1. BUG: KB zpracování ukazuje 3 tasky — má být vždy 1 (P1)

### Fakta ze screenshotu:

| Task | Stav | Doba |
|------|------|------|
| "vzdump backup status (pve-200.damek.local): backup failed" | Kvalifikuje | **2h 18m** (stuck!) |
| "mBank – Email Push" | LLM analýza (qwen3-coder-tool:30b) | 4m 8s |
| "# Email: Vaše zásilka byla přijata k přepravě" | Hotovo | **1495m 36s** (~25h!) |

### Proč je concurrency 1:

- `TaskQualificationService.kt:48`: `val effectiveConcurrency = 1` — hardcoded
- Komentář řádky 23-25: "each qualification calls _generate_summary() (30B LLM via router). Higher concurrency overloads CPU Ollama."
- `flatMapMerge(concurrency = effectiveConcurrency)` na řádku 54
- **Design: 1 GPU = 1 task najednou.** 3 tasky v "KB zpracování" je bug.

### Root Cause: Stuck tasks + recovery nefunguje za běhu

**Task stuck na "Kvalifikuje" 2+ hodin:**
- `TaskQualificationService.kt:34-43`: `isQualificationRunning = AtomicBoolean(false)`
- Pokud kvalifikační cyklus **visí** (KB HTTP call bez timeoutu), `isQualificationRunning` zůstane `true` navždy
- Žádný nový cyklus nezačne → `recoverStuckIndexingTasks()` se NIKDY nezavolá
- Stuck task zůstane v QUALIFYING navždy (do restartu podu)

**Recovery mechanismy:**
1. **Startup recovery** (`TaskService.kt:685-733`): `resetStaleTasks()` — resetuje VŠECHNY QUALIFYING tasky. Ale jen při startu podu.
2. **Runtime recovery** (`TaskService.kt:717-729`): `recoverStuckIndexingTasks()` — resetuje QUALIFYING tasky >10 min. Ale volá se na konci kvalifikačního cyklu (`TaskQualificationService.kt:87`) — pokud cyklus visí, NIKDY se nezavolá.

**Task "Hotovo" ale stále ve zpracování (1495m):**
- Task je DONE ale UI ho stále zobrazuje v "KB zpracování"
- Možná UI bug: UI nefiltruje hotové tasky z "zpracování" sekce
- Nebo: task v KB SQLite queue je stále `in_progress` ale Mongo task je DONE (inconsistentní stav)

### Řešení (směr):

1. **Watchdog pro kvalifikační cyklus** — Pokud `isQualificationRunning` je `true` déle než X minut (např. 15), force-reset na `false` a zavolat `recoverStuckIndexingTasks()`
2. **HTTP timeout na KB calls** — Kvalifikace volá KB REST API. Pokud není timeout, může viset navždy. Přidat timeout (60-120s).
3. **Periodická recovery nezávislá na kvalifikačním cyklu** — Spouštět `recoverStuckIndexingTasks()` na separátním timeru (každých 5 min), ne jen na konci cyklu.
4. **UI: nezbrazovat DONE tasky v "zpracování"** — Filtrovat hotové tasky z UI sekce.

### Relevantní kód:

| Soubor | Řádky | Co |
|--------|-------|----|
| `backend/server/.../TaskQualificationService.kt` | 34-43 | `isQualificationRunning` guard |
| `backend/server/.../TaskQualificationService.kt` | 48, 54 | `effectiveConcurrency = 1` |
| `backend/server/.../TaskQualificationService.kt` | 71-76 | Error handler failure → stuck QUALIFYING |
| `backend/server/.../TaskQualificationService.kt` | 87 | `recoverStuckIndexingTasks()` call |
| `backend/server/.../TaskService.kt` | 685-733 | `resetStaleTasks()` (startup only) |
| `backend/server/.../TaskService.kt` | 717-729 | `recoverStuckIndexingTasks()` (10 min threshold) |

---

## 2. BUG: KB fronta vždy ukazuje 199 (hardcoded limit) (P2)

### Fakta:

UI ukazuje "KB fronta 199" — vždy 199, nikdy víc, nikdy míň (i když reálně je ve frontě tisíce položek).

### Root Cause: Řetěz hardcoded limitů

**Vrstva 1 — Kotlin volá Python s `limit = 200`:**
- `IndexingQueueRpcImpl.kt:319`: `val kbQueueResponse = kbClient.getExtractionQueue(limit = 200)`
- `KnowledgeServiceRestClient.kt:564`: `suspend fun getExtractionQueue(limit: Int = 200)`
- REST call: `GET /queue?limit=200`

**Vrstva 2 — Python vrátí max 200 položek:**
- `llm_extraction_queue.py:440-461`: `list_queue(limit)` → `SELECT ... LIMIT ?` (200)
- `routes.py:274-283`: `/queue` endpoint vrátí items + stats

**Vrstva 3 — Kotlin filtruje 1 položku (in_progress → QUALIFYING):**
- `IndexingQueueRpcImpl.kt:358-359`: `kbInProgressFiltered = kbInProgress.filter { it.sourceUrn !in qualifyingCorrelationIds }`
- 1 položka (aktuálně QUALIFYING) se odfiltruje
- **200 - 1 = 199**

### Proč přesně 199:

1. Python SQLite queue má >> 200 pending+in_progress položek
2. Kotlin požádá o 200 → dostane 200
3. 1 položka je `in_progress` a matchne s QUALIFYING Mongo taskem → odfiltruje se
4. UI zobrazí **199**

### Řešení (směr):

- **Zobrazovat celkový počet z `stats`** — Python `/queue` endpoint už vrací `stats` (celkový count). UI by mělo zobrazovat reálný count (např. "KB fronta 1,247"), ne počet fetchnutých položek.
- Nebo zvýšit limit (ale to je jen workaround — s tisíci položkami nepomůže).
- **Klíčové:** Python `/queue` response zahrnuje `stats` objekt s celkovým počtem. Kotlin `IndexingQueueRpcImpl` by měl brát count z `stats.pending_count` (nebo ekvivalent), ne z `items.size`.

### Relevantní kód:

| Soubor | Řádky | Co |
|--------|-------|----|
| `backend/server/.../IndexingQueueRpcImpl.kt` | 319 | `limit = 200` hardcoded |
| `backend/server/.../KnowledgeServiceRestClient.kt` | 564 | `getExtractionQueue(limit = 200)` |
| `backend/service-knowledgebase/app/services/llm_extraction_queue.py` | 440-461 | `list_queue()` s SQL LIMIT |
| `backend/service-knowledgebase/app/api/routes.py` | 274-283 | `/queue` endpoint (vrací items + stats) |
| `backend/server/.../IndexingQueueRpcImpl.kt` | 358-359 | Filtrování in_progress → -1 = 199 |
