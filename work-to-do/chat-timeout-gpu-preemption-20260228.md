# Chat timeout — background task nepreemptuje GPU pro CRITICAL chat

**Priorita**: HIGH
**Status**: DONE

---

## Problém

Chat dostal `TokenTimeoutError` po 300s protože GPU byla obsazena background taskem.
Při příchodu CRITICAL chatu by se měl background task na GPU okamžitě zastavit.

### Přesný scénář

1. Background task běží na GPU (NORMAL priority)
2. Přijde chat request (CRITICAL priority, `X-Ollama-Priority: 0`)
3. Chat čeká 300s na GPU → timeout
4. Background task mezitím dokončí a nový se spustí

### Co se mělo stát

1. Chat přijde → router vidí CRITICAL ve frontě
2. Router preemptuje NORMAL request na GPU (cancel_event)
3. Orchestrator/background handler detekuje preemption → zastaví iteraci
4. Chat dostane GPU okamžitě

## Dva problémy

### A) Router preemption nefunguje spolehlivě

Router má `_preempt_normal_for_critical()` ale preemption neproběhla:
- Server log: `PREEMPT_FAILED: Orchestrator returned false for interrupt`
- Router nedokázal zrušit GPU request background tasku

### B) Zastavení tasku nezastaví čekající GPU request

Pokud orchestrator zastaví/přeruší task, čekající request v router queue se NEODEBERE.
Request zůstane ve frontě a blokuje GPU.

**Řešení**: Orchestrator musí při zastavení tasku:
1. Cancel probíhající HTTP request na router (close connection)
2. Router musí na client disconnect odebrat request z queue

## Logy

```
PYTHON_ORCHESTRATOR_INTERRUPT_FAILED: threadId=... error=No active task found
PREEMPT_FAILED: Orchestrator returned false for interrupt
Chat: TokenTimeoutError: LLM blocking call timed out after 300s
CLIENT_DISCONNECT: id=f2dedf17 — setting cancel_event
```

## Soubory

- `backend/service-ollama-router/app/request_queue.py` — preemption, queue management
- `backend/service-orchestrator/app/background/handler.py` — task cancellation
- `backend/service-orchestrator/app/llm/provider.py` — HTTP request lifecycle
- `backend/server/.../service/background/BackgroundEngine.kt` — interrupt/preempt logic
