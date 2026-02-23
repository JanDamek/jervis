# Chat: "RpcClient was cancelled" + stale connection indicator (zelená tečka)

> **Datum:** 2026-02-23 08:28
> **Zjištěno:** Screenshot — zpráva se neposlala, UI ukazuje "spojeno" (zelená tečka)
> **Chyba:** Toast "Chyba serveru: RpcClient was cancelled", banner "Zpráva nebyla odeslána"
> **Stav spojení v UI:** `• idle` + zelená tečka (= connected) — ale ve skutečnosti odpojeno

---

## 1. Problém

Uživatel posílá zprávu v chatu. kRPC WebSocket je ve skutečnosti mrtvý (server restart, síťový výpadek), ale UI ukazuje zelenou tečku "spojeno". Odeslání selže s chybou "RpcClient was cancelled". Žádný auto-retry, žádný auto-reconnect. Uživatel musí ručně kliknout "Znovu".

---

## 2. Root Cause: Řetěz 4 chyb

### 2.1 `classifySendError` nezná "RpcClient was cancelled" (P0)

**Soubor:** `shared/ui-common/.../chat/SendError.kt:36-52`

`classifySendError()` mapuje exception text na typ chyby:
```kotlin
return when {
    msgLower.contains("connection refused") -> SendError.Network(...)
    msgLower.contains("connection reset")   -> SendError.Network(...)
    ...
    else -> SendError.Server(null, msg)   // ← "RpcClient was cancelled" padne SEM
}
```

`"RpcClient was cancelled"` NENÍ matchnut žádnou větví → klasifikace jako `SendError.Server` (non-retryable).

**Správně:** Má to být `SendError.Network` (retryable), protože to znamená "WebSocket je mrtvý, reconnectni".

### 2.2 `sendMessage` nepoužívá `resilientFlow` (P0)

**Soubor:** `shared/ui-common/.../chat/ChatViewModel.kt:194-201`

```kotlin
repository.chat.sendMessage(
    text = originalText,
    clientMessageId = clientMessageId,
    ...
)
```

`sendMessage` volá kRPC přímo přes `servicesProvider()` → `currentServices`. Žádný check zda je spojení živé. `resilientFlow` (které detekuje "cancelled" a triggerne reconnect) se používá jen pro stream subscriptions, ne pro one-shot RPC calls.

**Správně:** Buď obalit `sendMessage` do retry wrapperu s connection check, nebo po chybě "cancelled" okamžitě triggnout reconnect.

### 2.3 Heartbeat interval 30s → stale "connected" stav (P1)

**Soubor:** `shared/domain/.../RpcConnectionManager.kt:184`

```kotlin
delay(30_000)  // 30s interval
services.clientService.getAllClients()  // heartbeat RPC call
```

Heartbeat detekuje mrtvé spojení, ale až po **30s**. V tomto okně:
- `_state` = `Connected` (stale)
- `ConnectionViewModel.State` = `CONNECTED` → zelená tečka
- Jakýkoli RPC call selže s "cancelled"

### 2.4 Po reconnectu se pending message neretryuje (P1)

**Soubor:** `shared/ui-common/.../chat/ChatViewModel.kt:566-588`

```kotlin
// scheduleAutoRetry()
if (state.lastErrorType == "server") {
    updatePendingInfo()  // just update UI
    return               // NO auto-retry for "server" errors!
}
```

Protože chyba je klasifikována jako `Server` (§2.1), auto-retry se NIKDY nespustí:
- `BACKOFF_DELAYS_MS = [0, 5000, 30000, 300000]` — ale jen pro `Network` chyby
- `Server` chyby → žádný retry, zpráva zůstane v "Zpráva nebyla odeslána" navždy
- Po reconnectu (30s heartbeat) se reloadHistory spustí, ale pending message se neretryuje

---

## 3. Timeline bugu (co se stane krok po kroku)

```
T+0s    Server se restartoval (deploy), WebSocket tiše umřel
T+0s    UI: zelená tečka (CONNECTED) — heartbeat ještě nebežel
T+5s    Uživatel posílá zprávu
T+5s    sendMessage() → kRPC → "RpcClient was cancelled"
T+5s    classifySendError → SendError.Server (ŠPATNĚ — má být Network)
T+5s    Banner: "Zpráva nebyla odeslána" + Toast
T+5s    scheduleAutoRetry → "server" error → NO RETRY
T+30s   Heartbeat detekuje mrtvé spojení → triggerReconnect
T+35s   Reconnect úspěšný → zelená tečka (legitimní)
T+35s   Pending message zůstává stuck — lastErrorType="server"
∞       Uživatel musí kliknout "Znovu" ručně
```

---

## 4. Řešení

### Fix 1: `classifySendError` — přidat "cancelled" (CRITICAL)

**Soubor:** `SendError.kt:36-52`

Přidat match pro "cancelled" jako Network chybu:
```kotlin
msgLower.contains("rpcclient was cancelled") -> SendError.Network(...)
msgLower.contains("was cancelled") -> SendError.Network(...)
msgLower.contains("channel was closed") -> SendError.Network(...)
```

Tím se automaticky zprovozní auto-retry (BACKOFF_DELAYS_MS).

