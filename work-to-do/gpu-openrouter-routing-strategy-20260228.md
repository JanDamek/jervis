# GPU + OpenRouter — strategie routování LLM requestů

**Priorita**: CRITICAL
**Status**: OPEN

---

## Současný stav

- **P40 GPU**: 2× 24GB VRAM, model 30b (~17.8GB) + embedding 8b (~5.7GB) = ~23.5GB → nad 32k context spill do CPU RAM → timeout 300s
- **OpenRouter**: nakonfigurovaný (API key, modely, fallback chains v UI), ale orchestrator používá hardcoded `"openrouter/auto"` a eskaluje teprve PO vyčerpání VŠECH lokálních tierů
- **autoUseOpenrouter**: per client/project flag existuje, ale routing je reaktivní (fail → escalate), ne proaktivní
- **Problém**: chat iterace 4 šla na p40-2 s plnou VRAM → 300s timeout → uživatel nedostane odpověď

## Požadavky

- Bankovní/citlivý klienti = **POUZE P40** (autoUseOpenrouter=false)
- Většina klientů = **P40 + OpenRouter** (autoUseOpenrouter=true)
- Model group per client/project z OpenRouterSettingsDto (UI už nastaveno)
- Chat (CRITICAL) musí dostat odpověď rychle — nesmí čekat na přeplněnou GPU

---

## Navrhovaná řešení (5 variant)

### Varianta A: "Overflow" — P40 primární, OpenRouter jako přepad

```
Request → P40 volná? → ANO → P40
                      → NE  → autoUseOpenrouter? → ANO → OpenRouter (model group klienta)
                                                  → NE  → čekej ve frontě na P40
```

**Pro**: Jednoduché, P40 se využije naplno, OpenRouter jen když je potřeba.
**Proti**: První request zabere P40, druhý musí čekat na routing decision.
**Implementace**: Router `request_queue.py` — pokud queue_depth > 0 a client má OpenRouter, vrátit HTTP header `X-Route-To: openrouter`. Orchestrator pak zavolá OpenRouter přímo.

---

### Varianta B: "P40 první slot, OpenRouter zbytek" — round-robin s preferencí

```
Request → P40 má volný slot? → ANO → P40 (max 1 concurrent CRITICAL)
                              → NE  → autoUseOpenrouter? → ANO → OpenRouter
                                                          → NE  → fronta P40
```

**Rozdíl od A**: P40 drží max 1 CRITICAL request. Jakýkoli další CRITICAL jde rovnou na OpenRouter (ne do fronty).

**Pro**: Chat nikdy nečeká. P40 se využije, ale neblokuje.
**Proti**: P40-2 může být idle zatímco OpenRouter platí.
**Implementace**: Orchestrator `provider.py` — před LLM callem zkontrolovat router `/status` endpoint pro queue depth.

---

### Varianta C: "Kontext-based routing" — malý context P40, velký OpenRouter

```
estimated_tokens < 16k → P40 (rychlé, žádný spill)
estimated_tokens 16k-48k → P40 pokud volná, jinak OpenRouter
estimated_tokens > 48k → OpenRouter přímo (P40 by spillovala)
```

**Pro**: Eliminuje VRAM spill problém. P40 dělá jen to co zvládne rychle.
**Proti**: Větší context = větší cena na OpenRouter. Neřeší obsazenost P40.
**Implementace**: Orchestrator `provider.py` v `_select_tier()` — přidat context threshold.

---

### Varianta D: "P40 = background, OpenRouter = foreground chat"

```
CRITICAL (chat) → autoUseOpenrouter? → ANO → OpenRouter přímo (žádné čekání)
                                      → NE  → P40 (preemptne NORMAL)
NORMAL (background tasks) → P40 vždy
IDLE (deadline scan) → P40 vždy
```

**Pro**: Chat je vždy rychlý. P40 se plně využije na background analýzy kde timeout nevadí.
**Proti**: OpenRouter stojí peníze i když P40 je volná. Plýtvání lokální GPU pro chat.
**Vylepšení**: Chat → P40 pokud volná, jinak OpenRouter.

---

### Varianta E: "Hybridní fronta s dynamickým routingem" (DOPORUČENÁ)

Kombinace A + C + D:

```
                    ┌─────────────────────────────────────────────┐
                    │            ROUTING DECISION                  │
                    │                                             │
Request ────────────┤  1. autoUseOpenrouter = false?              │
                    │     → VŽDY P40 (fronta, preemption)         │
                    │                                             │
                    │  2. estimated_tokens > 48k?                 │
                    │     → OpenRouter přímo (P40 by spillovala)  │
                    │                                             │
                    │  3. P40 volná (queue_depth = 0)?            │
                    │     → P40 (nejrychlejší, zdarma)            │
                    │                                             │
                    │  4. CRITICAL + P40 busy?                    │
                    │     → OpenRouter (chat nečeká)              │
                    │                                             │
                    │  5. NORMAL + P40 busy?                      │
                    │     → fronta P40 (background počká)          │
                    │                                             │
                    │  6. Timeout na P40 (>60s)?                  │
                    │     → cancel + retry na OpenRouter           │
                    └─────────────────────────────────────────────┘
```

**Pro**:
- Využije P40 naplno (zdarma, rychlé pro malý context)
- Chat nikdy nečeká (OpenRouter jako okamžitý fallback)
- Background neplýtvá OpenRouter kredity
- Respektuje client policy (banking = P40 only)
- Context-aware (velký context rovnou na cloud)

**Proti**: Složitější implementace.

