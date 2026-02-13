# BUG: RpcClient was cancelled - UI aplikace se nereconnectuje po restartu serveru

## Popis problému

Po redeploymentu server podu (nebo crash) **UI aplikace (Desktop/Mobile) přestane fungovat** s chybou "RpcClient was cancelled" a **NEOBNOVÍ** automaticky spojení. Uživatel musí restartovat celou aplikaci.

## Root Cause

Commit `ae851fc1` (fix: remove invalid coroutineContext access in RpcConnectionManager) odstranil connection monitoring logiku, ale **neimplementoval náhradu**. Výsledek: když WebSocket spojení umře, nikdo netriggerne reconnect.

### Co se stalo v commitu ae851fc1:

#### Před (funkční, ale s chybou):
```kotlin
private fun monitorConnection(client: KtorRpcClient) {
    scope.launch {
        try {
            // Toto způsobovalo crash - KtorRpcClient nemá coroutineContext property
            client.coroutineContext[kotlinx.coroutines.Job]?.join()
        } catch (_: Exception) {}

        println("RpcConnectionManager: Connection lost, triggering reconnect...")
        _state.value = RpcConnectionState.Disconnected
        reconnect()  // ← AUTOMATICKY RECONNECTOVAL
    }
}
```

#### Po (nefunkční - žádný reconnect):
```kotlin
private fun monitorConnection(client: KtorRpcClient) {
    // Connection monitoring via periodic health checks or error callbacks
    // For now, rely on resilientFlow error handling to trigger reconnects
    // Future: implement periodic ping or use WebSocket close callback

    // ← PRÁZDNÉ - nic se neděje!
}
```

### Proč resilientFlow error handling NEfunguje:

`RpcConnectionManager.kt:193-199`:
```kotlin
subscribe(services).catch { e ->
    if (e is CancellationException) throw e
    println("RpcConnectionManager: Stream error: ${e.message}")
    // Don't retry here — the monitorConnection will detect the dead
    // connection, trigger reconnect, bump generation, and flatMapLatest
    // will restart this stream automatically.

    // ↑ Tento komentář je LŽE - monitorConnection nic nedělá!
},
```

**resilientFlow očekává, že monitorConnection detekuje dead connection a zavolá reconnect(). Ale monitorConnection je prázdná funkce.**

## Scénář selhání

1. ✅ UI aplikace se připojí k serveru (WebSocket /rpc)
2. ✅ `resilientFlow` subscribuje event streamy (notifikace, progress, atd.)
3. ❌ Server pod se restartuje (`kubectl rollout restart`, crash, deployment)
4. ❌ WebSocket spojení umře
5. ❌ `resilientFlow.catch` zachytí error: "RpcClient was cancelled"
6. ❌ `monitorConnection` nic neudělá (je prázdná)
7. ❌ `reconnect()` se **NIKDY NEZAVOLÁ**
8. ❌ `_generation` se **NEZVÝŠÍ** → `flatMapLatest` **NERESUBSCRIBUJE**
9. ❌ UI zůstane navždy "disconnected", uživatel musí restartovat aplikaci

## Kde je chyba

**Soubor**: `shared/domain/src/commonMain/kotlin/com/jervis/di/RpcConnectionManager.kt`

**Řádky**: 154-158 (funkce `monitorConnection`)

## Jak to opravit

### Možnost A: WebSocket close callback (preferováno)

Implementovat callback, který detekuje když WebSocket umře:

```kotlin
private fun monitorConnection(client: KtorRpcClient) {
    // Ktor WebSocket má onClose callback - použít ten
    // Alternativně: HttpClient má plugin WebSockets s lifecycle hooks

    scope.launch {
        // Čekat na close event z WebSocket
        // Když WebSocket umře:
        println("RpcConnectionManager: Connection lost, triggering reconnect...")
        _state.value = RpcConnectionState.Disconnected
        _generation.value++  // Bump generation → flatMapLatest restartuje streamy
        reconnect()
    }
}
```

**PROBLÉM**: `KtorRpcClient` neexponuje WebSocket lifecycle. Možná řešení:
- Přístup k internal WebSocket přes reflection (ošklivé)
- Periodic ping/health check (možnost B)
- Upravit kRPC library aby exponovala lifecycle hooks

### Možnost B: Periodic health check (jednodušší)

```kotlin
private fun monitorConnection(client: KtorRpcClient) {
    scope.launch {
        while (currentServices != null && rpcClient == client) {
            delay(5000) // Check každých 5s

            try {
                // Ping server (minimální RPC call)
                httpClient?.get("$baseUrl/health")
            } catch (e: Exception) {
                // Connection ded
                println("RpcConnectionManager: Health check failed, triggering reconnect...")
                _state.value = RpcConnectionState.Disconnected
                _generation.value++
                reconnect()
                break
            }
        }
    }
}
```

### Možnost C: resilientFlow triggers reconnect (nejjednoduší patch)

Změnit `resilientFlow` aby sám triggnul reconnect:

```kotlin
subscribe(services).catch { e ->
    if (e is CancellationException) throw e
    println("RpcConnectionManager: Stream error: ${e.message}")

    // Trigger reconnect directly instead of waiting for monitorConnection
    _state.value = RpcConnectionState.Disconnected
    scope.launch {
        _generation.value++
        reconnect()
    }
},
```

**PROBLÉM**: Každý stream by triggnul reconnect samostatně → race condition, zbytečné duplicate reconnect calls.

## Doporučení

**Implementovat Možnost B (Periodic health check)** jako krátkodobé řešení.

Dlouhodobě: zjistit jak přistoupit k WebSocket lifecycle v kRPC/Ktor a implementovat Možnost A.

## Souvisící soubory

- `shared/domain/src/commonMain/kotlin/com/jervis/di/RpcConnectionManager.kt` - hlavní bug
- `shared/ui-common/src/commonMain/kotlin/com/jervis/ui/MainViewModel.kt:281` - používá resilientFlow
- `shared/ui-common/src/commonMain/kotlin/com/jervis/ui/meeting/MeetingViewModel.kt:172` - používá resilientFlow
- `backend/common-services/src/main/kotlin/com/jervis/common/rpc/RpcRetryUtils.kt:77` - definuje "rpcclient was cancelled" jako retryable error

## Testování fix

1. Spustit Desktop/Mobile UI aplikaci
2. Připojit se k serveru
3. Během běhu udělat: `kubectl rollout restart deployment/jervis-server -n jervis`
4. Počkat ~30s
5. **Očekávaný výsledek**: UI automaticky reconnectne, event streamy se obnoví
6. **Současný výsledek**: UI zobrazí error "RpcClient was cancelled", musí se restartovat aplikace

## Priorita

🔴 **CRITICAL** - UI aplikace je nepoužitelná po každém server redeploymentu bez restartu celé aplikace.

---

**Vytvořeno**: 2026-02-13
**Commit s regresí**: ae851fc1 (fix: remove invalid coroutineContext access in RpcConnectionManager)
**Commit co to fungoval**: před ae851fc1 (ale mělo to jinou chybu - invalid coroutineContext access)
