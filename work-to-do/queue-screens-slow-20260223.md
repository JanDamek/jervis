# Bug: Fronta úloh + Fronta indexace — zbývající optimalizace

**Severity**: MEDIUM (základní opravy provedeny, zbývá hlubší optimalizace)
**Date**: 2026-02-23

## Vyřešeno (c4b90aac)
- ~~State filter ignorován~~ — `PendingTaskService` nyní filtruje dle type+state
- ~~Sekvenční RPC~~ — `PendingTasksScreen` paralelizuje listTasks + countTasks přes async
- ~~10s auto-refresh~~ — `IndexingQueueScreen` prodloužen na 30s
- ~~Duplicitní QUALIFYING query~~ — `IndexingQueueRpcImpl` eliminována druhá query

## Zbývá

### Fronta úloh (PendingTasksScreen)
- **Paginace** — stále loaduje VŠECHNY tasky bez limitu, chybí infinite scroll
- **Jeden RPC** — stále 2 volání (listTasks + countTasks), server by měl vracet `PagedResult(items, totalCount)`

### Fronta indexace (IndexingQueueRpcImpl)
- **Cache reference data** — connections, clients, projects se mění zřídka → cache s TTL (30-60s)
- **Separátní RPC pro paginaci** — `loadMoreKbWaiting()` stále volá celý `getIndexingDashboard()`
  místo dedikovaného `getKbWaitingPage(page, pageSize)`
- **Celý dashboard na load** — 12+ DB dotazů v jednom RPC (4× findAll na reference kolekce)
- **Compound index** `(state, type)` na tasks kolekci

### Dotčené soubory
| Soubor | Změna |
|--------|-------|
| `shared/.../ui/PendingTasksScreen.kt` | Paginace + jeden RPC |
| `backend/.../service/PendingTaskService.kt` | PagedResult response |
| `backend/.../rpc/IndexingQueueRpcImpl.kt:89-154` | Cache, separátní page RPC |
| `shared/.../ui/screens/IndexingQueueScreen.kt:123-165` | Dedikované page RPC |
