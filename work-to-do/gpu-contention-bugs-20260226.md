# BUG: GPU contention — chat timeout + zombie requesty + graph extraction failures

**Datum:** 2026-02-26
**Priorita:** HIGH
**Typ:** BUG (3 propojené problémy)

---

## Bug 1: Chat timeout způsobený embedding floodem (HIGH)

### Popis

KB write servis spustil 35 paralelních embedding requestů (`qwen3-embedding:8b`), které obsadily GPU.
Foreground chat (`qwen3-coder-tool:30b`, CRITICAL priority) musel čekat na uvolnění GPU.
Router se snažil unloadnout embedding model, ale nemohl (35 aktivních requestů).
Po 60s forced unload, ale chat LLM call stejně překročil 300s timeout.

### Logy

```
08:09:09 REQUEST_IN: id=a1be14ad priority=CRITICAL path=/api/generate
08:09:09 WARNING GPU p40 has :30b + other models, unloading extras
08:10:09 WARNING GPU p40 still has 35 active requests after 60s wait, unloading anyway
08:14:08 Chat handler error: LLM blocking call timed out after 300s (tier=local_standard)
```

### Příčina

KB graph extraction posílá embedding requesty bez rate limitu. 35 najednou → GPU starvation.

### Navrhované řešení

1. **KB embedding concurrency limit** — max 5 paralelních embedding requestů v KB write servisu
2. **Router: CRITICAL preemption** — CRITICAL request okamžitě pauzne NORMAL embedding requesty
3. **Případně**: oddělení embedding na CPU (8b model zvládne i CPU)

---

## Bug 2: Router nesleduje cancellation — zombie requesty (MEDIUM)

### Popis

Když orchestrátor timeout'ne (300s), zruší HTTP spojení, ale router to nedetekuje.
Requesty zůstávají v routeru jako "aktivní" (žádný REQUEST_OUT v logu).
GPU zpracovává requesty, jejichž výsledek nikdo nečeká.

### Logy

```
08:03:16 REQUEST_IN: id=2ce5497c priority=NORMAL  ← nikdy REQUEST_OUT
08:06:16 REQUEST_IN: id=98a5c93c priority=NORMAL  ← nikdy REQUEST_OUT
08:09:09 REQUEST_IN: id=a1be14ad priority=CRITICAL ← nikdy REQUEST_OUT
08:10:38 REQUEST_IN: id=88aaa790 priority=NORMAL  ← nikdy REQUEST_OUT
08:13:38 REQUEST_IN: id=417bc6ef priority=NORMAL  ← nikdy REQUEST_OUT
```

5 requestů bez REQUEST_OUT = GPU plýtvá výpočetním časem na zombie requesty.

### Navrhované řešení

1. **Router: detect client disconnect** — pokud caller zavře spojení, cancel proxy request na Ollama
2. **Router: request timeout** — max 300s per request, pak cancel + REQUEST_OUT(timeout)
3. **Router: active request dashboard** — logovat počet aktivních requestů periodicky

---

## Bug 3: Graph extraction LLM failures — prázdný error (LOW)

### Popis

KB graph write opakovaně selhává s prázdným error message:

```
GRAPH_WRITE: LLM_EXTRACTION_FAILED sourceUrn=email:698f875f8d8de7e503d137d1 error=
```

Selhání se opakuje každé ~3 minuty (08:06, 08:09, 08:13) — pravděpodobně retry loop.
Prázdný error naznačuje timeout nebo prázdnou odpověď od LLM (GPU zahlcena).

### Navrhované řešení

1. **Log plný error** — `error=` by měl obsahovat exception message, ne prázdný string
2. **Backoff při opakovaném selhání** — po 3 selháních na stejném sourceUrn přestat retryovat
3. **Pravděpodobně se vyřeší opravou Bug 1** — pokud embedding nefloodni GPU, LLM extraction nebude timeouttovat

---

## Časová osa incidentu

| Čas | Událost |
|-----|---------|
| 08:03:16 | KB graph extraction request (NORMAL) |
| 08:06:16 | Další graph extraction (NORMAL) + extraction failure |
| 08:08:58 | Chat request přijat, KB search OK, 35 embedding requestů na GPU |
| 08:09:08 | Chat LLM call zahájen (CRITICAL, timeout=300s) |
| 08:09:09 | Router: GPU má :30b + embedding, snaha o unload |
| 08:10:09 | Router: forced unload po 60s čekání |
| 08:10:38 | Jeden request dokončen (82s) |
| 08:12:09 | Router: failed to unload embedding model |
| 08:14:08 | **Chat timeout po 300s** |

## Kontext

- GPU: NVIDIA P40, 24GB VRAM
- Model: qwen3-coder-tool:30b (Q4_K_M, 21.9GB VRAM)
- Embedding: qwen3-embedding:8b — koexistuje s :30b na GPU
- Chat priority: CRITICAL (`X-Ollama-Priority: 0`)
- Background/KB priority: NORMAL
