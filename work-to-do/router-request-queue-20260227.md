# Router — interní request queue

**Priorita**: HIGH

## Problém

Router aktuálně funguje **fire-through**: každý request se okamžitě routuje na backend
nebo vrátí 503/CPU fallback. Pokud jsou obě GPU busy (reservace, active requesty),
request buď čeká v `_wait_for_gpu` (blokuje caller 60s) nebo jde na CPU (pomalé pro :30b).

KB a orchestrátor neví a nemají vědět o backendu. Chtějí poslat request a dostat odpověď.
Router by měl fungovat jako **transparent proxy s frontou**.

## Klíčový princip

**Router VŽDY přijme request.** Nikdy nevrací 503/reject. Každý request se zařadí do queue
a router sám řídí vytížení backendů. Calleři (KB, orchestrátor, chat) neřeší routing —
pošlou request a dostanou odpověď.

## Požadavek

### Dvě fronty: CRITICAL (neomezená) + NORMAL (omezená)

```
CRITICAL queue:  [neomezená] — chat, foreground, interaktivní
NORMAL queue:    [max N]     — background, KB ingest, qualification
```

- **CRITICAL se NESMÍ nikdy ztratit ani odmítnout** — queue je neomezená
- NORMAL queue má limit (např. 10) — při překročení vrátí back-pressure calleru
- Router podle stavu obou front řídí přidělování backendů

### Řízení backendů podle queue

Router je **jediný kdo rozhoduje** o přidělení GPU/CPU. Logika:

1. **CRITICAL request v queue** → okamžitě přidělit GPU:
   - Volný GPU slot → přidělit
   - Všechny GPU obsazeny NORMAL → **preemptne** nejstarší NORMAL, uvolní slot
   - Preemptnutý NORMAL se vrátí do NORMAL queue
   - CRITICAL **nikdy nečeká** za NORMAL

2. **Jen NORMAL requesty v queue** → distribuovat na volné backendy:
   - Volný GPU → přidělit
   - Volný CPU (pro 7b/embedding) → přidělit
   - Všechny busy → NORMAL čeká v queue

3. **Obě fronty mají requesty** → CRITICAL first, NORMAL na zbylé kapacity

### Distribuce

- Když je fronta, práce se MUSÍ distribuovat na **všechny** backendy
- Nesmí stát GPU idle když jsou requesty v queue
- `find_with_model` vrací least-busy (opraveno) — queue to doplní o buffering

### CPU backend

- CPU backend JE součástí poolu **pouze pro NORMAL** requesty (7b, embedding)
- **CRITICAL NIKDY na CPU** — ani embedding, ani :30b, nic
- CRITICAL vždy GPU — preemptne NORMAL pokud třeba
- CPU = jen background práce (KB ingest, qualification)

## Návrh implementace

### RequestQueue

```python
class RequestQueue:
    """Two-tier queue: unlimited CRITICAL + bounded NORMAL."""

    def __init__(self, normal_max: int = 10):
        self._critical: asyncio.Queue = asyncio.Queue()      # neomezená
        self._normal: asyncio.Queue = asyncio.Queue(normal_max)
        self._dispatch_event = asyncio.Event()

    async def submit(self, request: TrackedRequest) -> Response:
        """Always accepts. Routes immediately or queues for dispatch."""
        # Try immediate routing
        backend = self._find_available_backend(request)
        if backend:
            return await self._execute(backend, request)

        # Queue — CRITICAL always accepted, NORMAL may block at limit
        future = asyncio.get_event_loop().create_future()
        entry = (request, future)
        if request.priority <= Priority.CRITICAL:
            self._critical.put_nowait(entry)  # neomezená — nikdy nefailne
        else:
            await self._normal.put(entry)     # blokuje při plné frontě

        self._dispatch_event.set()  # probudit dispatcher

        # Pokud CRITICAL a všechny GPU obsazeny NORMAL → preemptne
        if request.priority <= Priority.CRITICAL:
            self._preempt_normal_if_needed()

        return await future  # čeká na přidělení backendu a výsledek
```

### Dispatcher loop

```python
async def _dispatch_loop(self):
    """Background task — přiřazuje requesty z queue na volné backendy."""
    while True:
        await self._dispatch_event.wait()
        self._dispatch_event.clear()

        while True:
            # 1. Vždy nejdřív CRITICAL
            entry = self._try_get(self._critical) or self._try_get(self._normal)
            if not entry:
                break

            request, future = entry
            backend = self._find_available_backend(request)
            if not backend:
                # Vrátit zpět do fronty, čekat na uvolnění
                self._requeue(request, future)
                break

            # Spustit na backendu, výsledek předat do future
            asyncio.create_task(self._execute_and_resolve(backend, request, future))
```

### Signalizace uvolnění backendu

Když backend dokončí request → `_dispatch_event.set()` → dispatcher zkontroluje frontu:

```python
# V route_request finally bloku (po cleanup active_requests):
self._queue._dispatch_event.set()
```

## Omezení: max 2 concurrent requesty na backend

Ollama zvládne 2 paralelní requesty rozumně (context splitting). Víc degraduje výkon
všech requestů — nesmí se posílat víc než 2 na jeden backend.

```
max_concurrent_per_backend = 2

Celková kapacita = 2 GPU × 2 + 1 CPU × 2 = 6 slotů
(reálně :30b jen na GPU → 4 sloty pro velké modely)
```

Dispatcher kontroluje `backend.active_request_count() < max_concurrent_per_backend`
před přidělením.

## Timeouty callerů

Protože request teď může čekat v queue, calleři musí mít **dostatečný timeout**:

- Orchestrátor/KB volají router přes HTTP — timeout musí pokrýt:
  `čekání v queue + inference time`
- Aktuálně orchestrátor má 300s (`HEARTBEAT_DEAD_SECONDS`) — to by mělo stačit
- KB má `proxy_connect_timeout_s: 10` — **příliš krátké** pro queue, zvýšit na 30+
- Router sám timeout nemá — request čeká v queue dokud se nezpracuje nebo caller nedisconnectne
- Client disconnect monitoring (existující `_monitor_client_disconnect`) funguje i pro
  queued requesty — pokud caller zruší HTTP spojení, request se odstraní z queue

## Soubory

- `app/router_core.py` — přidat `RequestQueue`, nahradit `_do_route` za `queue.submit()`
- `app/gpu_state.py` — callback při dokončení requestu (event pro dispatcher)
- `app/config.py` — `normal_queue_max: int = 10`, `max_concurrent_per_backend: int = 2`
