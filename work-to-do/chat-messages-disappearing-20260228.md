# Chat — zprávy mizí po přepnutí okna/návratu do chatu

**Priorita**: HIGH
**Status**: DONE

---

## Problém

Po odeslání zprávy v chatu se zpráva zobrazí, přijde odpověď, ale po přepnutí do jiného okna a návratu zpět zprávy zmizí. Zůstanou jen starší zprávy, které tam byly předtím.

Scénář:
1. Uživatel pošle zprávu → zobrazí se v UI
2. Agent odpoví → odpověď se zobrazí
3. Uživatel přepne na jiný tab/okno
4. Po návratu → nové zprávy (uživatel + agent) zmizely, vidí jen starší historii

## Root cause analýza (2026-03-01)

Problém je **race condition mezi persistence a SSE emisí + SharedFlow replay=0**:

### 1. SharedFlow replay=0 ztrácí události při reconnectu

`ChatRpcImpl.kt:39-43`:
```kotlin
private val chatEventStream = MutableSharedFlow<ChatResponseDto>(
    replay = 0,                    // ← Nový subscriber nedostane historii
    extraBufferCapacity = 200,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
```
Při WebSocket reconnectu (přepnutí okna) se stará subskripce ukončí a nová nezíská žádné mezitím emitované události.

### 2. USER_MESSAGE se emituje PŘED uložením do DB

`ChatRpcImpl.kt:122-131` — okamžitý emit do streamu, `chatService.sendMessage()` (s MongoDB uložením) běží až v `backgroundScope.launch {}` (řádek 134+).

Timeline:
```
T1: USER_MESSAGE emit do chatEventStream (okamžitě)
T2: backgroundScope.launch { chatService.sendMessage() }
T3: chatService.addMessage() → MongoDB save (sequence přiřazena)
T4: Python /chat volán, SSE tokeny proudí
...
T8: Uživatel přepne okno → WebSocket reconnect
T9: subscribeToChatEvents() zavolána znovu
T10: getHistory(limit=15) → MongoDB query
T11: Pokud zpráva ještě není v DB (T3 nedoběhla) → chybí v historii
```

### 3. Python streamuje tokeny PŘED save_assistant_message()

`handler_streaming.py:54-81`:
```python
async for event in stream_text(direct_text):
    yield event                    # ← Tokeny emitovány okamžitě
await save_assistant_message(...)  # ← Save AŽ PO streamingu
```

### 4. Dedup logika v reloadHistory() je fragile

`ChatViewModel.kt:581-591`:
```kotlin
val inFlight = _chatMessages.value.filter { msg ->
    msg.sequence == null &&        // In-flight = žádná DB sequence
    msg.metadata["streaming"] != "true" &&
    (msg.messageType == ChatMessage.MessageType.USER_MESSAGE || ...)
}
```
Dedup porovnává text zprávy (`db.text == flight.text`), ale timing/encoding rozdíly mohou způsobit mismatch.

### 5. PROGRESS→FINAL logika maže sousední zprávy

`ChatViewModel.kt:503-506` — FINAL event odstraní všechny PROGRESS zprávy. Pokud se USER_MESSAGE interně uloží jako PROGRESS typ, zmizí.

## Řešení

### Varianta A: replay > 0 (rychlé, částečné)
```kotlin
private val chatEventStream = MutableSharedFlow<ChatResponseDto>(
    replay = 50,  // Nový subscriber dostane posledních 50 událostí
    ...
)
```
Problém: replay buffer roste, a stále neřeší persistence timing.

### Varianta B: Persistence-first (správné řešení)
1. `sendMessage()` — uložit USER_MESSAGE do MongoDB PŘED emitem do streamu
2. Python `handle_chat()` — `save_assistant_message()` volat PŘED yieldem tokenů (nebo alespoň po DONE)
3. `reloadHistory()` v ChatViewModel — po reconnectu vždy načíst z DB, nededupovat

### Varianta C: Hybrid
- replay=10 pro pokrytí krátkých reconnectů
- Persistence-first pro USER_MESSAGE (okamžitý save)
- Assistant message save při DONE eventu (Kotlin strana, ne Python)

## Soubory

- `backend/server/.../rpc/ChatRpcImpl.kt` — replay > 0, persistence-first emit
- `backend/server/.../service/chat/ChatService.kt` — save before SSE
- `backend/service-orchestrator/app/chat/handler_streaming.py` — save timing
- `shared/ui-common/.../chat/ChatViewModel.kt` — reloadHistory() dedup fix

## Ověření

1. Poslat zprávu, dostat odpověď, přepnout okno, vrátit se → zprávy MUSÍ zůstat
2. Zkontrolovat MongoDB `chat_messages` collection — jsou zprávy uložené?
3. Zkontrolovat že UI po reconnectu načte aktuální historii z DB
