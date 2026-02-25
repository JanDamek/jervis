# BUG: Notifikace selhání úlohy jsou nepoužitelné

**Datum:** 2026-02-25
**Priorita:** HIGH
**Typ:** BUG + ENHANCEMENT

---

## Reprodukce

1. MEETING_PROCESSING task projde přes qualification → READY_FOR_GPU → DISPATCHED_GPU
2. Python orchestrátor crashne (v tomto případě `ImportError: _TIER_INDEX`)
3. `router.py:99` odchytí výjimku → `report_status_change(status="error", error="cannot import name '_TIER_INDEX'...")`
4. Kotlin `OrchestratorStatusHandler.handleError()` → `userTaskService.failAndEscalateToUserTask()`
5. UI zobrazí dialog "Úloha vyžaduje odpověď" s textem "Úloha na pozadí selhala: MEETING_PROCESSING"
6. Push notifikace odešle stejný neužitečný text na mobil

**Screenshot:** Dialog jen říká typ úlohy, bez jakéhokoli kontextu co se stalo, proč, a co by uživatel měl dělat.

---

## Nalezené problémy

### Bug 1: `handleError()` neposílá error message (CRITICAL)

**Soubor:** `backend/server/.../coordinator/OrchestratorStatusHandler.kt:279`

```kotlin
// AKTUÁLNĚ — error text se zahodí:
userTaskService.failAndEscalateToUserTask(
    task = task,
    reason = "PYTHON_ORCHESTRATOR_ERROR",
)

// SPRÁVNĚ — předat error text:
userTaskService.failAndEscalateToUserTask(
    task = task,
    reason = "PYTHON_ORCHESTRATOR_ERROR",
    error = Exception(errorMsg),  // ← error string z Python orchestrátoru
)
```

Python posílá `error="cannot import name '_TIER_INDEX'..."` ale Kotlin ho ignoruje.

### Bug 2: Selhání = clarification dialog (WRONG UX)

**Soubor:** `shared/ui-common/.../notification/ApprovalNotificationDialog.kt`

Když task selže, `failAndEscalateToUserTask()` pošle `isApproval=false`, `interruptAction=null`.
UI to zobrazí jako "Úloha vyžaduje odpověď" s textovým polem "Vaše odpověď".

**Problém:** Selhání úlohy NENÍ clarification — uživatel nemá na co odpovídat.
Dialog by měl rozlišovat 3 režimy:
1. **Approval** (`isApproval=true`) — Povolit/Zamítnout akci
2. **Clarification** (`isApproval=false`, `interruptAction="clarify"`) — Agent potřebuje odpověď
3. **Error** (`isApproval=false`, `interruptAction=null`, selhání) — Úloha selhala, zobrazit error detail

### Bug 3: Chybí error detail v notifikaci

**Soubor:** `backend/server/.../task/UserTaskService.kt:38-60`

Title: `"Úloha na pozadí selhala: ${task.type}"` — jen typ úlohy (MEETING_PROCESSING).
Description obsahuje: state + reason + task.content, ale NE vlastní error message z Pythonu.

Po fixu Bug 1 bude description obsahovat i error message. Ale title by měl být lidsky čitelný:
- Místo `"Úloha na pozadí selhala: MEETING_PROCESSING"` → `"Zpracování schůzky selhalo"`
- Přidat mapování `TaskTypeEnum` → český lidský popis

### Bug 4: Push notifikace neobsahuje kontext

**Soubor:** `backend/server/.../task/UserTaskService.kt:86-118`

Push title: `"Nová úloha"` (pro non-approval) — ale to NENÍ nová úloha, to je selhání!
Push body: `title` = `"Úloha na pozadí selhala: MEETING_PROCESSING"` — bez dalšího kontextu.

**Fix:** Pro selhání:
- Push title: `"Úloha selhala"`
- Push body: lidsky čitelný popis co selhalo + zkrácený důvod

