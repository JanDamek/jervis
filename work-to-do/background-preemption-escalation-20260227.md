# Bug: Background handler escalates after preemption cancellation

**Priority**: MEDIUM
**Area**: Orchestrator (Python) + BackgroundEngine (Kotlin)

## Problem

When foreground chat preempts a running background task, the following happens:

1. Kotlin `BackgroundEngine` sends interrupt to Python orchestrator
2. Python responds "No active task found" (timing issue — task is mid-LLM-call)
3. Router cancels the LLM HTTP connection → returns `{"error":"cancelled","message":"Client disconnected"}`
4. Python `background/handler.py` sees this as an LLM failure → **escalates** to higher tier (e.g. `local_standard → local_large`)
5. Background handler continues running on the escalated tier
6. Eventually hits tool loop (563 seconds wasted GPU time)

## Observed Log

```
11:32:29 PREEMPT_INTERRUPT: Sending interrupt to thread v2-...-e5ce59af
11:32:29 PYTHON_ORCHESTRATOR_INTERRUPT_FAILED: error=No active task found
11:32:29 PREEMPT_FAILED: Orchestrator returned false for interrupt
11:32:39 LLM call failed: OllamaException - {"error":"cancelled","message":"Client disconnected"} (tier=local_standard)
11:32:39 ESCALATION | local_standard → local_large | cloud_allowed=False
... (continues for 8+ minutes)
11:40:23 BACKGROUND_DONE | success=False | iterations=5 | tools=4 | 563.2s
```

## Expected Behavior

When the LLM call is cancelled with "Client disconnected" or "cancelled":
- Background handler should recognize this as a **preemption** (not an LLM failure)
- Should NOT escalate to a higher tier
- Should either abort gracefully or pause and retry on same tier later

## Fix Options

1. **Python side**: In `background/handler.py`, check if LLM error message contains "cancelled" or "Client disconnected" — treat as preemption, abort instead of escalating
2. **Kotlin side**: Fix the timing issue where interrupt finds "No active task" — the task IS running but the Python interrupt endpoint doesn't see it
3. **Both**: Add a preemption flag in the orchestrator context that background handler checks before escalating