### Fix 2: `sendMessage` — trigger reconnect při "cancelled" (CRITICAL)

**Soubor:** `ChatViewModel.kt:194-233`

Po chybě "cancelled" zavolat `connectionManager.triggerReconnect()` okamžitě (ne čekat 30s na heartbeat):
```kotlin
} catch (e: Exception) {
    val error = classifySendError(e)
    if (e.message?.contains("cancelled") == true) {
        connectionManager.triggerReconnect("send failed: cancelled")
    }
    // ... existing error handling
}
```

### Fix 3: Heartbeat po reconnectu → retry pending messages (NICE TO HAVE)

**Soubor:** `ChatViewModel.kt:523` (`reloadHistory`)

Po úspěšném reconnectu (v `onConnectionReady` / `reloadHistory`), zkontrolovat `PendingMessageStorage` a reschedulovat auto-retry i pro `lastErrorType="server"`, pokud se stav změnil z Disconnected→Connected.

### Fix 4: Kratší heartbeat nebo pre-send check (NICE TO HAVE)

- Zkrátit heartbeat z 30s na 15s (detekce mrtvého spojení za poloviční čas)
- Nebo: před `sendMessage` zkontrolovat `connectionManager.state` — pokud Disconnected, počkat na reconnect místo okamžité chyby

---

## 5. Relevantní soubory

| Soubor | Řádky | Co |
|--------|-------|----|
| `shared/ui-common/.../chat/SendError.kt` | 36-52 | `classifySendError` — chybí match pro "cancelled" |
| `shared/ui-common/.../chat/ChatViewModel.kt` | 194-233 | `sendMessage` — bez connection check |
| `shared/ui-common/.../chat/ChatViewModel.kt` | 566-588 | `scheduleAutoRetry` — server errors = no retry |
| `shared/domain/.../RpcConnectionManager.kt` | 179-203 | Heartbeat (30s interval) |
| `shared/domain/.../RpcConnectionManager.kt` | 261-268 | `resilientFlow` — "cancelled" handling (jen pro streamy) |
| `shared/ui-common/.../ConnectionViewModel.kt` | — | Connection state enum (green dot) |
| `shared/ui-common/.../PersistentTopBar.kt` | 552-616 | ConnectionIndicator rendering |
| `shared/domain/.../NetworkModule.kt` | 79-82 | WebSocket ping interval (20s client) |
| `backend/server/.../KtorRpcServer.kt` | 87-91 | WebSocket ping/timeout (30s/15s server) |

---

## 6. Ping/Pong mismatch (bonus P2)

Nesouvisí přímo s bugem, ale:
- **Client** ping: 20s (`NetworkModule.kt:80`)
- **Server** ping: 30s, timeout 15s (`KtorRpcServer.kt:89-90`)
- Oba posílají pingy nezávisle — funguje to, ale je to zbytečně nesynchronizované

---

## 7. Architektonický návrh: Server ping je zbytečný (P2)

### Problém

Server posílá ping klientovi (`KtorRpcServer.kt:87-91`, interval 30s, timeout 15s). Ale:

- **Server nepotřebuje klienta ke své práci** — server běží nezávisle, zpracovává tasky, indexuje KB atd. bez ohledu na to, zda je klient připojený
- **Server nepotřebuje znát stav klientů** — heartbeat `getAllClients()` na klientovi je pro UI, ne pro server
- Server ping jen detekuje mrtvého klienta, aby mohl uzavřít WebSocket — ale server z toho nemá žádný benefit

### Správný design: Remote notifikace jako fallback

Server má (nebo by měl mít) **remote notification** mechanismus pro případ, kdy flow/WebSocket nedoručí zprávu:

1. **Primární kanál:** kRPC WebSocket flow (push, real-time)
2. **Fallback:** Remote notifikace — pokud WebSocket selže, zpráva se uloží jako pending notifikace
3. **Doručení:** Jakékoli UI po otevření (nebo reconnectu) stáhne pending notifikace (pull-based)
4. **Scope:** Jen ta konkrétní notifikace, ne vše — UI stáhne a zobrazí jen to, co mu patří

### Co to znamená pro tento bug:

- **Client ping (20s)** je správný — klient POTŘEBUJE vědět, zda je server živý (pro UI indikátor)
- **Server ping (30s)** je zbytečný — server nemá důvod aktivně monitorovat klienty
- **Heartbeat `getAllClients()`** je správný koncept, ale patří čistě na klienta (detekce mrtvého spojení → reconnect)
- **Při selhání doručení** (WebSocket mrtvý) → zpráva by se měla uložit jako remote notifikace → klient ji stáhne po reconnectu

### Řešení (směr):

1. **Odebrat server-side ping** z `KtorRpcServer.kt` — server nepotřebuje pingovat klienty
2. **Client-side ping ponechat** (`NetworkModule.kt:80`, 20s) — klient detekuje mrtvý server
3. **Implementovat/ověřit remote notifikace** — pokud flow nedoručí zprávu, uložit jako pending notifikaci
4. **Po reconnectu stáhnout pending notifikace** — klient po reconnectu zavolá endpoint pro nevyzvednuté notifikace