### Bug 5: Chybí akční tlačítka pro selhání

**Soubor:** `shared/ui-common/.../notification/ApprovalNotificationDialog.kt:163-217`

Pro selhání by dialog měl nabízet:
- **"Zkusit znovu"** — re-queue task do READY_FOR_QUALIFICATION
- **"Zahodit"** — cancel task
- **"Detail"** — zobrazit plný error text (expandable)

Aktuálně nabízí jen "Zavřít" a textové pole "Vaše odpověď" (nesmyslné pro error).

---

## Řešení

### Krok 1: Předat error do UserTaskService

`OrchestratorStatusHandler.handleError()` — předat `error` parameter.

### Krok 2: Nový mode pro error v UI dialogu

Přidat do `JervisEvent.UserTaskCreated`:
```kotlin
val isError: Boolean = false
val errorDetail: String? = null
```

V `UserTaskNotificationDialog` přidat třetí větev:
```kotlin
if (event.isError) {
    ErrorContent(event, onRetry, onDismiss)
} else if (event.isApproval) {
    ApprovalContent(...)
} else {
    ClarificationContent(...)
}
```

### Krok 3: ErrorContent composable

```
┌──────────────────────────────────┐
│ ⚠ Zpracování schůzky selhalo    │
│                                  │
│ Důvod: <error message>           │
│                                  │
│ ▸ Zobrazit detail                │ ← expandable
│   <full error text>              │
│                                  │
│        [Zahodit]  [Zkusit znovu] │
└──────────────────────────────────┘
```

### Krok 4: Lidské názvy typů úloh

Mapování v `UserTaskService` nebo sdílený helper:
```kotlin
val TASK_TYPE_LABELS = mapOf(
    TaskTypeEnum.MEETING_PROCESSING to "Zpracování schůzky",
    TaskTypeEnum.EMAIL_PROCESSING to "Zpracování emailu",
    TaskTypeEnum.IDLE_REVIEW to "Pravidelný přehled",
    TaskTypeEnum.JIRA_PROCESSING to "Zpracování Jira issue",
    // ...
)
```

### Krok 5: Push notifikace pro error

```kotlin
val pushTitle = when {
    isError -> "Úloha selhala"
    isApproval -> "Schválení vyžadováno"
    else -> "Úloha potřebuje odpověď"
}
val pushBody = when {
    isError -> "${taskTypeLabel}: ${errorSummary.take(80)}"
    else -> title
}
```

### Krok 6: Retry funkce

V `NotificationViewModel` přidat `retryTask(taskId)`:
- Zavolá nový RPC: `taskService.retryFailedTask(taskId)` → re-queue do `READY_FOR_QUALIFICATION`

---

## Soubory k úpravě

| Soubor | Změna |
|--------|-------|
| `backend/server/.../coordinator/OrchestratorStatusHandler.kt` | Předat error do failAndEscalateToUserTask |
| `backend/server/.../task/UserTaskService.kt` | Error mode, lidské názvy, push text |
| `shared/common-dto/.../events/JervisEvent.kt` | `isError`, `errorDetail` fieldy |
| `shared/ui-common/.../notification/ApprovalNotificationDialog.kt` | ErrorContent composable, retry tlačítko |
| `shared/ui-common/.../notification/NotificationViewModel.kt` | retryTask(), error handling |
| `shared/common-api/.../service/IAgentOrchestratorService.kt` | retryFailedTask RPC |
| `backend/server/.../rpc/AgentOrchestratorRpcImpl.kt` | retryFailedTask implementace |

**Docs k aktualizaci:** `docs/ui-design.md` (nový dialog pattern), `docs/architecture.md` (notification flow)

---

## Hotfix (okamžitý)

`_TIER_INDEX` ImportError crash je již opravený — `handler.py` nyní importuje `clamp_tier` z `provider.py`.
Bez tohoto fixu ŽÁDNÝ background task neprojde.
