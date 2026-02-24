# Bugy v chat systému — Python handler + Kotlin service

**Severity**: HIGH
**Date**: 2026-02-24

## Kontext

Souvisí s `chat-intent-and-direct-answer-bugs-20260224.md` (intent klasifikátor + halucinace).
Tento soubor pokrývá infrastrukturní bugy v chat pipeline.

---

## CRITICAL

### 1. ChatMessageService — dedup nefunguje

**Soubor**: `backend/server/.../service/chat/ChatMessageService.kt` (řádky 41-68)

Dedup check loguje "MESSAGE_DEDUP" ale **nevrací se** — kód pokračuje a vytvoří novou zprávu:
```kotlin
if (clientMessageId != null && chatMessageRepository.existsByClientMessageId(clientMessageId)) {
    logger.info { "MESSAGE_DEDUP | ..." }
    // ← CHYBÍ return!
}
val nextSequence = ... // pokračuje dál
```

**Dopad**: Při retry z UI se vytvoří duplicitní zprávy.
**Oprava**: Přidat `return` s existující zprávou, nebo throw.

### 2. ChatMessageService — race condition ve sequence

**Soubor**: `backend/server/.../service/chat/ChatMessageService.kt` (řádek 47)

```kotlin
val nextSequence = chatMessageRepository.countByConversationId(conversationId) + 1
```

Není atomické — dva souběžné callers dostanou stejnou sequence.
Server logy ukazují `FOREGROUND_CHAT_START: active=2` → dvě zprávy paralelně.

**Dopad**: Zprávy se stejnou sequence → broken pagination, špatné pořadí.
**Oprava**: MongoDB atomic `$inc` nebo `findAndModify` na sequence counter.

### 3. Python handler — sequence se neinkrementuje

**Soubor**: `backend/service-orchestrator/app/chat/handler.py` (řádky 247-807)

Všechny assistant odpovědi používají `request.message_sequence + 1`.
Pokud handler uloží víc zpráv (decomposition, error recovery), mají stejnou sequence.

**Dopad**: Duplicitní sequence v MongoDB → compression se rozbije.
**Oprava**: Použít `chat_context_assembler.get_next_sequence()` pro každé uložení.

---

## HIGH

### 4. ChatRpcImpl — exception při history load se spolkne

**Soubor**: `backend/server/.../rpc/ChatRpcImpl.kt` (řádky 81-83)

```kotlin
} catch (e: Exception) {
    logger.error(e) { "Failed to load chat history for subscription" }
    // ← pokračuje bez dat, bez scope, bez chybové zprávy
}
```

Scope change (persist z minulé session) se emituje UVNITŘ try bloku (řádky 67-80).
Pokud load failne → scope se nikdy neobnoví → UI zůstane bez klienta/projektu.

**Dopad**: Po restartu s DB chybou je chat prázdný a scope ztracený.
**Oprava**: Emitovat SCOPE_CHANGE mimo try block, nebo emit ERROR event.

### 5. switch_context — substring matching příliš volný

**Soubor**: `backend/service-orchestrator/app/chat/handler.py` (řádky 1262-1277)

```python
if cname == client_name_query or client_name_query in cname:
```

"BMS" matchne "Moneta BMS" i "BMS-FX". First match wins.

**Dopad**: Přepnutí na špatného klienta.
**Oprava**: Preferovat exact match, pak substring. Při více výsledcích se zeptat uživatele.

### 6. Tool argumenty — silent empty dict na parse error

**Soubor**: `backend/service-orchestrator/app/chat/handler.py` (řádky 638-643)

```python
except json.JSONDecodeError:
    arguments = {}  # tiše pokračuje s prázdnými argumenty
```

**Dopad**: Malformed tool call od LLM se spustí s prázdnými parametry → nesmyslné výsledky.
**Oprava**: Logovat error, přeskočit tool call, vrátit chybovou zprávu LLM.

### 7. Required parametry se defaultují na prázdný string

**Soubor**: `backend/service-orchestrator/app/chat/handler.py` (řádky 1187-1189)

```python
client_id=arguments.get("client_id", active_client_id or ""),  # ← ""
```

Pokud `active_client_id` je None → task se vytvoří s prázdným client_id.

**Dopad**: Kotlin rejectne task → silent failure.
**Oprava**: Validovat povinné parametry před voláním, vrátit error text.

---

## MEDIUM

### 8. Decomposition — žádný timeout na combiner

**Soubor**: `backend/service-orchestrator/app/chat/handler.py` (řádky 1828-1844)

Summarizer má 90s timeout, classifier 15s, ale combiner (finální LLM call) nemá timeout.
5 sub-topics × 3 iterace × 30s = 450s → překročí foreground timeout (300s).

**Oprava**: Přidat timeout na combiner. Nebo trackovat elapsed time a abortnout decomposition > 250s.

### 9. Sub-topic drift detection — chybí tool_call_history

**Soubor**: `backend/service-orchestrator/app/chat/handler.py` (řádek 1725)

`_detect_drift()` volaná v sub-topics nedostává `tool_call_history` → signal 2 (tool volaný 3× přes iterace) nefunguje.

**Dopad**: Infinite loop v sub-topics se nezachytí.
**Oprava**: Předávat `tool_call_history` do `_detect_drift()`.

### 10. ChatService — dedup vrací emptyFlow

**Soubor**: `backend/server/.../service/chat/ChatService.kt` (řádky 76-82)

Při detekci duplikátu vrací `emptyFlow()` → UI nedostane žádnou odpověď.

**Dopad**: UI neví, jestli zpráva prošla nebo ne.
**Oprava**: Vrátit potvrzení (echo existující zprávy), nebo ERROR event.

---

## NOTE (existující TODO, ne bug)

### Workflow steps — tools vždy prázdné

**Soubor**: `backend/server/.../rpc/KtorRpcServer.kt` (řádek 218)

```kotlin
tools = emptyList(), // Tools extraction not yet implemented
```

UI je připravené zobrazit tools, ale backend je neparsuje z progress events.
Není kritické, ale snižuje viditelnost do kroků chatu.

---

## Dotčené soubory

| Soubor | Změna |
|--------|-------|
| `backend/server/.../service/chat/ChatMessageService.kt` | Fix dedup return + atomic sequence (#1, #2) |
| `backend/server/.../rpc/ChatRpcImpl.kt` | Fix history error handling + scope (#4) |
| `backend/server/.../service/chat/ChatService.kt` | Fix dedup empty flow (#10) |
| `backend/service-orchestrator/app/chat/handler.py` | Sequence increment, tool arg validation, switch_context matching, required params, combiner timeout, sub-topic drift (#3, #5, #6, #7, #8, #9) |