---

## Doporučení: Varianta E (hybridní)

### Implementační kroky

#### 1. Router — nový endpoint `/queue-status`

```python
# backend/service-ollama-router/app/main.py
@app.get("/queue-status")
async def queue_status():
    return {
        "critical_queue": len(queue.critical),
        "normal_queue": len(queue.normal),
        "gpu_busy": [g.id for g in gpus if g.reserved],
        "gpu_free": [g.id for g in gpus if not g.reserved],
    }
```

#### 2. Orchestrator — OpenRouter settings loader

```python
# backend/service-orchestrator/app/llm/openrouter_resolver.py
async def resolve_openrouter_model(
    client_id: str,
    project_id: str | None,
    use_case: str,  # "chat", "coding", "reasoning", etc.
) -> str | None:
    """Fetch OpenRouter model from settings (cached).
    Returns model ID (e.g. 'anthropic/claude-sonnet-4') or None.
    """
    settings = await kotlin_client.get_openrouter_settings()
    if not settings or not settings.get("enabled"):
        return None
    models = settings.get("models", [])
    # Filter by use_case preference
    for model in models:
        if use_case in model.get("preferredFor", []):
            return model["modelId"]
    # Fallback to first model
    return models[0]["modelId"] if models else "openrouter/auto"
```

#### 3. Orchestrator — routing decision v provider.py

```python
# backend/service-orchestrator/app/llm/provider.py
async def select_route(
    estimated_tokens: int,
    priority: str,  # "CRITICAL" | "NORMAL" | "IDLE"
    auto_providers: set[str],
    use_case: str,
    client_id: str,
    project_id: str | None,
) -> Route:
    has_openrouter = "openrouter" in auto_providers

    # Rule 1: No OpenRouter → always local
    if not has_openrouter:
        return Route(target="local", tier=select_local_tier(estimated_tokens))

    # Rule 2: Large context → OpenRouter directly
    if estimated_tokens > 48_000:
        model = await resolve_openrouter_model(client_id, project_id, use_case)
        return Route(target="openrouter", model=model)

    # Rule 3: Check GPU availability
    queue_status = await get_router_queue_status()
    gpu_free = len(queue_status["gpu_free"]) > 0

    # Rule 4: GPU free → use it
    if gpu_free:
        return Route(target="local", tier=select_local_tier(estimated_tokens))

    # Rule 5: CRITICAL + GPU busy → OpenRouter (no waiting)
    if priority == "CRITICAL":
        model = await resolve_openrouter_model(client_id, project_id, use_case)
        return Route(target="openrouter", model=model)

    # Rule 6: NORMAL/IDLE → queue on local GPU
    return Route(target="local", tier=select_local_tier(estimated_tokens))
```

#### 4. Chat handler — integrovat routing

```python
# backend/service-orchestrator/app/chat/handler_agentic.py
# Před každou LLM iterací:
route = await select_route(
    estimated_tokens=estimated_tokens,
    priority="CRITICAL",  # Chat je vždy CRITICAL
    auto_providers=auto_providers,
    use_case="chat",
    client_id=client_id,
    project_id=project_id,
)
if route.target == "openrouter":
    response = await openrouter_completion(route.model, messages, tools)
else:
    response = await local_completion(route.tier, messages, tools)
```

#### 5. Background handler — integrovat routing

```python
# Pro background tasks: priority="NORMAL"
# → vždy jde na P40 (čeká ve frontě)
# → eskaluje na OpenRouter jen po opakovaném timeout
```

#### 6. Timeout fallback — cancel + retry

```python
# V provider.py _blocking_completion():
try:
    response = await asyncio.wait_for(call, timeout=60)  # Kratší timeout pro první pokus
except asyncio.TimeoutError:
    if has_openrouter:
        logger.info("Local GPU timeout, falling back to OpenRouter")
        # Cancel local request
        await cancel_router_request(request_id)
        # Retry on OpenRouter
        model = await resolve_openrouter_model(...)
        response = await openrouter_completion(model, messages, tools)
    else:
        # No OpenRouter → wait full 300s
        response = await asyncio.wait_for(call, timeout=240)
```

### Soubory

- `backend/service-ollama-router/app/main.py` — `/queue-status` endpoint
- `backend/service-orchestrator/app/llm/provider.py` — routing decision, timeout fallback
- `backend/service-orchestrator/app/llm/openrouter_resolver.py` — NOVÝ: fetch model z settings
- `backend/service-orchestrator/app/chat/handler_agentic.py` — route selection per iterace
- `backend/service-orchestrator/app/chat/handler_streaming.py` — OpenRouter streaming support
- `backend/service-orchestrator/app/background/handler.py` — background routing (P40 preferred)
- `backend/service-orchestrator/app/tools/kotlin_client.py` — get_openrouter_settings()
- `backend/server/.../rpc/internal/InternalEnvironmentRouting.kt` — expose OpenRouter settings internally

### Ověření

1. Chat s `autoUseOpenrouter=true` + P40 busy → odpověď přes OpenRouter do 10s
2. Chat s `autoUseOpenrouter=false` (banking) → čeká na P40, preemptne NORMAL
3. Chat + P40 volná → jde na P40 (zdarma, rychlé)
4. Background task → vždy P40 (šetří OpenRouter kredity)
5. Context > 48k → rovnou OpenRouter (P40 by spillovala)
6. P40 timeout 60s → automatic fallback na OpenRouter
7. OpenRouter model = dle settings klienta/projektu, ne hardcoded "auto"
