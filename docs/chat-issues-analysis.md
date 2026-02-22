# Foreground Chat — Issue Analysis (2026-02-22)

**Status:** Bug report for development team
**Found by:** INFRA agent (log analysis + code review)
**Affected files:** `handler.py`, `ChatRpcImpl.kt`, `ChatViewModel.kt`

---

## Test 1: Normal message (14:28 UTC)

### Issue 1: Response takes extremely long (5-8 minutes)

#### Symptom
User sends a chat message, waits 5-8 minutes for a response. UI shows "Zpracovávám..." and then cycling through thinking events ("Přepínám na...", "Hledám v KB...", etc.).

#### Root cause
The **qwen3-coder-tool:30b** model enters tool-call loops. EVERY LLM response has `finish_reason=tool_calls` with `has_content=False`. The model never voluntarily generates a text response.

**Observed loop pattern:**
```
iteration 1: switch_context("Default - moje domáctnost", "Asistent")
iteration 2: kb_search("Carlos from Cuba email detail")
iteration 3: create_background_task(...)
iteration 4: kb_search("Carlos from Cuba email acostamiller@nauta.cu")
iteration 5: list_recent_tasks
iteration 6: memory_store(...)
iteration 7: store_knowledge(...)
iteration 8: switch_context("MMB", "nUFO")  ← LOOP DETECTED (2x same call)
→ forced text response
```

Each LLM call takes **15-60 seconds** (30b model on P40). 8 iterations × ~20s avg = ~3-4 minutes just for LLM calls, plus tool execution time.

#### Safety nets (currently working)
- **Loop detection** (handler.py:192-233): Detects when same tool+args called 2x consecutively. Forces text response without tools. ✓ Works but only catches identical consecutive calls, not wandering loops.
- **MAX_ITERATIONS=15** (handler.py:47): Hard limit on agentic loop. Forces text response at 15 iterations.

#### Recommendations
1. **Lower MAX_ITERATIONS** from 15 to 8-10 — 15 is too many for a 30b model that takes 15-60s per call
2. **Better loop detection** — detect "drift" (tool calls unrelated to the original question), not just identical consecutive calls
3. **Token budget per message** — if total LLM time exceeds 60-90s without producing text content, force response
4. **Consider smaller/faster model for simple queries** — 30b is overkill for "what is Carlos's email?"
5. **System prompt refinement** — instruct model to respond directly when possible, use tools only when necessary

**Status: partially fixed** in `36d72e8f` (MAX_ITERATIONS 15→6, intent classification, drift detection, focus reminders).

---

### Issue 2: Response is confused / irrelevant

#### Symptom
Final response doesn't match the user's question. Model wanders through various tools (KB search, create background tasks, store knowledge, switch contexts) without staying focused on the original question.

#### Root cause
Two factors:
1. **Context pollution** — model receives 20 recent messages (full history) + system prompt + tool descriptions (26 tools!). With only ~10k tokens of estimated context, the tool descriptions alone consume ~4k tokens.
2. **No tool prioritization** — model has 26 tools equally weighted. For a simple question like "check Carlos's email in KB", the model should: (a) switch_context, (b) kb_search, (c) respond. Instead it creates background tasks, stores knowledge, lists recent tasks, etc.
3. **Drift during long loops** — after 5+ iterations, the model loses focus on the original question

**Status: partially fixed** in `36d72e8f` (intent classification, tool filtering, focus reminders).

---

### Issue 3: "Přepínám na..." thinking event shown too long

#### Symptom
"Přepínám na: Assistent" is displayed for ~60 seconds, even though switch_context executes instantly.

#### Root cause
The event flow in `handler.py`:

```python
# 1. yield thinking("Přepínám na: Assistent")  ← UI shows this
# 2. execute switch_context (instant)
# 3. yield scope_change (UI changes scope)
# 4. yield tool_result (DROPPED by ChatRpcImpl)
# 5. → NEXT iteration starts → LLM call (15-60s)
#    ↑ During this time, UI still shows "Přepínám na: Assistent"
# 6. yield thinking("Hledám v KB...")           ← NOW UI updates
```

The `thinking` event is emitted BEFORE tool execution. The PROGRESS message persists until the NEXT thinking event, which only comes after the next LLM call completes.

**Status: fixed** in `36d72e8f` — added inter-iteration thinking event "Zpracovávám informace..." after tool execution.

#### Additional: tool_call/tool_result events are dropped

In `ChatRpcImpl.kt` (lines 181-183):
```kotlin
"tool_call", "tool_result" -> {
    // Skip — don't emit raw tool data to chat stream
}
```

