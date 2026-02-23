# Bug: Fronta úloh + Fronta indexace — pomalé načítání

**Severity**: HIGH (UI nepoužitelné při větším objemu dat)
**Date**: 2026-02-23

## 1. Fronta úloh (PendingTasksScreen)

### Symptom
Otevření screenu "Fronta úloh" trvá velmi dlouho.

### Příčiny

#### 1a. Dva sekvenční RPC volání
`PendingTasksScreen.kt:56-65`:
```kotlin
fun load() {
    tasks = repository.pendingTasks.listTasks(selectedTaskType, selectedState)
    totalTasks = repository.pendingTasks.countTasks(selectedTaskType, selectedState)
}
```
- `listTasks` a `countTasks` se volají **sekvenčně** — druhé čeká na první
- Obě provádějí DB query přes stejná kritéria → double scan

#### 1b. Žádná paginace
- `listTasks` vrací **VŠECHNY** tasky bez limitu
- Při stovkách tasků přenáší obrovský payload přes WebSocket

### Doporučená oprava
1. **Jeden RPC** — server vrátí `PagedResult(items, totalCount)` v jednom volání
2. **Paginace** — loadovat max 20-50 tasků, infinite scroll nebo stránkování
3. **Paralelizace** — pokud zůstanou 2 volání, `async` místo sekvenčního

---

## 2. Fronta indexace (IndexingQueueScreen)

### Symptom
Otevření screenu "Fronta indexace" trvá velmi dlouho, auto-refresh každých 10s opakuje celý scan.

### Příčiny

#### 2a. Masivní `getIndexingDashboard` — mnoho DB kolekcí naráz
`IndexingQueueRpcImpl.kt:89-154` — jeden RPC call dělá:
1. `connectionRepository.findAll()` — celá kolekce connections
2. `clientRepository.findAll()` — celá kolekce clients
3. `projectRepository.findAll()` — celá kolekce projects
4. `pollingStateRepository.findAll()` — celá kolekce polling states
5. `pollingIntervalRpc.getSettings()` — další DB dotaz
6. `collectGitItems` + `collectEmailItems` + `collectBugTrackerItems` + `collectWikiItems` — 4× scan index kolekcí
7. `kbClient.getExtractionQueue(limit=200)` — HTTP volání do KB service
8. `mongoTemplate.find(QUALIFYING tasks)` — full scan tasks kolekce
9. `mongoTemplate.find(active indexing tasks)` — další scan tasks kolekce
10. `collectTasksByStates(READY_FOR_GPU)` — další scan
11. `collectTasksByStates(DISPATCHED_GPU, PYTHON_ORCHESTRATING)` — další scan
12. `collectIndexedTasksPaginated` — další scan

Celkem **12+ DB dotazů + 1 HTTP** v jednom RPC volání.

#### 2b. Duplicitní QUALIFYING query
`IndexingQueueRpcImpl.kt:330-354`:
- Řádek 335: `activeServerTasks` loaduje QUALIFYING + DONE tasky
- Řádek 348: `qualifyingTasks` loaduje znovu QUALIFYING tasky — **duplicitní query**

#### 2c. Auto-refresh opakuje celý scan
`IndexingQueueScreen.kt:170-189`:
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        delay(10_000)
        repository.indexingQueue.getIndexingDashboard(...)  // celý dashboard znovu!
    }
}
```
Každých 10 sekund se opakuje celý masivní scan (12+ DB dotazů + HTTP).

#### 2d. `loadMoreKbWaiting` / `loadMoreIndexed` volá celý dashboard
`IndexingQueueScreen.kt:123-165`:
- Pro načtení další stránky KB fronty se volá `getIndexingDashboard()` — **celý dashboard**
- Mělo by existovat dedikované `getKbWaitingPage(page, pageSize)` RPC

### Doporučená oprava
1. **Cache reference data** — connections, clients, projects se mění zřídka → cache s TTL (30-60s)
2. **Eliminovat duplicitní query** — QUALIFYING tasks loadovat jednou, použít dvakrát
3. **Separátní RPC pro paginaci** — `getKbWaitingPage()`, `getIndexedPage()` místo celého dashboardu
4. **Inkrementální refresh** — auto-refresh pouze pro aktivní sekce (processing counts), ne celý dashboard
5. **Compound index** na `(state, type)` v tasks kolekci pro rychlé filtrování
6. **Prodloužit refresh interval** — 10s je příliš agresivní, 30-60s stačí pro dashboard

### Dotčené soubory

| Soubor | Řádek | Problém |
|--------|-------|---------|
| `shared/.../ui/PendingTasksScreen.kt` | 56-65 | 2 sekvenční RPC, žádná paginace |
| `shared/.../ui/screens/IndexingQueueScreen.kt` | 95-121 | Celý dashboard na load |
| `shared/.../ui/screens/IndexingQueueScreen.kt` | 123-165 | Celý dashboard pro paginaci |
| `shared/.../ui/screens/IndexingQueueScreen.kt` | 170-189 | 10s auto-refresh celého dashboardu |
| `backend/.../rpc/IndexingQueueRpcImpl.kt` | 89-154 | 12+ DB dotazů v jednom RPC |
| `backend/.../rpc/IndexingQueueRpcImpl.kt` | 330-354 | Duplicitní QUALIFYING query |
| `backend/.../rpc/IndexingQueueRpcImpl.kt` | 299-465 | collectPipelineTasks — masivní scan |
