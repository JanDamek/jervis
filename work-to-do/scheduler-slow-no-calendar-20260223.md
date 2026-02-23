# Plánovač: pomalé otevírání + chybí kalendář + prošlé úkoly v indexaci

> **Datum:** 2026-02-23
> **Zjištěno:** UI — plánovač se otevírá velmi dlouho, chybí kalendářní pohled, prošlé úkoly by neměly projít indexací
> **Stav:** Aktuální implementace = prostý list bez termínů, bez priority

---

## 1. BUG: `listAllTasks()` načítá VŠECHNY tasky — ne jen SCHEDULED_TASK (P0)

### Root Cause

**Soubor:** `backend/server/.../TaskSchedulingService.kt:56`

```kotlin
suspend fun listAllTasks(): List<TaskDocument> = scheduledTaskRepository.findAll().toList()
```

`findAll()` vrací **VŠECHNY tasky** z kolekce `tasks` — EMAIL_PROCESSING, BUGTRACKER_PROCESSING, GIT_PROCESSING, WIKI_PROCESSING, MEETING_PROCESSING, USER_INPUT_PROCESSING, USER_TASK, IDLE_REVIEW + SCHEDULED_TASK. V produkci to můžou být **tisíce** dokumentů.

**Správné metody existují ale nepoužívají se:**
- Řádek 58: `findByProjectIdAndType(projectId, TaskTypeEnum.SCHEDULED_TASK)` ✓
- Řádek 61: `findByClientIdAndType(clientId, TaskTypeEnum.SCHEDULED_TASK)` ✓

### Řešení

Změnit `listAllTasks()`:
```kotlin
suspend fun listAllTasks(): List<TaskDocument> =
    scheduledTaskRepository.findByType(TaskTypeEnum.SCHEDULED_TASK).toList()
```

Nebo přidat dedikovanou repository metodu s filtrem na type + state (jen NEW, ne DONE/ERROR).

---

## 2. BUG: Sekvenční RPC volání + O(n) lookup (P1)

### Root Cause

**Soubor:** `shared/ui-common/.../SchedulerScreen.kt:62-70`

```kotlin
LaunchedEffect(Unit) {
    clients = repository.clients.getAllClients()       // RPC #1 — čeká
    projects = repository.projects.getAllProjects()     // RPC #2 — čeká
    loadTasks()                                         // RPC #3 — čeká
}
```

3 sekvenční RPC volání — žádná paralelizace. Navíc v `loadTasks()` (řádek 43-47):

```kotlin
val client = clients.find { it.id == task.clientId }     // O(n) pro KAŽDÝ task
val project = projects.find { it.id == projId }           // O(n) pro KAŽDÝ task
```

Pro N tasků × M klientů = O(N×M) lookupů místo O(N) s mapou.

### Řešení

1. Paralelizovat RPC volání přes `async { }` + `awaitAll()`
2. Použít `clients.associateBy { it.id }` pro O(1) lookup

---

## 3. FEATURE: Přejmenovat na "Kalendář" + kalendářní pohled (P1)

### Aktuální stav vs docs

**Docs (`ui-design.md:577`):** Plánovač má ikonu `CalendarMonth` a je v sekci "Management".

**Aktuální implementace (`SchedulerScreen.kt`):** Prostý `JListDetailLayout` s textovým seznamem. Žádný:
- **Kalendářní pohled** — termíny nejsou vizuálně zobrazeny na časové ose
- **Barevné rozlišení** urgence — prošlé vs blížící se vs budoucí
- **Indikátor stavu** — NEW vs dispatched vs DONE
- **Skupiny** — dnes / tento týden / tento měsíc / budoucí / **PROŠLÉ**

### Návrh od uživatele

- **Přejmenovat "Plánovač" → "Kalendář"** — lépe odpovídá funkci (ikona už je `CalendarMonth`)
- **Zobrazení:** buď list seřazený podle času (s vizuálními skupinami), nebo přímo kalendářní grid

### Řešení (směr)

