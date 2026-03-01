# Chat historie — zprávy stále chybí po restartu (navzdory fixu)

**Priorita**: HIGH
**Status**: OPEN (2026-03-01)

---

## Problém

Přestože fix z `claude/implement-work-tasks-CBoXS` (dynamic limit + persistence-first) byl nasazen, uživatel po restartu stále vidí staré zprávy z 24.2. místo nedávných konverzací.

### Co bylo opraveno (merge 2026-03-01)

1. **Dynamic limit** (commit 69c2810c) — `findTop10` nahrazeno `findByConversationIdOrderBySequenceDesc().take(limit)` → respektuje volající limit (15)
2. **Persistence-first** (commit 40070b49) — USER_MESSAGE emitován po `chatService.sendMessage()` → garantuje DB uložení
3. **Replay buffer** — `MutableSharedFlow(replay=10)` → pokrývá krátké disconnecty

### Co stále nefunguje

Uživatel odeslal ~10 zpráv (opravy, instrukce). Po restartu serveru vidí jen staré zprávy z 24.2. (tracing bug analýza). Novější zprávy zmizely.

---

## Možné root causes

### 1. Python strana neuložila assistant zprávy

Fix 5e843334 přesunul `save_assistant_message()` před `stream_text()`. Ale:
- Co když Python save selhal tiše (exception caught)?
- Co když orchestrátor crashnul během streamu?
- Zpráva se zobrazila v UI (přes SSE stream) ale nikdy nedorazila do MongoDB

**Ověření**: Po odeslání zprávy a obdržení odpovědi → zkontrolovat MongoDB:
```bash
mongosh --eval "db.chat_messages.find({conversationId: ObjectId('...')}).sort({sequence: -1}).limit(5)"
```

**Řešení**: Přidat logging do Python `save_assistant_message()` — logovat success/failure s message ID.

### 2. Conversation session se vytvořila nová

`ChatService.getHistory()` hledá:
```kotlin
chatSessionRepository.findFirstByUserIdAndArchivedOrderByLastMessageAtDesc(userId, false)
```

Pokud se po restartu vytvořila nová session (nové ID), staré zprávy jsou pod starým conversationId. Nová session je prázdná → UI vidí "žádné zprávy" nebo jen ty z nové session.

**Ověření**: Zkontrolovat počet aktivních sessions:
```bash
mongosh --eval "db.chat_sessions.find({userId: 'jan', archived: false}).count()"
```
Měl by být max 1. Pokud > 1 → duplikát.

**Řešení**: Deduplikace sessions — pokud existuje aktivní session, netvořit novou.

### 3. Zprávy z chatu z 24.2. jsou jediné co v DB jsou

Pokud všechny novější zprávy (10× opravy) nebyly nikdy uloženy do DB:
- Zobrazily se v UI přes SSE stream (volatile)
- Replay buffer je in-memory → ztracen při restartu
- DB obsahuje jen staré zprávy z 24.2.

Toto je nejpravděpodobnější scénář. Zprávy z 24.2. jsou poslední úspěšně persistované.

**Řešení**: Monitoring — po každém chat response logovat:
```
CHAT_MSG_PERSISTED | conversationId=... | sequence=... | from=assistant | len=...
```

### 4. Background agent přepsal konverzaci

Deadline scan a background tasks mají vlastní thread_id, ale pokud sdílí conversationId → background agent zprávy vytlačí chat zprávy z limitu.

**Ověření**: Zkontrolovat zda chat_messages obsahuje background task zprávy se stejným conversationId.

---

## Diagnostický postup

1. `mongosh` → zkontrolovat chat_sessions (kolik aktivních, jaké ID)
2. `mongosh` → zkontrolovat chat_messages pro aktivní session (kolik jich je, data)
3. Odeslat testovací zprávu → ověřit že se uložila do DB
4. Restartovat server → ověřit že zpráva přežila

## Soubory

- `backend/server/.../rpc/ChatRpcImpl.kt` — subscribeToChatEvents, history loading (řádky 49-114)
- `backend/server/.../service/chat/ChatService.kt` — getHistory, sendMessage (řádky 130-156)
- `backend/server/.../service/chat/ChatMessageService.kt` — getLastMessages, addMessage (řádky 39-99)
- `backend/server/.../repository/ChatMessageRepository.kt` — findByConversationIdOrderBySequenceDesc
- `backend/service-orchestrator/app/chat/handler.py` — save_assistant_message
- `backend/service-orchestrator/app/chat/handler_agentic.py` — save_assistant_message
- `shared/ui-common/.../screens/chat/ChatViewModel.kt` — reloadHistory, merge logic (řádky 563-626)

## Ověření

1. Po odeslání zprávy → `db.chat_messages.count({conversationId: X})` se zvýšilo
2. Po restartu serveru → zprávy stále viditelné v UI
3. Žádné duplicitní chat_sessions pro stejného uživatele
4. Background task zprávy NESDÍLÍ conversationId s foreground chatem
