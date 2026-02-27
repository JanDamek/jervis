# Bug: Chat intent detection nerozpozná git/coding operace

**Priority**: HIGH
**Area**: Orchestrator → `app/chat/handler.py` + `app/chat/intent_decomposer.py`

## Problem

Když uživatel v chatu požádá o git operaci (např. "udělej branch", "commitni změny",
"podívej se na kód projektu nUFO"), intent detection klasifikuje jako `['task_mgmt', 'core']`
místo aby zahrnula `coding` intent.

Bez `coding` intentu chat nedostane klíčové tools:
- `dispatch_coding_agent` — spuštění Claude CLI pro git/code operace
- `code_search` — hledání v kódu

Výsledek: chat se zasekne v tool loop (opakovaně volá `switch_context`), drift detection
ho zastaví, a uživatel nedostane požadovanou akci.

## Pozorované logy

```
Chat: intent=['task_mgmt', 'core'] → 18/33 tools
Intent decomposition failed:  (treating as single intent)
Chat: calling tool switch_context with args: {'client': 'MMB', 'project': 'nUFO'}
Chat: drift detected (opakovaně voláš stejný tool se stejnými argumenty), forcing response
```

## Expected Behavior

- Intent detection rozpozná klíčová slova: git, branch, commit, push, pull, merge,
  kód, code, repo, deploy, build → zahrne `coding` intent
- Chat dostane `dispatch_coding_agent` + `code_search` v tool setu
- Agent zavolá `dispatch_coding_agent` pro git operace

## Fix

V `app/chat/handler.py` nebo `app/chat/intent_decomposer.py`:
- Rozšířit keyword matching pro `coding` intent o git-related termíny
- Případně: pokud je vybraný projekt s git repo připojením, automaticky zahrnout `coding`

## Kompletní logy chat session

```
21:45:05 [app.chat.context] INFO: CONTEXT_ASSEMBLED | conversationId=6998bb3ee784c7a84477ee2c | messages=20/20 | summaries=1/1 | tokens~3192/26768 | totalDbMessages=117
21:45:05 [app.chat.handler] INFO: Chat: intent=['task_mgmt', 'core'] → 18/33 tools
21:45:13 [app.chat.intent_decomposer] WARNING: Intent decomposition failed:  (treating as single intent)
21:45:13 [app.chat.handler_agentic] INFO: Chat: iteration 1/6
21:45:13 [app.chat.handler_agentic] INFO: Chat: estimated_tokens=15086 → tier=local_standard
21:46:13 [app.chat.handler_agentic] INFO: Chat: executing 1 tool calls
21:46:13 [app.chat.handler_agentic] INFO: Chat: calling tool switch_context with args: {'client': 'MMB', 'project': 'nUFO'}
21:46:13 [app.chat.handler_agentic] INFO: Chat: iteration 2/6
21:46:13 [app.chat.handler_agentic] INFO: Chat: estimated_tokens=15407 → tier=local_standard
21:46:42 [app.chat.handler_agentic] WARNING: Chat: drift detected (opakovaně voláš stejný tool se stejnými argumenty), forcing response
21:46:54 [app.llm.provider] INFO: LLM streaming complete: model=ollama/qwen3-coder-tool:30b, 215 tokens, 511 chars
21:46:59 [app.chat.topic_tracker] WARNING: Topic detection LLM failed: TimeoutError() (TimeoutError)
```

Klíčové body:
- `intent=['task_mgmt', 'core']` → **chybí `coding`** → `dispatch_coding_agent` a `code_search` nejsou v tool setu
- Intent decomposition selhal s prázdnou chybou
- LLM se pokusil volat `switch_context` opakovaně (nemá coding tools, neví co dělat)
- Drift detection po 2 iteracích zastavil loop → vygeneroval fallback odpověď (511 znaků)

## Files

- `backend/service-orchestrator/app/chat/handler.py` — intent → tool selection mapping
- `backend/service-orchestrator/app/chat/intent_decomposer.py` — intent classification logic
