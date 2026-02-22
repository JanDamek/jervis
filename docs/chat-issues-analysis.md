# Foreground Chat — Issue Analysis (2026-02-22)

**Status:** Bug report for development team
**Found by:** INFRA agent (log analysis + code review)
**Affected files:** `handler.py`, `ChatRpcImpl.kt`, `ChatViewModel.kt`

---

## Issue 1: Response takes extremely long (5-8 minutes)

### Symptom
User sends a chat message, waits 5-8 minutes for a response. UI shows "Zpracovávám..." and then cycling through thinking events ("Přepínám na...", "Hledám v KB...", etc.).

### Root cause
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

### Safety nets (currently working)
- **Loop detection** (handler.py:192-233): Detects when same tool+args called 2x consecutively. Forces text response without tools. ✓ Works but only catches identical consecutive calls, not wandering loops.
- **MAX_ITERATIONS=15** (handler.py:47): Hard limit on agentic loop. Forces text response at 15 iterations.

### Recommendations
1. **Lower MAX_ITERATIONS** from 15 to 8-10 — 15 is too many for a 30b model that takes 15-60s per call
2. **Better loop detection** — detect "drift" (tool calls unrelated to the original question), not just identical consecutive calls
3. **Token budget per message** — if total LLM time exceeds 60-90s without producing text content, force response
4. **Consider smaller/faster model for simple queries** — 30b is overkill for "what is Carlos's email?"
5. **System prompt refinement** — instruct model to respond directly when possible, use tools only when necessary

---

## Issue 2: Response is confused / irrelevant

### Symptom
Final response doesn't match the user's question. Model wanders through various tools (KB search, create background tasks, store knowledge, switch contexts) without staying focused on the original question.

### Root cause
Two factors:
1. **Context pollution** — model receives 20 recent messages (full history) + system prompt + tool descriptions (26 tools!). With only ~10k tokens of estimated context, the tool descriptions alone consume ~4k tokens.
2. **No tool prioritization** — model has 26 tools equally weighted. For a simple question like "check Carlos's email in KB", the model should: (a) switch_context, (b) kb_search, (c) respond. Instead it creates background tasks, stores knowledge, lists recent tasks, etc.
3. **Drift during long loops** — after 5+ iterations, the model loses focus on the original question

### Recommendations
1. **Reduce tool count** — categorize tools, only expose relevant ones based on intent classification
2. **Add focus reminder** — after each tool result, append a reminder of the original user question
3. **Intent classification** — simple pre-pass (fast model) to determine if the question needs tools at all
4. **Two-phase approach** — classify intent first, then provide only relevant tools
5. **System prompt: explicit instruction** — "Answer the user's question directly. Only use tools when you need information you don't have."

---

## Issue 3: Thinking events displayed too long

### Symptom
"Přepínám na: Assistent" is displayed for ~60 seconds, even though switch_context executes instantly.

### Root cause
The event flow in `handler.py` (lines 248-345):

```python
# 1. yield thinking("Přepínám na: Assistent")  ← UI shows this
# 2. execute switch_context (instant)
# 3. yield scope_change (UI changes scope)
# 4. yield tool_result (DROPPED by ChatRpcImpl)
# 5. → NEXT iteration starts → LLM call (15-60s)
#    ↑ During this time, UI still shows "Přepínám na: Assistent"
# 6. yield thinking("Hledám v KB...")           ← NOW UI updates
```

The `thinking` event is emitted BEFORE tool execution (line 258-259). The PROGRESS message persists until the NEXT thinking event, which only comes after the next LLM call completes.

### Fix (simple)
After all tool calls in an iteration are done, before the next LLM call, yield a generic thinking event:

```python
# At end of tool execution loop (after line 345), before next iteration:
yield ChatStreamEvent(type="thinking", content="Přemýšlím...")
```

This way, after switch_context completes instantly, the UI shows "Přemýšlím..." during the LLM call, not "Přepínám na...".

### Additional: tool_call/tool_result events are dropped

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

## Timeline of a typical chat request (from logs)

```
14:28:03  POST /chat → 200 OK
14:28:03  Context assembled (20 messages, ~1205 tokens)
14:28:03  iteration 1/15 → LLM call (CRITICAL priority)
14:28:50  LLM response: switch_context → thinking("Přepínám na Assistent")
14:28:50  switch_context executed (instant)
14:28:50  iteration 2/15 → LLM call
14:29:47  LLM response: kb_search → thinking("Hledám v KB")
14:29:47  kb_search executed (~10s)
14:30:59  iteration 3/15 → LLM call
14:30:49  LLM response: create_background_task
...
14:36:00  iteration 8/15 → loop detected (2x switch_context)
14:36:00  forced response without tools (streaming)
14:36:38  streaming complete (598 chars)
14:36:39  PYTHON_CHAT_END | type=done
```

**Total: 8 minutes 36 seconds** for a simple question.

---

## File references

| File | Lines | What |
|------|-------|------|
| `backend/service-orchestrator/app/chat/handler.py` | 107-345 | Agentic loop, tool execution, thinking events |
| `backend/service-orchestrator/app/chat/handler.py` | 192-233 | Loop detection + forced response |
| `backend/service-orchestrator/app/chat/handler.py` | 583-614 | `_describe_tool_call` — thinking event text |
| `backend/server/.../rpc/ChatRpcImpl.kt` | 136-194 | Event stream handling, tool_call/tool_result filter |
| `backend/server/.../rpc/ChatRpcImpl.kt` | 246-266 | Event type mapping |
| `shared/ui-common/.../chat/ChatViewModel.kt` | 323-327 | PLANNING → PROGRESS mapping |
| `shared/ui-common/.../chat/ChatViewModel.kt` | 409-432 | PROGRESS message replacement |
