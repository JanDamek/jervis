# Chat — historie ztracená po restartu aplikace + agent ignoruje korekce

**Priorita**: HIGH
**Status**: DONE (2026-03-01) — truncation bug fixed (findByOrderByDesc + Flow.take), persistence-first emit, replay=10, system prompt correction example + memory_store logging

---

## Problém

Uživatel píše stejnou korekci do chatu opakovaně (10×), ale po restartu aplikace:
1. Chat zprávy nejsou vidět (historie se nenačte správně)
2. Agent si korekci nezapamatuje (nepoužije `memory_store` ani `kb_delete`)

Oba problémy vedou k frustrujícímu zacyklenému chování.

---

## Root cause 1: Truncation bug v getLastMessages()

### Problém
`ChatMessageService.kt` řádek 90 — metoda `getLastMessages(limit)` **ignoruje limit parametr**:

```kotlin
suspend fun getLastMessages(
    conversationId: ObjectId,
    limit: Int = 10,        // ← parametr přijat
): List<ChatMessageDocument> {
    require(limit > 0)
    val messages = chatMessageRepository
        .findTop10ByConversationIdOrderBySequenceDesc(conversationId)  // ← HARDCODED 10!
        .toList()
    return messages.reversed()
}
```

Spring Data `findTop10By...` vždy vrátí max 10 záznamů, bez ohledu na `limit` parametr. Pokud má konverzace 20+ zpráv (user + assistant), starší zprávy se nenačtou.

### Flow
```
App restart → subscribeToChatEvents() → getHistory(limit=15)
  → ChatService.getHistory(limit=15) → getLastMessages(limit=15)
    → findTop10ByConversationIdOrderBySequenceDesc()  ← vždy max 10
      → UI zobrazí jen posledních 10 zpráv
```

### Řešení
Přidat novou repository metodu s dynamickým limitem:
```kotlin
// ChatMessageRepository.kt
@Query("{ 'conversationId': ?0 }")
fun findByConversationIdOrderBySequenceDesc(
    conversationId: ObjectId,
    pageable: Pageable,
): Flow<ChatMessageDocument>
```

Nebo jednoduše:
```kotlin
fun findByConversationIdOrderBySequenceDesc(
    conversationId: ObjectId,
): Flow<ChatMessageDocument>
```

A v `getLastMessages()`:
```kotlin
val messages = chatMessageRepository
    .findByConversationIdOrderBySequenceDesc(conversationId)
    .take(limit)
    .toList()
return messages.reversed()
```

### Soubory
- `backend/server/.../service/chat/ChatMessageService.kt` — řádek 90, fix query
- `backend/server/.../repository/ChatMessageRepository.kt` — nová metoda s Pageable nebo Flow

---

## Root cause 2: Chat agent neperzistuje korekce od uživatele

### Problém
Když uživatel řekne "to tam není, vymaž to z KB", agent by měl:
1. Zavolat `kb_search` → najít chybný záznam
2. Zavolat `kb_delete(sourceUrn=...)` → smazat ho
3. Zavolat `memory_store(category="procedure", ...)` → zapamatovat si korekci

Místo toho agent:
- Odpovídá "uložil jsem to" ale reálně nic neuloží
- Nebo hledá v KB ale nenajde správný záznam k smazání
- Po restartu chatu (nový session) korekce není v paměti

### Kontext
System prompt (`system_prompt.py`) již obsahuje pravidla pro self-correction a "uživatel má vždy pravdu" (přidáno 2026-03-01). Problém může být:
1. Agent pravidla nedodržuje (30b model nedostatečně silný pro složité instrukce)
2. `kb_delete` tool selže tiše (chybné sourceUrn)
3. `memory_store` se nezavolá (agent si myslí že korekce je jen runtime info)

### Řešení

#### 2.1 Ověřit kb_delete tool funguje
Ověřit v logách orchestrátoru že `kb_delete` reálně maže záznamy:
```python
# tools/definitions.py — kb_delete handler
# Přidat logging: logger.info("KB_DELETE: sourceUrn=%s result=%s", urn, result)
```

#### 2.2 Posílit system prompt pro korekce
Přidat explicitní příklad do system_prompt.py:
```
**Příklad korekce:**
User: "traceID a spanID v aplikace není, vymaž to z KB"
→ KROK 1: kb_search("traceId spanId nUFO") → najdi chybný záznam
→ KROK 2: kb_delete(sourceUrn z výsledku) → smaž ho
→ KROK 3: memory_store(key="nufo-no-tracing", value="nUFO nepoužívá tracing (traceId/spanId)", category="procedure")
→ ODPOVĚĎ: "Smazal jsem chybný KB záznam a zapamatoval si, že nUFO nepoužívá tracing."
```

#### 2.3 Přidat logging pro memory_store a kb_delete
V Python tool handlerech přidat explicitní logy:
```python
logger.info("TOOL_CALL: %s args=%s", tool_name, tool_args)
logger.info("TOOL_RESULT: %s success=%s", tool_name, success)
```

### Soubory
- `backend/service-orchestrator/app/chat/system_prompt.py` — posílit příklady korekcí
- `backend/service-orchestrator/app/tools/definitions.py` — logging pro kb_delete, memory_store
- `backend/service-orchestrator/app/tools/handlers.py` — ověřit kb_delete reálně funguje

---

## Root cause 3: Předchozí opravu (replay, persistence-first) ověřit

Existující work-to-do `chat-messages-disappearing-20260228.md` (Status: DONE) řešil:
- SharedFlow replay=0 → zprávy zmizí při reconnectu
- USER_MESSAGE emit PŘED uložením do DB

Je třeba ověřit, zda tyto opravy byly reálně deploynuté a fungují:
1. Zkontrolovat `ChatRpcImpl.kt` — má `replay > 0`?
2. Zkontrolovat `ChatRpcImpl.kt` — USER_MESSAGE se ukládá PŘED emitem?

### Soubory
- `backend/server/.../rpc/ChatRpcImpl.kt` — řádky 39-43 (replay), 122-134 (emit timing)

---

## Ověření

1. Odeslat zprávu v chatu → restartovat aplikaci → zprávy MUSÍ být vidět (ne jen 10, ale adekvátní historie)
2. Říct agentovi "vymaž X z KB" → ověřit v logách že `kb_delete` se zavolal a uspěl
3. Restartovat chat → zeptat se agenta na X → NESMÍ opakovat smazanou informaci
4. MongoDB `chat_messages` collection — ověřit že zprávy jsou uložené se správným sequence