**Varianta A (list podle času):**
- Rozdělit seznam do skupin: **Prošlé** (červeně), **Dnes**, **Tento týden**, **Později**
- Prošlé úkoly zvýraznit jako urgentní
- Sticky headers s datem

**Varianta B (kalendářní grid):**
- Měsíční/týdenní grid s tečkami na dnech kde jsou úkoly
- Klik na den → seznam úkolů pro ten den
- Prošlé dny zvýrazněné červeně

---

## 4. BUG: Prošlé úkoly se nemají dostat přes indexaci (P1)

### Problém

Uživatel říká: "past musí urgentně řešit background proces. Hlavně past se tady neměly dostat přes indexaci."

### Aktuální flow z `docs/structures.md:467-471`:

```
Step 4: hasFutureDeadline=true AND hasActionableContent=true
    ├─ deadline < scheduleLeadDays away → READY_FOR_GPU (too close, do now)
    └─ deadline >= scheduleLeadDays away → create SCHEDULED_TASK copy
         scheduledAt = deadline - scheduleLeadDays
         original task → DONE
```

**Problém 1:** Kvalifikace kontroluje `hasFutureDeadline` — ale co když deadline je **v minulosti**? Aktuální kód v `SimpleQualifierAgent` by měl rozpoznat prošlý deadline a poslat rovnou do `READY_FOR_GPU` (urgentní zpracování), ale:
- Pokud LLM vrátí `hasFutureDeadline=true` pro deadline v minulosti (model chyba), task se naplánuje na minulý čas
- Scheduler loop ho pak okamžitě dispatchne (scheduledAt <= now), ale s prodlevou kvalifikace + scheduler cyklu (60s)

**Problém 2:** Prošlé SCHEDULED_TASK zůstávají v plánovači jako NEW — scheduler je dispatchne, ale:
- Žádná priorita — prošlé se zpracovávají stejně jako budoucí
- Žádné zvýraznění v UI — uživatel nevidí které úkoly jsou prošlé
- Žádný urgentní background proces pro prošlé úkoly

### Řešení (směr)

1. **Kvalifikace:** Pokud deadline je v minulosti → READY_FOR_GPU okamžitě (ne SCHEDULED_TASK)
2. **Scheduler loop:** Prošlé úkoly (scheduledAt < now) dispatchovat s vyšší prioritou (nižší `queuePosition`)
3. **UI:** Prošlé úkoly zobrazit červeně s urgentním indikátorem

---

## 5. Relevantní soubory

| Soubor | Řádky | Co |
|--------|-------|----|
| `backend/server/.../TaskSchedulingService.kt` | 56 | `listAllTasks()` — `findAll()` bez type filtru |
| `backend/server/.../TaskSchedulingService.kt` | 58-62 | Správné filtrované metody (nepoužité v listAll) |
| `shared/ui-common/.../SchedulerScreen.kt` | 42-53 | `loadTasks()` — O(n) lookup |
| `shared/ui-common/.../SchedulerScreen.kt` | 62-71 | Sekvenční RPC volání |
| `backend/server/.../BackgroundEngine.kt` | 644-684 | Scheduler loop — dispatch bez priority |
| `backend/server/.../SimpleQualifierAgent.kt` | — | `hasFutureDeadline` rozhodování |
| `docs/structures.md` | 467-471 | Kvalifikační flow pro deadline |
| `docs/ui-design.md` | 577 | Plánovač v menu (CalendarMonth ikona) |

---

## 6. Srovnání s docs

| Aspekt | Docs/Očekávání | Realita |
|--------|---------------|---------|
| Načítání dat | Jen SCHEDULED_TASK | Všechny typy tasků (`findAll()`) |
| UI pohled | Kalendář (ikona CalendarMonth) | Prostý textový seznam |
| Termíny | Vizuálně zvýrazněné | Jen textový formát, žádné barvy |
| Prošlé úkoly | Nesmí projít indexací / urgentní | Zpracovány stejně jako budoucí |
| Paralelizace | Standard (async) | Sekvenční RPC volání |
