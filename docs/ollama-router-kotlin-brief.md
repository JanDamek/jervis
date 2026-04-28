# Brief: přepsat ollama-router z Python do Kotlin

**Status:** TODO. Aktuálně Python `service-ollama-router` se opakovaně
zasekává (zombie requesty v `gpu.active_requests`, dispatcher loop
nedrenuje frontu). Reaper failsafe + 3 race fixy nasazeny dnes
(commits `38bd8663f`, `b033fb77c`, `184c69b42`, `8fb1a8779`), zombies
přesto vznikají v intervalech 30 min — symptom je zaseklá fronta
`{llm:N, vlm:M}` pozorovaná v `ACTIVE_REQUESTS` logu.

User explicit (2026-04-28): "router je opět zaseklý? sepiš kompletní
zadání na přepis router do Kotlin, je to aktuálně největší problém.
žádná perzistence, jen thread v queue až do volání modelu a odpověd."

Per CLAUDE.md memory `feedback-kotlin-first.md`: agentic = Python,
non-agentic = Kotlin. Router je čistý proxy + queue + GPU coordination,
**bez LLM rozhodování** = patří do Kotlin.

## Cíle (must-have)

1. **Žádná perzistence**. Žádná Mongo collection, žádný Redis stream.
   Vše in-memory. Pod restart = aktivní streamy se rozpadnou, klient
   gRPC vidí `UNAVAILABLE` a retry-uje sám.
2. **Klientské vlákno drží request přes celou dobu**. Klient gRPC stream
   čeká, dokud worker pošle odpověď. Žádný callback, žádný poll.
3. **Žádné hard timeouty na server-side stream** (per CLAUDE.md).
   Connection loss detekuje OkHttp / Ktor (TCP RST, EPIPE). Klient má
   svůj client-side chunk-idle timeout (orchestrator už má
   `TOKEN_TIMEOUT_SECONDS = 120s`).
4. **Strukturovaná concurrency** — `coroutineScope { ... }`
   garantuje, že když parent skončí, všichni children dostanou
   `CancellationException` a finally proběhne. Tím odpadají race
   conditions, které jsme dnes lovili v Pythonu.
5. **Type safety** — sealed `RequestState`, sealed `Priority`,
   `data class` s exhaustive `when`. Bug `_Message.model_dump`
   z dnešního commitu `a33a1aed9` by v Kotlin nebyl možný.

## Architektura (high-level)

```
gRPC client (orchestrator, KB, …)
   │  RouterInferenceService.Chat / Generate / Embed (h2c)
   ▼
KtorServerCoroutineDispatcher
   │
   ▼
RequestRouter
   │  - validateRequest, capability/tier resolution
   │  - find_slot fast path (Mutex-protected)
   │  - slow path: Channel<RequestEnvelope>(unlimited) + per-group dispatch
   ▼
GpuPool (per-backend mutex + Mutex.lock around active_requests)
   │
   ▼
OllamaProxy (Ktor HttpClient + Flow<NDJSON>)
   │  - emit chunks via SendChannel<ChatChunk>
   │  - finally: cleanup_backend, notify_slot_freed
   ▼
gRPC stream odpověď klientovi
```

### Klíčové kontrakty

- **`Channel<RequestEnvelope>(capacity = UNLIMITED)`** per QueueGroup
  (LLM / VLM / EMBED). Capacity unlimited protože klient čeká na své
  response — žádný backpressure proti klientovi.
- **`Mutex` per `GpuBackend`** chrání `activeRequests: MutableMap<RequestId, RunningRequest>` + `reservedBy`. Žádné race window jako v Pythonu (`_pre_claim_slot` vs `_dispatch_to_backend`).
- **`coroutineScope { launch { … } }`** uvnitř `dispatchOne(request)`:
  klient suspend `request.completion.await()`, worker emit chunks do
  `request.outChannel`, klient consume.
- **Cleanup deterministický**: každý `try/finally` v Kotlin coroutines
  proběhne i při `CancellationException` (na rozdíl od Python asyncio
  generator GC).
- **No reaper potřeba** — pokud klient cancel-uje (gRPC server
  `context.cancelled`), parent scope zruší worker child, finally fire.

## Component breakdown

| Soubor | Odpovědnost |
|---|---|
| `RouterApplication.kt` | Spring Boot main, Ktor binding |
| `RouterInferenceServiceImpl.kt` | grpc-kotlin servicer pro `Chat`, `Generate`, `Embed` (proto v `proto/jervis/router/v1/`) |
| `OpenrouterProxyServiceImpl.kt` | Pro free-tier OpenRouter cloud fallback (zachovat funkcionalitu) |
| `GpuPool.kt` | `data class GpuBackend(name, url, vramGb, ...)`, mutex, `acquireSlot/releaseSlot` (replace `_pre_claim_slot`) |
| `RequestQueue.kt` | per-group `Channel<RequestEnvelope>` + dispatcher coroutines |
| `RequestRouter.kt` | top-level routing + capability resolution (replace `RouterCore`) |
| `OllamaProxy.kt` | Ktor `HttpClient` POST `/api/chat` / `/api/generate` / `/api/embed`, parse NDJSON → `Flow<JsonObject>` |
| `ModelLoader.kt` | `loadModel(gpu, model)` — Ollama `/api/show` + warmup ping |
| `WhisperCoordinator.kt` | preempt-by-whisper signals (zachovat) |
| `ReservationGuard.kt` | CRITICAL reservation idle/absolute timeout (zachovat, simplified) |

## Domain model