This is intentional (raw tool data not useful for UI), but means the UI gets NO feedback between thinking events. Consider:
- Emitting tool_result as a brief status update (e.g., "KB: 3 results found")
- Or at minimum, emit a "processing complete" thinking event after each tool

---

### Issue 4: Duplicate assistant messages in UI

#### Symptom (screenshot)
Two nearly identical assistant messages displayed for a single user question.

#### Root cause
User sent the same message twice (or UI retried). Both requests ran as **parallel** SSE connections to the orchestrator. Both completed independently, each producing a final response. ChatRpcImpl processed both flows in the same `backgroundScope.launch {}`, emitting FINAL events for both. ChatViewModel added both as separate messages.

#### Evidence from logs
```
14:28:03  PYTHON_CHAT_START (request 1)
14:29:33  PYTHON_CHAT_START (request 2, same message)
14:29:33  FOREGROUND_CHAT_START: active=2  ← TWO parallel handlers!
14:36:39  PYTHON_CHAT_END (request 1) → done
14:36:53  PYTHON_CHAT_END (request 2) → done
```

#### Fix needed
1. **Server-side dedup** — ChatService.sendMessage should track active session requests and reject/cancel duplicate sends
2. **Or client-side dedup** — ChatViewModel should prevent sending if a request is already in-flight
3. **Or messageId-based dedup** — FINAL events with same response could be deduplicated in ChatViewModel

---

## Test 2: Extra long message (15:09 UTC, after intent fix deployment)

### Timeline

```
15:09:07  POST /chat → 200 OK
15:09:07  Context assembled: messages=0/20 (new session after archive)
15:09:07  Intent: ['core', 'brain', 'research', 'task_mgmt'] → 26/26 tools
          ↑ ALL categories matched! Message so long it contained keywords from everything.
          Intent filtering DID NOTHING — zero benefit.
15:09:07  Iteration 1/6
          estimated_tokens=41,399 (msgs=33,260 + tools=4,043 + output=4,096)
          → tier=local_large
          ↑ Message alone = ~33k tokens = ~133k chars. WAY over 32k context.
          UI shows: "Zpracovávám..." (PROGRESS, client-side)
15:13:21  LLM response after 4 min 14 sec: store_knowledge(entire bug analysis)
          ↑ Model's first action = save user's message to KB, not respond!
          UI shows: "Ukládám znalost: BUG: Systematické..." (thinking event)
15:13:31  store_knowledge completed (~10s)
          UI shows: "Zpracovávám informace..." (inter-iteration thinking)
15:13:31  Iteration 2/6
          estimated_tokens=43,333 (msgs=35,194 + tools=4,043 + output=4,096)
          → tier=local_large (even bigger now after tool results)
15:18:31  LLM TIMED OUT after 300s: HeartbeatTimeoutError
          ↑ Model couldn't process 43k tokens in time
15:18:32  Error event sent to UI
          Partial response saved (1 tool result)
15:18:32  PYTHON_CHAT_END | type=error
```

### Issue 5: "Zpracovávám..." shows for 4+ minutes

#### What is "Zpracovávám..."?
It's a **client-side PROGRESS message** added by `ChatViewModel.kt` (lines 198-204) immediately after sending a message:

```kotlin
val progressMsg = ChatMessage(
    from = ChatMessage.Sender.Assistant,
    text = "Zpracovávám...",
    messageType = ChatMessage.MessageType.PROGRESS,
)
_chatMessages.value = _chatMessages.value + progressMsg
```

This message persists until **the first server event replaces it**:
- A `thinking` event replaces it with the tool description (e.g., "Ukládám znalost...")
- A `token` event removes it and starts streaming
- A `final` event removes it and shows the response
- An `error` event removes it and shows the error

If the first LLM call takes 4 minutes → "Zpracovávám..." is shown for 4 minutes.

#### Why "Zpracovávám informace..." appeared again
After the first thinking event ("Ukládám znalost...") replaced "Zpracovávám...", and the tool executed, the new inter-iteration code (from fix `36d72e8f`) emits `thinking("Zpracovávám informace...")`. This is a **different** message — it's from the server, not the client-side "Zpracovávám...". But it looks similar to the user.

#### Fix needed
1. **Distinct wording** — "Zpracovávám informace..." is too similar to "Zpracovávám...". Use something clearly different, e.g., "Analyzuji informace..." or "Čekám na model..."
2. **Consider immediate thinking event** — emit `thinking("Připravuji odpověď...")` BEFORE the first LLM call to replace "Zpracovávám..." faster

### Issue 6: Intent classification matched ALL categories (no filtering)

#### Root cause
The user's message was a massive bug analysis (~133k chars) containing keywords from every category:
- `"issue"`, `"ticket"` → BRAIN
- `"úkol"`, `"task"` → TASK_MGMT
- `"kód"`, `"implementac"`, `"architektur"` → RESEARCH
- CORE always included

