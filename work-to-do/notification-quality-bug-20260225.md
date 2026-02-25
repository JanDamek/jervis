# BUG: Notifikace selhání úlohy — OPRAVENO

**Datum:** 2026-02-25
**Priorita:** HIGH → FIXED
**Typ:** BUG + ENHANCEMENT

---

## Co bylo opraveno

### Bug 1: `handleError()` neposílal error message z Pythonu ✅
`OrchestratorStatusHandler.handleError()` nyní předává `errorMessage` do `failAndEscalateToUserTask()`.

### Bug 2: Selhání zobrazovalo clarification dialog místo error dialogu ✅
Nový třetí mode `ErrorContent` v `ApprovalNotificationDialog.kt`:
- Rozlišuje 3 režimy: **Approval** / **Clarification** / **Error**
- Error dialog zobrazuje error detail s expandable sekcí
- Tlačítka: **Zahodit** (cancel task) + **Zkusit znovu** (re-route to agent)

### Bug 3: Title bez lidského názvu typu úlohy ✅
Nový `TASK_TYPE_LABELS` mapování v `UserTaskService.companion`:
- `MEETING_PROCESSING` → "Zpracování schůzky"
- `EMAIL_PROCESSING` → "Zpracování emailu"
- atd. pro všech 10 typů

### Bug 4: Push notifikace nerozlišovala error/approval/clarification ✅
Push title nyní: "Úloha selhala" / "Schválení vyžadováno" / "Úloha potřebuje odpověď"
Push body: lidsky čitelný popis s error detailem.

### Bug 5: Chyběly Retry/Zahodit tlačítka ✅
- `discardTask()` — cancel task via `repository.userTasks.cancel()`
- `retryTask()` — re-route via `sendToAgent(routingMode=DIRECT_TO_AGENT)`
- Oba wired do `App.kt` přes `onRetry`/`onDiscard` callbacky

### Hotfix: `_TIER_INDEX` ImportError crash ✅ (předchozí commit)

---

## Změněné soubory

| Soubor | Změna |
|--------|-------|
| `shared/common-dto/.../events/JervisEvent.kt` | + `isError`, `errorDetail` fieldy |
| `backend/server/.../coordinator/OrchestratorStatusHandler.kt` | Předat `errorMessage` do escalation |
| `backend/server/.../task/UserTaskService.kt` | Error mode, TASK_TYPE_LABELS, push text |
| `backend/server/.../rpc/NotificationRpcImpl.kt` | + `isError`, `errorDetail` params |
| `shared/ui-common/.../notification/ApprovalNotificationDialog.kt` | `ErrorContent` composable |
| `shared/ui-common/.../notification/NotificationViewModel.kt` | `retryTask()`, `discardTask()` |
| `shared/ui-common/.../App.kt` | Wire `onRetry`/`onDiscard` callbacks |
