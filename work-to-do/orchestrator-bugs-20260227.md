# Orchestrator bugy — 2026-02-27

> Všechny 3 bugy opraveny 2026-02-27.

## ~~BUG 1: `get_mongo_db` neexistuje — topic_tracker a consolidation nefunkční~~ ✅

**Fix:** Přidána `get_mongo_db()` do `kotlin_client.py` s async motor klientem.
Reuse singleton `AsyncIOMotorClient`, vrací default database z `settings.mongodb_url`.

## ~~BUG 2: `respond_to_user_task` — KeyError `'task_id'`~~ ✅

**Fix:** Handler v `handler_tools.py` nyní akceptuje jak `task_id` tak `user_task_id`.

## ~~BUG 3: `brain_create_issue` tool loop — detekce nefunguje jako stop~~ ✅

**Fix:** Dvě změny:
1. `background/handler.py`: Loop detection přesunuta PŘED tool execution.
   Při detekci se tool nespustí, vrátí se error do LLM, a `loop_break` flag
   ukončí i outer while-loop (ne jen inner for-loop).
2. `graph/nodes/respond.py`: Stejná oprava — detekce před execution.

## BUG 4: Router nepoužívá p40-2 — `find_with_model` vrací vždy první GPU

**Priorita**: HIGH

### Problém

`GpuPool.find_with_model()` v `gpu_state.py:109-114` vrací **první** GPU
s načteným modelem. p40-1 je vždy první v seznamu → dostává 100% requestů.
p40-2 je idle i když má model loaded.

Routing flow (`_do_route` krok 2):
```
find_with_model("qwen3-coder-tool:30b") → p40-1 (první match, vždy)
p40-1 není reserved → posíláme tam
p40-2 nedostane nikdy nic
```

### Řešení

`find_with_model` by měl load-balancovat — buď **least-busy** (méně active requests)
nebo **round-robin**:

```python
def find_with_model(self, model: str) -> GpuBackend | None:
    """Find healthy GPU with model — prefer least busy for load balancing."""
    candidates = [b for b in self.healthy_backends if b.has_model(model)]
    if not candidates:
        return None
    return min(candidates, key=lambda b: b.active_request_count())
```

Tím se NORMAL requesty rozloží na obě GPU když mají model loaded.

Pro CRITICAL: `_find_best_gpu_for_critical` už má správnou logiku (hledá GPU
bez active CRITICAL), ale `_do_route` krok 2 (řádek 175) ho obchází — najde
model na p40-1 přes `find_with_model` a pošle tam dřív než se dostane k
`_route_critical`. Opravit: pro CRITICAL přeskočit krok 2, vždy jít přes
`_route_critical` (krok 3).

### Soubory

- `app/gpu_state.py:109-114` — `find_with_model()`
- `app/router_core.py:165-242` — `_do_route()` routing logic


## Poznámka: Přechodné chyby (neopravovat)

- `Correction LLM call failed: peer closed connection` — 1× při redeployi routeru, retry handled
- `LLM call failed: OllamaException - Server disconnected` — 1× při redeployi, transient