Result: `intent=['core', 'brain', 'research', 'task_mgmt'] → 26/26 tools` — every category matched.

#### Fix needed
1. **Intent classification should also consider message length** — very long messages are likely "information dumps", not commands. Default to CORE only for long messages (>500 chars?).
2. **Or use first sentence/last sentence for intent** — the actual question is usually in the first or last sentence, not buried in the middle of a bug report.

### Issue 7: Token overflow — 41k tokens for 32k context

#### Root cause
Message was ~133k chars = ~33k tokens. Plus tools (4k) + output budget (4k) = 41k estimated tokens. The tier selector chose `local_large` but even that can't handle 41k tokens properly.

The 30b model on P40 with `num_ctx=32768` gets:
- 48k context = full speed (~30 tok/s)
- Over 48k → CPU RAM spill (~7-12 tok/s)
- Over ~250k → fails

At 41k estimated tokens, the model was operating on the edge, which is why:
- First call took **4 min 14 sec** (slow CPU spill)
- Second call **timed out at 300s** (even larger context after adding tool results)

#### Fix needed
1. **Message length limit** — truncate or summarize user messages over ~4k tokens before sending to LLM
2. **Or context budget enforcement** — if estimated_tokens > tier's num_ctx, truncate messages/history to fit
3. **Pre-check before LLM call** — if estimated > 32k, warn user "Zpráva je příliš dlouhá" instead of sending to LLM

### Issue 8: Model stores user message to KB instead of responding

#### Root cause
The model received a long bug analysis and decided to `store_knowledge` with the **entire content** as the first action, instead of acknowledging/responding to the user. This is a model behavior issue compounded by:
1. `store_knowledge` tool is in the CORE or TASK_MGMT category — always available
2. No instruction in system prompt to NOT store user messages verbatim
3. Model sees a large structured document → assumes it should be stored

#### Fix needed
1. **System prompt: "Never store user's message verbatim. If user provides detailed analysis, acknowledge it and respond."**
2. **Or remove store_knowledge from CORE** — move to TASK_MGMT so it's only available when user explicitly mentions knowledge/storage

---

## Summary of all issues

| # | Issue | Severity | Status | Fix location |
|---|-------|----------|--------|-------------|
| 1 | Response takes 5-8 min (tool-call loops) | HIGH | Partially fixed (36d72e8f) | handler.py |
| 2 | Response is confused/irrelevant | HIGH | Partially fixed (36d72e8f) | handler.py, system_prompt.py |
| 3 | Thinking event shown too long | MEDIUM | Fixed (36d72e8f) | handler.py |
| 4 | Duplicate assistant messages | MEDIUM | Open | ChatService.kt or ChatViewModel.kt |
| 5 | "Zpracovávám..." shown 4+ min | HIGH | Open | ChatViewModel.kt, handler.py |
| 6 | Intent classification: all categories match | MEDIUM | Open | intent.py |
| 7 | Token overflow (41k for 32k context) | HIGH | Open | handler.py |
| 8 | Model stores user message instead of responding | LOW | Open | system_prompt.py, tools.py |

---

## File references

| File | Lines | What |
|------|-------|------|
| `backend/service-orchestrator/app/chat/handler.py` | 107-345 | Agentic loop, tool execution, thinking events |
| `backend/service-orchestrator/app/chat/handler.py` | 192-233 | Loop/drift detection + forced response |
| `backend/service-orchestrator/app/chat/handler.py` | 583-614 | `_describe_tool_call` — thinking event text |
| `backend/service-orchestrator/app/chat/intent.py` | 1-131 | Intent classification (regex-based) |
| `backend/service-orchestrator/app/chat/tools.py` | 309-372 | Tool categories, TOOL_DOMAINS |
| `backend/service-orchestrator/app/chat/system_prompt.py` | 57-72 | System prompt tool section |
| `backend/server/.../rpc/ChatRpcImpl.kt` | 136-194 | Event stream handling, tool_call/tool_result filter |
| `backend/server/.../rpc/ChatRpcImpl.kt` | 246-266 | Event type mapping |
| `shared/ui-common/.../chat/ChatViewModel.kt` | 198-204 | "Zpracovávám..." PROGRESS message |
| `shared/ui-common/.../chat/ChatViewModel.kt` | 323-327 | PLANNING → PROGRESS mapping |
| `shared/ui-common/.../chat/ChatViewModel.kt` | 409-432 | PROGRESS message replacement |
| `shared/ui-common/.../chat/ChatViewModel.kt` | 468-489 | STREAMING_TOKEN handling |
