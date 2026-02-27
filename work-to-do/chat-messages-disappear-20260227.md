# Chat — zprávy zmizí při příchodu odpovědi

**Priorita**: HIGH (uživatel viditelný bug)

## Problém

Uživatel pošle zprávu v chatu. Zpráva se zobrazí (optimistic). Pak zmizí i s odpovědí.

## Root cause

`reloadHistory()` (řádek 525) nahradí `_chatMessages.value` kompletně historií z DB.
Optimistic zpráva ještě není v DB → zmizí.

### Flow

1. User pošle zprávu → `_chatMessages.value += optimisticMsg` (řádek 175)
2. Progress přidán → `_chatMessages.value += progressMsg` (řádek 209)
3. Během čekání na odpověď nastane reconnect (network blip, timeout, server restart)
4. `resilientFlow` restartne subscription → `.onStart` → `reloadHistory()` (řádek 102)
5. `_chatMessages.value = newMessages` (řádek 525) — **přepíše vše z DB**
6. Optimistic msg + progress nejsou v DB → **zmizí**
7. Odpověď přijde ale uživatel už nevidí ani otázku ani odpověď

### Proč to nastává často

- Python orchestrátor zpracovává ~30s (LLM tool calling)
- Během té doby může nastat reconnect (WebSocket timeout, health check fail)
- `triggerReconnect()` se volá i při "RpcClient was cancelled" (řádek 218)
- Každý reconnect → `reloadHistory()` → přepis

## Řešení

### Varianta A: Merge místo replace (doporučeno)

`reloadHistory()` by měl **mergovat** historii s in-flight zprávami:

```kotlin
private suspend fun reloadHistory() {
    val history = repository.chat.getChatHistory(limit = 10)
    val newMessages = history.messages.map { ... }

    // Zachovat optimistic + progress zprávy které ještě nejsou v DB
    val inFlight = _chatMessages.value.filter { msg ->
        msg.messageType == ChatMessage.MessageType.USER_MESSAGE && msg.sequence == null
        || msg.messageType == ChatMessage.MessageType.PROGRESS
    }
    _chatMessages.value = newMessages + inFlight
}
```

### Varianta B: Nevolat reloadHistory při aktivním chatu

```kotlin
.onStart {
    onConnectionReady()
    if (!_isLoading.value) {
        reloadHistory()  // Jen pokud neprobíhá chat
    }
}
```

## Soubory

- `shared/ui-common/.../chat/ChatViewModel.kt:102` — `reloadHistory()` v `.onStart`
- `shared/ui-common/.../chat/ChatViewModel.kt:525` — `_chatMessages.value = newMessages`
- `shared/ui-common/.../chat/ChatViewModel.kt:175` — optimistic message append
- `shared/ui-common/.../chat/ChatViewModel.kt:218` — `triggerReconnect` po send error
