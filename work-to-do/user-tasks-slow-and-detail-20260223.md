# Bug: User Tasks — zbývající optimalizace a UX

**Severity**: MEDIUM (základní opravy provedeny)
**Date**: 2026-02-23

## Vyřešeno (c4b90aac)
- ~~CancellationException~~ — re-throw ve všech scope.launch blocích
- ~~Chat historie bez paginace~~ — `getChatHistory` nyní `takeLast(50)`
- ~~Chyba se nezobrazuje~~ — chatError StateFlow v detailu

## Zbývá

### 1. Pomalé načítání seznamu
- **Regex search bez indexu** — `UserTaskService.kt:142-147` stále `.*pattern.*` regex na `content`
  → MongoDB text index na `taskName` + `content`, nahradit regex za `$text` search
- **Dva DB dotazy** — count + data query prochází regex dvakrát
  → jeden dotaz s `facet` nebo `estimatedDocumentCount()`
- **Celý TaskDocument** — mapper přenáší content, attachments, agentCheckpointJson
  → projekce: pro seznam jen id, title, state, clientId, projectId, createdAt, pendingQuestion
- **Compound index** `(type, createdAt)` na tasks kolekci

### 2. Prázdný detail user tasku
Detail nezobrazuje strukturovaně:
- **Původní obsah** — co spustilo task (email subject, issue title...)
- **Otázka orchestrátoru** — `pendingUserQuestion` prominentně zvýrazněná
- **Kontext otázky** — `userQuestionContext` viditelný
- **Hlavička** — typ zdroje, klient, projekt, datum

### 3. Anglické user tasky
- Server labels opraveny na češtinu
- Starší anglické tasky v DB — OK, postupně odpadnou
- **K ověření**: orchestrátor LLM generuje `ask_user` otázky česky?

### 4. Kalendář
- **DONE tasky v kalendáři** — filtrovat pryč, ukazovat jen aktivní
- **Staré emaily naplánované na dnešek** — kvalifikátor by neměl plánovat staré emaily
- **Prošlé SCHEDULED_TASK** → eskalovat jako urgentní USER_TASK
- **Kalendářní zobrazení** — přepínač seznam / denní / týdenní grid

### Dotčené soubory
| Soubor | Změna |
|--------|-------|
| `backend/.../service/task/UserTaskService.kt:130-167` | Text index, projekce, jeden dotaz |
| `backend/.../mapper/TaskMapper.kt` | Lightweight DTO pro seznam |
| `backend/.../entity/TaskDocument.kt` | MongoDB indexy |
| `shared/.../ui/UserTasksScreen.kt:272-281` | Strukturovaný detail |
| `shared/.../ui/SchedulerScreen.kt` | DONE filtr, kalendářní view |