```kotlin
@JvmInline value class RequestId(val value: String)

enum class Priority { CASCADE, CRITICAL, NORMAL, BACKGROUND }
enum class QueueGroup { EMBED, LLM, VLM }
enum class GpuName { P40_1, P40_2 }

sealed interface RequestState {
    data object Queued : RequestState
    data class Running(val gpu: GpuName, val startedAt: Instant) : RequestState
    data class Preempted(val reason: PreemptReason, val emittedChunks: Int) : RequestState
    data object Completed : RequestState
    data class Failed(val cause: ProxyError) : RequestState
}

data class RequestEnvelope(
    val id: RequestId,
    val priority: Priority,
    val capability: Capability,
    val model: String,
    val body: JsonObject,
    val deadline: Instant?,
    val outbox: SendChannel<ChatChunk>,   // server-side gRPC stream sink
    val state: AtomicReference<RequestState>,
    val cancelToken: CompletableJob,      // klient cancel signál
)

sealed class ProxyError : RuntimeException() {
    data object UnknownBackend : ProxyError()
    data class UpstreamError(val status: Int, val body: String) : ProxyError()
    data class StuckBackend(val ms: Long) : ProxyError()
    data class GpuExhausted(val model: String) : ProxyError()
}
```

## Streaming flow (no race conditions)

```kotlin
suspend fun handleChat(request: RequestEnvelope): Unit = coroutineScope {
    val gpu = gpuPool.acquireSlot(request)
        ?: run {
            requestQueue[request.queueGroup].send(request)
            request.cancelToken.join()  // čekáme na dispatcher pickup
            return@coroutineScope
        }

    try {
        val httpFlow: Flow<JsonObject> = ollamaProxy.streamChat(gpu, request)
        httpFlow.collect { chunk ->
            request.outbox.send(chunk.toGrpcChunk())
        }
    } catch (e: PreemptedByCriticalError) {
        // re-submit do fronty
        requestQueue[request.queueGroup].send(request)
    } catch (e: PreemptedByWhisperError) {
        whisperCoordinator.waitForDone()
        requestQueue[request.queueGroup].send(request)
    } finally {
        gpuPool.releaseSlot(gpu, request.id)
    }
}
```

`coroutineScope` garantuje:
- klient cancel → scope cancelled → `httpFlow.collect` dostane
  `CancellationException` → finally proběhne deterministicky
- pod restart → JVM SIGTERM → Ktor close → všechny scope-y cancelled
  jednotně

## Watchdogs (stejná logika, less code)

- **Reservation idle timeout** — `Job` co `delay(60_000)` po každém
  CRITICAL request, releasne reservation. Žádný separate task loop.
- **Active requests logger** — `flow { while (true) { delay(30_000); emit(snapshot()) } }`
  jen když total > 0.
- **Reaper failsafe** — **NEBUDE potřeba** v Kotlin verzi. Structured
  concurrency garantuje cleanup. Pokud v testu jednou zombie objeví,
  je to bug v naší cestě, ne v Pythonu, takže fixneme přímo.

## Migrace strategy

### Fáze 1 — koexistence (paralelní deploy)
- Nový pod `jervis-ollama-router-kt` na portu `:11431` (klienti `:11430`)
- Postupně přepínat klientské konfigurace na `:11431`

### Fáze 2 — full cutover
- Když všichni klienti běží proti `:11431`, přejmenovat service na
  `jervis-ollama-router` a smazat Python deployment

### Fáze 3 — cleanup
- Smazat `backend/service-ollama-router/`
- Update `docs/architecture.md` URL tables
- Update `k8s/build_ollama_router.sh` na Kotlin Gradle build

## Testovací plán

| Scenario | Očekávané chování |
|---|---|
| Klient cancel mid-stream | Worker scope cancelled, finally release slot, žádný zombie |
| Pod restart během 5 active streamů | Klienti dostanou `UNAVAILABLE`, retry, nový pod přijme |
| CRITICAL reservation idle | Po 60s release, dispatcher pokračuje |
| Stuck Ollama backend | Klient timeout (120s) → cancel → finally ✓ (server nemá vlastní timeout) |
| 3 paralel CRITICAL na 1 GPU | First wins slot, ostatní v queue. FIFO podle priority |
| Whisper preempt | Stream raise PreemptedByWhisperError → re-submit (žádné dispatcher hangy) |

## Out of scope

- ❌ Persistent queue (Mongo/Redis) — user explicit "žádná perzistence"
- ❌ Multi-replica scaling — `replicas: 1` zachovat (state in-memory)
- ❌ HA failover — pod down = retry klient, žádný hot standby
- ❌ Distributed tracing přes pody — log-based monitoring stačí

## Existující kontrakty zachovat

- gRPC `RouterInferenceService.Chat / Generate / Embed` (proto schema beze změn)
- `RequestContext` payload struktura
- OpenRouter cloud fallback (free tier)
- GPU model sets (`p40-1`, `p40-2`) v configmapu
- Whisper coordination (preempt rules)
- VRAM coordination s XTTS

## Acceptance criteria

1. ✅ Build úspěšný přes `bash k8s/build_ollama_router_kt.sh`
2. ✅ Klient (orchestrator + KB) bez code change zaregistrován
3. ✅ Žádný `STALE_REQUEST_EVICT` log (reaper smazán protože není
   potřeba)
4. ✅ Pod restart během streamů → klienti dostanou error → retry success
5. ✅ Test 4-hodinová zátěžová session, žádné zaseklé queues

## Effort estimate

User pravidlo: bez time estimates. Dependencies:
- Phase 1 (Foundation): GpuPool + RequestQueue + RequestRouter
- Phase 2 (Streaming): OllamaProxy + gRPC servicer
- Phase 3 (Coordinators): Whisper + Reservation
- Phase 4 (Migration): paralelní deploy + cutover
