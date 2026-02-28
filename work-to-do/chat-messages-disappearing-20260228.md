# Chat — zprávy mizí po přepnutí okna/návratu do chatu

**Priorita**: HIGH
**Status**: OPEN

---

## Problém

Po odeslání zprávy v chatu se zpráva zobrazí, přijde odpověď, ale po přepnutí do jiného okna a návratu zpět zprávy zmizí. Zůstanou jen starší zprávy, které tam byly předtím.

Scénář:
1. Uživatel pošle zprávu → zobrazí se v UI
2. Agent odpoví → odpověď se zobrazí
3. Uživatel přepne na jiný tab/okno
4. Po návratu → nové zprávy (uživatel + agent) zmizely, vidí jen starší historii

## Pravděpodobná příčina

Chat UI pravděpodobně při návratu/reconnectu znovu načte historii z backendu (MongoDB `chat_messages`), ale:
- Zprávy se neuloží do DB včas (race condition)
- Nebo se ukládají pod špatným conversationId
- Nebo UI nepočká na potvrzení uložení a při reconnectu fetchne stará data
- Nebo WebSocket reconnect po přepnutí okna resetuje lokální stav bez synchronizace s DB

## Oblasti k investigaci

1. **Python chat handler** — ověřit že user message i agent response se ukládají do MongoDB PŘED odesláním SSE response
   - `backend/service-orchestrator/app/chat/handler.py` — ukládání zpráv
   - `backend/service-orchestrator/app/chat/context.py` — ChatContextAssembler, MongoDB persistence

2. **Kotlin ChatService** — ověřit že zprávy z Python SSE se ukládají do DB
   - `backend/server/.../service/chat/ChatService.kt`
   - `backend/server/.../service/chat/PythonChatClient.kt`

3. **UI ChatScreen** — ověřit co se děje při reconnectu/návratu
   - Načte se historie z backendu? Nebo jen z lokálního stavu?
   - Při WebSocket reconnect se resetuje lokální state?
   - `shared/ui-common/.../screens/chat/` — ChatScreen, ChatViewModel

4. **Conversation lifecycle** — ověřit že conversationId je konzistentní
   - Nová zpráva → stejný conversationId jako předchozí?
   - Po reconnectu → fetchuje se správný conversation?

## Ověření

1. Poslat zprávu, dostat odpověď, přepnout okno, vrátit se → zprávy MUSÍ zůstat
2. Zkontrolovat MongoDB `chat_messages` collection — jsou zprávy uložené?
3. Zkontrolovat že UI po reconnectu načte aktuální historii z DB
