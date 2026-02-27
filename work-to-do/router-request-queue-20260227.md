# Router — interní request queue

**Priorita**: HIGH

## Problém

Router aktuálně funguje **fire-through**: každý request se okamžitě routuje na backend
nebo vrátí 503/CPU fallback. Pokud jsou obě GPU busy (reservace, active requesty),
request buď čeká v `_wait_for_gpu` (blokuje caller 60s) nebo jde na CPU (pomalé pro :30b).

KB a orchestrátor neví a nemají vědět o backendu. Chtějí poslat request a dostat odpověď.
Router by měl fungovat jako **transparent proxy s malou frontou**.

## Požadavek

### Interní queue (buffer)

- Router má **N slotů** kde N = počet backendů (2 GPU + 1 CPU = 3)
- Každý slot = 1 concurrent request per backend
- Příchozí request:
  1. Volný backend → route okamžitě (jako teď)
  2. Všechny backendy busy, ale queue má místo → přidat do queue, čekat na volný backend
  3. Queue plná → teprve teď caller čeká (back-pressure)

### Distribuce

- Když je fronta, práce se MUSÍ distribuovat na všechny backendy
- Nesmí stát GPU idle když jsou requesty v queue
- `find_with_model` už vrací least-busy (opraveno), ale queue zajistí lepší throughput

### Prioritní řazení v queue

- CRITICAL requesty jdou na začátek fronty (před NORMAL)
- CRITICAL embedding → CPU pokud GPU dělá :30b inference (existující logika)

### CPU backend

- CPU backend JE součástí poolu pro modely kde to dává smysl (7b, embedding)
- CPU backend se nepoužívá pro :30b (existující logika step 6)

## Návrh implementace

```python
class RequestQueue:
    """Internal request buffer — distributes across backends as they become available."""

    def __init__(self, max_per_backend: int = 1):
        self._queue: asyncio.PriorityQueue = asyncio.PriorityQueue()
        self._max_per_backend = max_per_backend

    async def submit(self, request: TrackedRequest) -> Response:
        """Submit request — routes immediately if backend available, else queues."""
        # Try immediate routing first
        backend = self._find_available_backend(request)
        if backend:
            return await self._execute(backend, request)

        # Queue and wait for backend
        event = asyncio.Event()
        self._queue.put_nowait((request.priority.value, time.monotonic(), request, event))
        await event.wait()
        # backend assigned by dispatcher
        return await self._execute(request.assigned_backend, request)
```

### Dispatcher loop

Background task sleduje backendy. Když backend dokončí request → vezme další z queue:

```python
async def _dispatch_loop(self):
    while True:
        # Wait for any backend to become available
        await self._backend_available_event.wait()

        # Pop highest priority request from queue
        _, _, request, event = await self._queue.get()
        backend = self._find_available_backend(request)
        request.assigned_backend = backend
        event.set()  # unblock the waiting submit()
```

## Soubory

- `app/router_core.py` — přidat `RequestQueue`, integrovat do `_do_route`
- `app/gpu_state.py` — signalizace když backend dokončí request (callback/event)
- `app/config.py` — `max_concurrent_per_backend: int = 1` (konfigurovatelné)
