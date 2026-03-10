# Orchestrator — Detailed Technical Reference

> Kompletní referenční dokument pro Python orchestrátor a jeho integraci s Kotlin serverem.
> Základ pro analýzu, rozšiřování a debugging celé orchestrační vrstvy.
> **Automaticky aktualizováno:** 2026-02-25

---

## Agent Selection Strategy

Only two agent types remain. Claude is the default and only production agent:

| Agent | Use Case | Provider | Auth | Status |
|-------|----------|----------|------|--------|
| **Claude** | Default agent for all tasks (coding + review) | Anthropic (Claude SDK) | Setup token or API key | ✅ Active |
| **Kilo** | Future alternative agent | — | — | Placeholder |

Agent type is always `claude` unless user explicitly selects otherwise.

**Auth methods** (Claude):
- **Setup Token** (recommended): `claude setup-token` → long-lived `sk-ant-oat01-...` token, stored in MongoDB
- **API Key**: Anthropic Console pay-as-you-go key

**Configuration:** `shared/ui-common/.../sections/CodingAgentsSettings.kt` (UI), `coding_agent_settings` collection (MongoDB).

---

## Obsah

### Legacy Orchestrator (14-node graph)

1. [Přehled systému](#1-přehled-systému)
2. [Architektura komunikace](#2-architektura-komunikace)
3. [Životní cyklus úlohy — kompletní flow](#3-životní-cyklus-úlohy--kompletní-flow)
4. [OrchestrateRequest — vstupní data](#4-orchestraterequest--vstupní-data)
5. [LangGraph StateGraph — graf orchestrace (legacy)](#5-langgraph-stategraph--graf-orchestrace)
6. [OrchestratorState — kompletní stav](#6-orchestratorstate--kompletní-stav)
7. [Nodes — detailní popis každého uzlu (legacy)](#7-nodes--detailní-popis-každého-uzlu)
8. [LLM Provider — model a volání](#8-llm-provider--model-a-volání)
9. [Knowledge Base integrace](#9-knowledge-base-integrace)
10. [K8s Job Runner — spouštění coding agentů](#10-k8s-job-runner--spouštění-coding-agentů)
11. [Workspace Manager — příprava prostředí](#11-workspace-manager--příprava-prostředí)
12. [Context Store — hierarchické úložiště](#12-context-store--hierarchické-úložiště)
13. [Approval Flow — interrupt/resume mechanismus](#13-approval-flow--interruptresume-mechanismus)
14. [Concurrency Control — multi-orchestration](#14-concurrency-control--multi-orchestration)
15. [Stuck detection a liveness](#15-stuck-detection-a-liveness)
16. [Chat Context Persistence — paměť agenta](#16-chat-context-persistence--paměť-agenta)
17. [Correction Agent — korekce přepisů](#17-correction-agent--korekce-přepisů)

### Multi-Agent Delegation System (7-node graph, feature-flagged)

18. [Delegation Graph — přehled](#18-delegation-graph--přehled)
19. [Delegation Graph — nové nodes](#19-delegation-graph--nové-nodes)
20. [OrchestratorState — delegation fields](#20-orchestratorstate--delegation-fields)
21. [DAG Executor — paralelní execution](#21-dag-executor--paralelní-execution)
22. [Agent Communication Protocol](#22-agent-communication-protocol)
23. [Specialist Agents — registr 19 agentů](#23-specialist-agents--registr-19-agentů)
24. [Memory Integration — 4 vrstvy](#24-memory-integration--4-vrstvy)
25. [Feature Flags a backward compatibility](#25-feature-flags-a-backward-compatibility)

### Shared Infrastructure

26. [Kotlin integrace — kompletní API](#26-kotlin-integrace--kompletní-api)
27. [Konfigurace a deployment](#27-konfigurace-a-deployment)
28. [Datové modely — kompletní referenční seznam](#28-datové-modely--kompletní-referenční-seznam)
29. [Souborová mapa](#29-souborová-mapa)

---

## 1. Přehled systému

Orchestrátor je **Python FastAPI** služba založená na **LangGraph** (stavový graf s checkpointy). Řeší VŠECHNY uživatelské požadavky — od jednoduchých dotazů přes coding úlohy po celé epicy.

### Klíčové principy

| Princip | Implementace |
|---------|-------------|
| **Žádné hard timeouty** | Streaming + token-arrival liveness (300s bez tokenu = dead); timestamp-based stuck detection (15 min) |
| **Push-based komunikace** | Python → Kotlin POST callbacky; polling je pouze safety-net (60s) |
| **Local-first LLM** | Ollama lokálně, cloud jen na explicitní eskalaci |
| **Single orchestration** | Jeden GPU request najednou (asyncio.Semaphore) |
| **Persistent state** | MongoDB checkpointy — přežijí restart podu |
| **KB-first architecture** | Každý node má přístup ke Knowledge Base kontextu |

### Služby v systému

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Kotlin Server (:5500)                       │
│  AgentOrchestratorService → dispatch/resume                        │
│  OrchestratorStatusHandler → state transitions                     │
│  BackgroundEngine → safety-net polling + timestamp-based stuck detection │
│  KtorRpcServer → /internal/ endpoints (push receivers)             │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ REST (HTTP)
┌───────────────────────────▼─────────────────────────────────────────┐
│                   Python Orchestrator (:8090)                       │
│  FastAPI + LangGraph StateGraph + MongoDBSaver                     │
│  LLM Provider (litellm) → Ollama / Anthropic / OpenAI / Gemini    │
│  Job Runner → K8s Jobs (claude, kilo)                              │
│  Workspace Manager → .jervis/ files, CLAUDE.md, MCP config        │
│  Context Store → orchestrator_context (MongoDB)                    │
│  Correction Agent → whisper transcript corrections                  │
└───────────┬────────────┬───────────────┬────────────────────────────┘
            │            │               │
    ┌───────▼───┐  ┌─────▼─────┐  ┌──────▼──────┐
    │  Ollama   │  │    KB      │  │  K8s Jobs   │
    │  (GPU)    │  │  (:8080)   │  │  (agents)   │
    │ :11434    │  │  Weaviate  │  │  PVC shared  │
    └───────────┘  │  ArangoDB  │  └─────────────┘
                   └───────────┘
```

---

## 2. Architektura komunikace

### 2.1 Směry komunikace

```
Kotlin → Python:
  POST /orchestrate/stream     — fire-and-forget dispatch (vrací thread_id)
  POST /approve/{thread_id}    — fire-and-forget resume (po user approval)
  POST /cancel/{thread_id}     — zrušení orchestrace
  GET  /status/{thread_id}     — safety-net polling (60s interval)
  GET  /health                 — health check + busy flag
  POST /internal/compress-chat — async chat komprese (po dokončení)

Python → Kotlin:
  POST /internal/orchestrator-progress  — node transitions (updates stateChangedAt)
  POST /internal/orchestrator-status    — completion/error/interrupt
  POST /internal/correction-progress    — correction agent progress
```

### 2.2 Push-based model (primární)

Na každém přechodu mezi nody Python volá `kotlin_client.report_progress()`:

```python
await kotlin_client.report_progress(
    task_id=request.task_id,       # MongoDB ObjectId string
    client_id=request.client_id,
    node="respond",                # aktuální node
    message="Generating response...",
    goal_index=0, total_goals=1,
    step_index=0, total_steps=1,
)
```

Kotlin přijímá na `/internal/orchestrator-progress`:
1. Updates `stateChangedAt` timestamp on TaskDocument (for stuck detection)
2. Emituje `OrchestratorTaskProgress` event do UI via Flow subscription

Při dokončení/chybě/interruptu:

```python
await kotlin_client.report_status_change(
    task_id=task_id,
    thread_id=thread_id,
    status="done",           # "done" | "error" | "interrupted" | "cancelled"
    summary="...",           # pro "done"
    error="...",             # pro "error"
    interrupt_action="...",  # pro "interrupted": "clarify", "commit", "push"
    interrupt_description="...",
)
```

### 2.3 Safety-net polling (sekundární)

`BackgroundEngine.runOrchestratorResultLoop()` — každých 60 sekund:

1. Najde tasks ve stavu `PROCESSING`
2. Zkontroluje `orchestrationStartedAt` / `stateChangedAt` z TaskDocument v DB
3. Pokud čas od posledního update < 15 minut (`STUCK_THRESHOLD_MINUTES`) → OK, čeká
4. Pokud čas od posledního update > 15 minut → zavolá Python `GET /status/{thread_id}`
5. Pokud Python nedostupný → reset task na `QUEUED` (retry)
6. Pokud Python vrátí stav → deleguje na `OrchestratorStatusHandler`

### 2.4 UI notifikace

Kotlin → UI: via kRPC WebSocket Flow subscriptions
- `OrchestratorTaskProgress` — node transitions, progress bar
- `OrchestratorTaskStatusChange` — terminal stavy
- Queue status updates — kolik tasků čeká, co běží

---

## 3. Životní cyklus úlohy — kompletní flow

### 3.1 FOREGROUND (chat) — uživatel píše do chatu

```
1. UI: User types message
     ↓
2. AgentOrchestratorRpcImpl.sendChat()
     ↓
3. Saves ChatMessageDocument (role=USER, auto-sequence)
     ↓
4. Finds/creates TaskDocument for this client+project
   - Reuses existing task with orchestratorThreadId (for resume)
   - Or creates new FOREGROUND task (type=USER_INPUT_PROCESSING)
     ↓
5. Task state → QUEUED
     ↓
6. BackgroundEngine.runExecutionLoop() picks up task
     ↓
7. AgentOrchestratorService.run(task, userInput)
   - Path A: task.orchestratorThreadId != null → resumePythonOrchestrator()
   - Path B: new task → dispatchToPythonOrchestrator()
     ↓
8. dispatchToPythonOrchestrator():
   a. Guard: countByState(PROCESSING) == 0
   b. pythonOrchestratorClient.isHealthy() == true
   c. Load project rules, environment, client/project names
   d. ChatHistoryService.prepareChatHistoryPayload(taskId) → chat context
   e. Build OrchestrateRequestDto (includes chat_history)
   f. POST /orchestrate/stream → returns {thread_id, stream_url}
   g. If 429 → return false (orchestrator busy, retry later)
   h. Task state → PROCESSING, save orchestratorThreadId
     ↓
9. Python _run_and_stream():
   a. asyncio.Semaphore acquire
   b. run_orchestration_streaming(request, thread_id)
   c. For each node event → kotlin_client.report_progress() (push callback)
   d. After completion → kotlin_client.report_status_change()
     ↓
10. Kotlin receives push callback:
    - /internal/orchestrator-progress → stateChangedAt update + UI emit
    - /internal/orchestrator-status → OrchestratorStatusHandler.handleStatusChange()
     ↓
11. OrchestratorStatusHandler:
    - "done" → handleDone():
      a. Emit final response to chat stream
      b. Save ASSISTANT ChatMessageDocument
      c. Check for inline messages (arrived during orchestration)
      d. If inline → re-queue to QUEUED (process new messages)
      e. If no inline → DONE (terminal)
      f. Async: ChatHistoryService.compressIfNeeded() (non-blocking)
    - "interrupted" → handleInterrupted():
      a. Emit clarification/approval to chat
      b. Save ASSISTANT ChatMessageDocument
      c. Task state → DONE (keeps orchestratorThreadId for resume)
    - "error" → handleError():
      a. Emit error to chat, save error message
      b. Task state → ERROR
```

### 3.2 BACKGROUND (scheduler, indexer) — stručně

```
TaskSchedulingService creates task with processingMode=BACKGROUND
  → BackgroundEngine picks up
  → Same dispatch flow
  → On interrupt: creates USER_TASK (notification in UI sidebar)
  → On done: deletes task (no chat UI)
```

### 3.3 Resume flow (approval/clarification)

```
1. User responds in chat (after interrupt)
     ↓
2. AgentOrchestratorRpcImpl detects existing task with orchestratorThreadId
     ↓
3. Task state → QUEUED
     ↓
4. BackgroundEngine picks up → AgentOrchestratorService.run()
     ↓
5. task.orchestratorThreadId != null → resumePythonOrchestrator()
     ↓
6. Determines: clarification vs approval
   - Clarification (no "Schválení:" prefix): approved=true, reason=user's answer
   - Approval: parse yes/no intent from user text
     ↓
7. POST /approve/{thread_id} with {approved, reason}
     ↓
8. Python approve() endpoint (main.py):
   - Fire-and-forget: creates asyncio task, registers in _active_tasks
   - Graph Agent path: compiled.ainvoke(Command(resume=resume_value), config)
   - Legacy path: resume_orchestration(thread_id, resume_value, chat_history)
   - On completion: report_status_change(status="done")
   - On error: report_status_change(status="error")
```

---

## 4. OrchestrateRequest — vstupní data

```python
class OrchestrateRequest(BaseModel):
    task_id: str                          # MongoDB ObjectId string
    client_id: str                        # ClientId string
    project_id: str | None                # ProjectId string (optional)
    client_name: str | None               # Human-readable client name
    project_name: str | None              # Human-readable project name
    workspace_path: str                   # "clients/{clientId}/{projectId}"
    query: str                            # User's original message
    agent_preference: str = "auto"        # "auto" | "claude" | "kilo"
    rules: ProjectRules                   # Branch naming, approval gates, cloud policies
    environment: dict | None              # Resolved environment context (infra, links)
    jervis_project_id: str | None         # JERVIS internal project for tracker ops
    chat_history: ChatHistoryPayload | None  # Conversation context (recent + summaries)
```

### ProjectRules

```python
class ProjectRules(BaseModel):
    branch_naming: str = "task/{taskId}"
    commit_prefix: str = "task({taskId}):"
    require_review: bool = False
    require_tests: bool = False
    require_approval_commit: bool = True    # interrupt() before commit
    require_approval_push: bool = True      # interrupt() before push
    allowed_branches: list[str]             # ["task/*", "fix/*"]
    forbidden_files: list[str]              # ["*.env", "secrets/*"]
    max_changed_files: int = 20
    auto_push: bool = False
    auto_use_anthropic: bool = False        # Cloud model auto-eskalace
    auto_use_openai: bool = False
    auto_use_gemini: bool = False
    max_openrouter_tier: str = "NONE"     # "NONE"/"FREE"/"PAID"/"PREMIUM" — OpenRouter fallback tier
```

### ChatHistoryPayload

```python
class ChatHistoryPayload(BaseModel):
    recent_messages: list[ChatHistoryMessage]  # Last 20 messages verbatim
    summary_blocks: list[ChatSummaryBlock]     # Compressed older blocks (max 15)
    total_message_count: int                   # Celkový počet zpráv v konverzaci
```

---

## 5. LangGraph StateGraph — graf orchestrace (legacy 14-node)

> **Poznámka:** Tento graf je zachován v `build_orchestrator_graph()` pro backward compatibility.
> Nový 7-nodový delegační graf je popsán v [sekci 18](#18-delegation-graph--přehled).
> Přepínání mezi grafy řídí feature flag `use_delegation_graph` (default: False = legacy).

### 5.1 Vizuální diagram

```
                                    ┌──────────────────────────────────┐
                                    │            ENTRY                 │
                                    └──────────────┬───────────────────┘
                                                   │
                                           ┌───────▼───────┐
                                           │    intake      │
                                           │ (classify +    │
                                           │  clarify)      │
                                           └───────┬────────┘
                                                   │
                                           ┌───────▼───────┐
                                           │ evidence_pack  │
                                           │ (KB + tracker  │
                                           │  artifacts)    │
                                           └───────┬────────┘
                                                   │
                                    ┌──────────────┼──────────────────┐
                                    │              │                  │
                          ┌─────────▼──┐   ┌──────▼──────┐   ┌──────▼──────┐
                          │  respond   │   │    plan     │   │ plan_epic / │
                          │ (ADVICE)   │   │(SINGLE_TASK)│   │   design    │
                          └─────┬──────┘   └──────┬──────┘   │(EPIC/GEN)  │
                                │                 │          └──────┬──────┘
                                │     ┌───────────┤                 │
                                │     │           │          ┌──────▼──────┐
                                │  ┌──▼────┐  ┌───▼────┐    │ select_goal │◄──┐
                                │  │respond│  │execute │    └──────┬──────┘   │
                                │  │(anal.)│  │ _step  │◄──┐      │          │
                                │  └──┬────┘  └───┬────┘   │  ┌───▼────┐    │
                                │     │           │        │  │plan    │    │
                                │     │      ┌────▼────┐   │  │_steps  │    │
                                │     │      │evaluate │   │  └───┬────┘    │
                                │     │      └────┬────┘   │      │         │
                                │     │           │        │      │         │
                                │     │    ┌──────┼────┐   │      └─────────┘
                                │     │    │      │    │   │
                                │     │ ┌──▼──┐   │ ┌──▼───▼──┐
                                │     │ │adv. │   │ │advance  │
                                │     │ │step │───┘ │_goal    │
                                │     │ └─────┘     └─────────┘
                                │     │
                          ┌─────▼─────▼────┐
                          │ git_operations  │
                          │ (commit/push    │
                          │  approval)      │
                          └───────┬─────────┘
                                  │
                          ┌───────▼─────────┐
                          │    finalize      │
                          │ (final report)   │
                          └───────┬─────────┘
                                  │
                              ┌───▼───┐
                              │  END  │
                              └───────┘
```

### 5.2 Routing logika

**Po evidence_pack** — `_route_by_category(state)`:
- `task_category == "advice"` → `respond`
- `task_category == "single_task"` → `plan`
- `task_category == "epic"` → `plan_epic`
- `task_category == "generative"` → `design`

**Po plan** — `route_after_plan(state)`:
- Všechny steps jsou `StepType.RESPOND` → `respond`
- Jinak → `execute_step` (coding loop)

**Po evaluate** — `next_step(state)`:
- `evaluation.acceptable == false` → `finalize` (skip zbylé kroky)
- `current_step_index + 1 < len(steps)` → `advance_step` → `execute_step`
- `current_goal_index + 1 < len(goals)` → `advance_goal` → `select_goal`
- Všechno hotovo → `git_operations`

**Po plan_epic / design** — `_route_after_epic_or_design(state)`:
- `error` nebo `final_result` nastaveno → `finalize` (zamítnuto)
- Jinak → `select_goal` (schváleno, spustit)

### 5.3 State persistence

- **Checkpointer**: `MongoDBSaver` z `langgraph-checkpoint-mongodb`
- **Databáze**: `jervis_checkpoints` (separátní MongoDB database)
- Automaticky ukládá stav po každém node
- Thread ID = `thread-{task_id}-{uuid[:8]}` — link mezi TaskDocument a checkpoint
- `recursion_limit = 150` (prevence infinite loops)

### 5.4 Delegation Graph (Multi-Agent System)

When `use_delegation_graph=True`, the orchestrator uses an alternative 7-node graph:

```
intake → evidence_pack → plan_delegations → execute_delegation → synthesize → finalize → END
                                                    ↑          │
                                                    └──────────┘
                                              (more pending delegations)
```

**Nodes:**

| Node | Purpose |
|------|---------|
| `plan_delegations` | LLM selects agents from AgentRegistry, builds ExecutionPlan (DAG of delegations) |
| `execute_delegation` | Dispatches DelegationMessage to agents, loops until all delegations complete |
| `synthesize` | Merges AgentOutput results, RAG cross-check, translates to response_language |

**Routing after execute_delegation:**
- If pending delegations remain → loop back to `execute_delegation`
- If all complete → proceed to `synthesize`

**Feature flag:** `settings.use_delegation_graph` (default: False). The `get_orchestrator_graph()` function returns either the legacy or delegation graph based on this flag.

> **Full reference:** See [section 18](#18-delegation-graph--přehled) for the complete delegation graph documentation including visual diagram, graph builder code, and feature flag switching.

---

## 6. OrchestratorState — kompletní stav

```python
class OrchestratorState(TypedDict, total=False):
    # --- Core task data ---
    task: dict                          # CodingTask.model_dump()
    rules: dict                         # ProjectRules.model_dump()
    environment: dict | None            # Resolved env context from Kotlin
    jervis_project_id: str | None       # JERVIS internal project for tracker ops

    # --- Task identity (top-level for easy access from all nodes) ---
    client_name: str | None
    project_name: str | None

    # --- Chat history (conversation context across sessions) ---
    chat_history: dict | None           # ChatHistoryPayload.model_dump()

    # --- Intake results ---
    task_category: str | None           # "advice" | "single_task" | "epic" | "generative"
    task_action: str | None             # "respond" | "code" | "tracker_ops" | "mixed"
    external_refs: list | None          # ["UFO-24", "https://..."]
    evidence_pack: dict | None          # EvidencePack.model_dump()
    needs_clarification: bool

    # --- Branch awareness ---
    target_branch: str | None           # Branch detected from user query (e.g. "feature/auth")

    # --- Clarification (from intake interrupt/resume) ---
    project_context: str | None         # KB project context (fetched in intake, branch-aware)
    task_complexity: str | None         # "simple" | "medium" | "complex" | "critical"
    clarification_questions: list | None
    clarification_response: dict | None # User's answers after resume
    allow_cloud_prompt: bool            # User explicitly requested cloud

    # --- Goals & steps ---
    goals: list                         # [Goal.model_dump(), ...]
    current_goal_index: int
    steps: list                         # [CodingStep.model_dump(), ...]
    current_step_index: int
    step_results: list                  # [StepResult.model_dump(), ...]
    goal_summaries: list                # [GoalSummary.model_dump(), ...] — cross-goal context

    # --- Results ---
    branch: str | None                  # Git branch name (after git_operations)
    final_result: str | None            # Final response text (set by respond/finalize)
    artifacts: list                     # Changed files
    error: str | None                   # Error message
    evaluation: dict | None             # Evaluation.model_dump() (last step)

    # --- Delegation system (multi-agent, new) ---
    execution_plan: dict | None          # ExecutionPlan — DAG of delegations
    delegation_states: dict              # {delegation_id: DelegationState}
    active_delegation_id: str | None     # Currently executing delegation
    completed_delegations: list          # Completed delegation IDs
    delegation_results: dict             # {delegation_id: result summary}
    response_language: str               # ISO 639-1 detected language (e.g. "cs", "en")
    domain: str | None                   # DomainType classification
    session_memory: list                 # Recent session memory entries
    _delegation_outputs: list            # Raw AgentOutput dicts for synthesis
```

> **Full reference:** See [section 20](#20-orchestratorstate--delegation-fields) for detailed field descriptions, usage per node, and initial state builder.

---

## 7. Nodes — detailní popis každého uzlu (legacy graph)

### 7.1 intake

**Soubor**: `app/graph/nodes/intake.py`
**Účel**: Klasifikace úlohy, detekce intentu, branch detection, povinná klarifikace

**Kroky**:
1. **Detekce target branch** z query (`_detect_branch_reference`) — hledá vzory:
   - Explicitní: `"on branch feature/auth"`, `"branch: main"`, `"na větvi develop"`
   - Branch prefixy: `feature/*`, `fix/*`, `hotfix/*`, `release/*`
   - Známé názvy: `main`, `master`, `develop`, `staging`, `production`
2. Fetch project context z KB (`fetch_project_context`, **branch-aware** — předá `target_branch`)
3. Build environment summary (pokud `state.environment` existuje)
4. Detekce cloud promptu (`detect_cloud_prompt` — keywords "use cloud", "použi cloud" atd.)
5. Build context section — client/project names + KB context (s branch info)
6. Recent conversation context (posledních 5 zpráv z `chat_history` pro klasifikaci)
7. LLM structured output — JSON s klasifikací

**LLM prompt vyžaduje**:
```json
{
  "category": "advice|single_task|epic|generative",
  "action": "respond|code|tracker_ops|mixed",
  "complexity": "simple|medium|complex|critical",
  "goal_clear": true,
  "external_refs": ["UFO-24"],
  "clarification_questions": [...]
}
```

**Povinná klarifikace**: Pokud `goal_clear == false` a `clarification_questions` neprázdné:
- Vytvoří `ClarificationQuestion` objekty
- Zavolá `interrupt()` — graf se zastaví
- Python pushne `status=interrupted, action=clarify`
- Kotlin: FOREGROUND → emitne do chatu + DONE; BACKGROUND/IDLE → USER_TASK
- Po resume: `clarification_response` obsahuje user's answers

**Output**: `task_category`, `task_action`, `external_refs`, `task_complexity`, `project_context`, `allow_cloud_prompt`, `needs_clarification`, **`target_branch`**

### 7.2 evidence_pack

**Soubor**: `app/graph/nodes/evidence.py`
**Účel**: Paralelní sběr kontextu z KB a trackeru

**Kroky**:
1. KB retrieve — task-relevant kontext (`prefetch_kb_context`)
2. External refs — pro každý ref (max 10) fetch z KB
3. Chat history summary — sestaví z `chat_history.summary_blocks`
4. Sestaví `EvidencePack`

**Output**: `evidence_pack` dict obsahující:
- `kb_results: [{source, content}]`
- `tracker_artifacts: [{ref, content}]`
- `chat_history_summary: str`
- `external_refs`, `facts`, `unknowns`

### 7.3 respond

**Soubor**: `app/graph/nodes/respond.py`
**Účel**: Přímá odpověď na ADVICE a SINGLE_TASK/respond dotazy

**BACKGROUND skip**: Pro `processing_mode == "BACKGROUND"` se celý respond node přeskočí (okamžitý return `{"final_result": "Background task completed."}`). BACKGROUND tasky nemají příjemce odpovědi — task se po dokončení smaže.

**Používá se pro** (FOREGROUND only): Meeting summaries, knowledge queries, planning advice, analýzy

**Context building**:
1. Task identity (client/project names)
2. Project context z KB
3. Evidence pack KB results + tracker artifacts
4. **Chat history — plný konverzační kontext** (summary blocks + recent messages)
5. User clarification (pokud proběhla)
6. Environment context

**LLM system prompt**: "You are Jervis, an AI assistant... Use Czech language." Obsahuje:
- Anti-halucinační pravidla (NIKDY netvrd že jsi provedl akci bez potvrzeného výsledku z nástroje)
- Korekce vs. příkazy (rozlišení uživatelské opravy od příkazu k akci)
- Explicitní capabilities (co UMÍŠ a NEUMÍŠ — git je READ-ONLY, žádné code changes, branch ops, deploy)

**Agentic tool-use loop** (max 8 iterations): LLM call → tool calls → execute → repeat.
Tools: `web_search`, `kb_search`, `kb_delete`, `store_knowledge`, `ask_user`, `create_scheduled_task`, + KB stats, git, filesystem, terminal tools.

**Tool loop detection** (5 signals): Sleduje historii `(tool_name, args_json)`. Signály: (1) Consecutive same — 2× identický tool+args. (2) Same tool 3×+ — jeden tool volán celkově 3+×. (3) **Alternating pair** — A→B→A→B pattern (detekuje ping-pong mezi dvěma tools, např. brain_search + brain_update). (4) Domain drift — 3 iterace s 3+ různými doménami bez průniku. (5) Excessive tools — 8+ distinct tools po 4+ iteracích. Při detekci se injektuje system message "STOP" a vynutí finální odpověď.

**ask_user tool**: Pokud agent potřebuje upřesnění od uživatele, zavolá `ask_user(question)`. Executor vyhodí `AskUserInterrupt`, respond node zachytí → volá `interrupt()` → graf se zastaví → uživatel odpoví v chatu → graf pokračuje s odpovědí jako tool result. Viz [§13 Approval Flow](#13-approval-flow--interruptresume-mechanismus).

**Token streaming (background tasks)**: Finální odpověď (po poslední LLM iteraci bez tool_calls) se streamuje do UI po malých chuncích (12 znaků) přes `kotlin_client.emit_streaming_token()` → Kotlin `/internal/streaming-token` endpoint (legacy no-op). **Pozn.:** Foreground chat nyní používá přímý SSE stream přes `IChatService.subscribeToChatEvents()` → `ChatRpcImpl` → `PythonChatClient` (viz architecture.md §16).

**Output**: `final_result` — odpověď v češtině

### 7.4 plan

**Soubor**: `app/graph/nodes/plan.py`
**Účel**: Plánování pro SINGLE_TASK (routing dle task_action)

**Routing dle action**:

| task_action | Co dělá | Výstup |
|-------------|---------|--------|
| `respond` | Vytvoří single respond step | 1 goal, 1 step (StepType.RESPOND) |
| `code` | LLM dekomponuje na goals + steps | N goals, M steps (StepType.CODE) |
| `tracker_ops` | LLM plánuje tracker operace | 1 goal, N steps (StepType.TRACKER) |
| `mixed` | LLM plánuje mix respond+code+tracker | 1 goal, N steps (mixed types) |

**Context pro LLM**: client/project identity, project context, KB results, clarification, **key decisions z chat history summaries** (posledních 10)

**`_plan_coding_task()`**: Volá LLM pro dekompozici → goals se seznamem dependencies

**`route_after_plan()`**: Pokud všechny steps jsou RESPOND → `respond` node; jinak → `execute_step`

### 7.5 decompose (coding pipeline)

**Soubor**: `app/graph/nodes/coding.py`
**Účel**: Alternativní dekompozice pro EPIC/GENERATIVE path (task → goals)

**Rozdíl oproti plan**: `decompose` je standalone node pro staré flow; `plan._plan_coding_task()` dělá totéž ale v rámci plan nodu.

### 7.6 select_goal

**Soubor**: `app/graph/nodes/coding.py`
**Účel**: Výběr aktuálního cíle s validací závislostí

**Logika**:
1. Vezme `goals[current_goal_index]`
2. Zkontroluje `goal.dependencies` proti `completed_ids` (z goal_summaries)
3. Pokud nesplněné závislosti → pokusí se swapnout s pozdějším goal bez závislostí
4. Pokud swap nelze → pokračuje best-effort

### 7.7 plan_steps

**Soubor**: `app/graph/nodes/coding.py`
**Účel**: Vytvoření execution steps pro aktuální goal

**Context**: Cross-goal kontext (goal_summaries — co bylo dřív hotovo, změněné soubory)

**LLM output**: JSON steps s konkrétními instrukcemi pro coding agenta, soubory, agent type

**Agent selection**: `select_agent(complexity, preference)`:
- All complexities → Claude CLI (default)
- Kilo → only if explicitly configured
- Manual preference override: `agent_preference != "auto"`

### 7.8 execute_step

**Soubor**: `app/graph/nodes/execute.py`
**Účel**: Provedení jednoho kroku (dispatch dle step type)

**Tři typy**:

#### StepType.RESPOND
- LLM + KB context → přímá odpověď
- Nastaví `final_result` i `step_results`

#### StepType.CODE
1. Pre-fetch KB context pro step (soubory, task description)
2. `workspace_manager.prepare_workspace()` → zapisuje instrukce do `.jervis/`
3. `job_runner.run_coding_agent()` → K8s Job
4. Čte výsledek z `.jervis/result.json`
5. Přidá `StepResult` do `step_results`

#### StepType.TRACKER
- Volá Kotlin internal API endpointy:
  - `POST /internal/tracker/create-issue`
  - `POST /internal/tracker/update-issue`
- Pro každou operaci (create/update/comment)

### 7.9 evaluate

**Soubor**: `app/graph/nodes/evaluate.py`
**Účel**: Evaluace výsledku posledního kroku

**Kontroly**:
1. Step success — pokud `last_result.success == false` → FAILED
2. Forbidden files — `fnmatch` proti `rules.forbidden_files` → BLOCKED
3. Max file count — `len(changed_files) > rules.max_changed_files` → WARNING

**Výstup**: `Evaluation(acceptable, checks)` — `acceptable = not any(BLOCKED or FAILED)`

### 7.10 advance_step / advance_goal

**advance_step**: Jednoduše `current_step_index += 1`

**advance_goal**:
1. `current_goal_index += 1`
2. Build `GoalSummary` z recent step results
3. Přidá do `goal_summaries` (cross-goal context pro další goals)

### 7.11 git_operations

**Soubor**: `app/graph/nodes/git_ops.py`
**Účel**: Git commit/push s approval gatami

**Flow**:
1. Zkontroluje, zda existují úspěšné code changes (changed_files)
2. Pokud ne → return `{branch: None}`
3. **Commit approval gate** (`require_approval_commit`):
   - `interrupt()` → graf se zastaví
   - User schválí/zamítne
4. `workspace_manager.prepare_git_workspace()` → přepíše CLAUDE.md na git-permissive
5. Deleguje commit na Claude agenta (K8s Job s `ALLOW_GIT=true`):
   - Instructions: commit message s prefixem, stage only relevant files, NO push
6. **Push approval gate** (`require_approval_push`, pokud `auto_push == true`):
   - `interrupt()` → user approval
   - Deleguje push na Claude agenta

### 7.12 finalize

**Soubor**: `app/graph/nodes/finalize.py`
**Účel**: Generování finálního reportu

**BACKGROUND skip**: Pro `processing_mode == "BACKGROUND"` se summary generation přeskočí (žádný LLM call). KB outcome ingestion a Memory Agent flush stále běží.

**Logika**:
1. **BACKGROUND** → skip summary, keep `final_result` from respond (or default)
2. Pokud `final_result` už nastaveno (respond node) → skip
3. Sestaví kontext: client/project, branch, artifacts, **conversation stats z chat_history**, **key decisions**
4. LLM generuje český souhrn (max 3-5 vět)
5. Fallback: strukturovaný souhrn bez LLM

---

## 8. LLM Provider — model a volání

**Soubor**: `app/llm/provider.py`

### 8.1 Tiery modelů

Fixní `num_ctx` na GPU — žádná dynamická selekce. GPU1 = 48k, GPU2 = 32k (s embedding modelem).

| Tier | Model | Context | Kdy |
|------|-------|---------|-----|
| `LOCAL_STANDARD` | `ollama/qwen3-coder-tool:30b` | 48k | Všechny lokální úlohy (default) |
| `LOCAL_COMPACT` | `ollama/qwen3-coder-tool:30b` | 32k | Pojistka pro GPU2 |
| `CLOUD_REASONING` | `anthropic/claude-sonnet-4-5` | - | Architektura, design (auto=anthropic) |
| `CLOUD_CODING` | `openai/gpt-4o` | - | Code editing (auto=openai) |
| `CLOUD_PREMIUM` | `anthropic/claude-opus-4-6` | - | Kritické úlohy |
| `CLOUD_LARGE_CONTEXT` | `google/gemini-2.5-pro` | 1M | Ultra-large context (auto=gemini) |
| `CLOUD_OPENROUTER` | dle fronty | varies | OpenRouter fallback při busy GPU |

### 8.2 Routing — `select_route()`

**Soubor**: `app/llm/openrouter_resolver.py`

Nahrazuje dynamickou eskalaci. Rozhoduje local vs cloud dle GPU stavu a `maxOpenRouterTier`:

```python
async def select_route(
    estimated_tokens: int,
    max_tier: str = "NONE",       # "NONE" / "FREE" / "PAID" / "PREMIUM"
    priority: str = "CRITICAL",
) -> Route:
```

Logika:
1. `max_tier == "NONE"` → vždy local (čeká na GPU)
2. `estimated_tokens > 48k` → LARGE_CONTEXT fronta (pokud `max_tier >= PAID`)
3. GPU volná → local (`LOCAL_STANDARD`, 48k)
4. GPU busy → iteruj fronty dle `max_tier`:
   - Vždy zkus FREE frontu první
   - `max_tier >= PAID` → zkus PAID
   - `max_tier >= PREMIUM` → zkus PREMIUM
5. Fallback: čekej na local GPU

### 8.3 OpenRouter fronty

4 fronty konfigurované v OpenRouter nastavení:

| Fronta | Modely (default) | Kdy |
|--------|-------------------|-----|
| `FREE` | p40 (local), qwen3-30b:free | GPU busy, maxTier >= FREE |
| `PAID` | p40 (local), claude-haiku-4, gpt-4o-mini | maxTier >= PAID |
| `PREMIUM` | p40 (local), claude-sonnet-4, o3-mini | maxTier >= PREMIUM |
| `LARGE_CONTEXT` | gemini-2.5-pro (1M), claude-sonnet-4 (200k) | estimated > 48k, maxTier >= PAID |

### 8.4 Cloud eskalace (`llm_with_cloud_fallback`)

**Soubor**: `app/graph/nodes/_helpers.py`

Pro LangGraph nody (ne chat/background handler):
```
1. context_tokens > 49_000? → rovnou cloud (pokud auto-enabled)
2. Pokus o lokální model (LOCAL_STANDARD, 48k)
3. Lokální selhal → cloud eskalace:
   a. auto_providers neprázdné? → auto-escalate (bez ptaní)
   b. žádný provider auto? → interrupt() (zeptat se usera)
   c. user zamítl? → RuntimeError
```

**Auto-providers**: `rules.auto_use_anthropic/openai/gemini` + `rules.max_openrouter_tier != "NONE"` → openrouter

### 8.5 Streaming + token-arrival timeout

```python
async def _iter_with_timeout(stream):
    # asyncio.wait_for per chunk — TOKEN_TIMEOUT_SECONDS = 300
    async for chunk in stream:
        yield chunk  # each chunk must arrive within timeout
    # raises TokenTimeoutError if no token for 5 min
```

- **Streaming** (bez tool calls): per-chunk token-arrival timeout. Pokud tokeny přicházejí → čeká neomezeně. Pokud 5 min bez tokenu → `TokenTimeoutError`.
- **Blocking** (tool calls — litellm omezení): **300s** timeout pro všechny tiery (fixní num_ctx, žádný CPU-spill)
- Cloud tiery: **300s** (rychlé API)

---

## 9. Knowledge Base integrace

**Soubor**: `app/kb/prefetch.py`

### 9.1 Dva typy KB fetche

**`prefetch_kb_context()`** — pro coding agenty (zapisuje se do `.jervis/kb-context.md`):
1. Relevantní znalosti pro task (5 results, confidence 0.7, graph expansion)
2. Coding conventions (3 results, client-level)
3. Architecture decisions (3 results, project-level)
4. File-specific knowledge (2 results per file, max 3 soubory)

**`fetch_project_context(target_branch=...)`** — pro orchestrátor (intake, decompose):
1. **Repository & branch structure** — graph search pro `repository` a `branch` node types
   - Zobrazí available branches s `← TARGET` marker pro detekovanou branch
2. **Project structure** — files + classes (branch-scoped pokud `target_branch` specifikován)
   - Používá `_graph_search_branch_aware()` s `branchName` query parametrem
   - File/class nodes annotovány `[branch: main]` pokud není target branch fixní
3. Architecture & modules (5 results, graph expansion)
4. Coding conventions (3 results, client-level)
5. Task-relevant context (5 results, confidence 0.6, graph expansion)

### 9.2 KB API volání

```python
POST {kb_url}/api/v1/search
Body: {
    "query": "...",
    "client_id": "...",
    "project_id": "...",
    "max_results": 5,
    "min_confidence": 0.7,
    "expand_graph": true,
}
```

### 9.3 Runtime KB přístup (HTTP MCP)

Coding agenti mají runtime přístup přes unified HTTP MCP server (`service-mcp:8100`):
- `kb_search(query, client_id, project_id, max_results)` — full-text search + graph
- `kb_search_simple(query)` — quick RAG-only search
- `kb_traverse(start_node, direction, max_hops)` — graph traversal
- `kb_graph_search(query, node_type, limit)` — graph node search
- `kb_get_evidence(node_key)` — supporting RAG chunks for a node
- `kb_resolve_alias(alias)` — entity alias resolution
- `kb_store(content, kind, metadata)` — store new knowledge

Agents connect via HTTP (`.claude/mcp.json` → `type: "http"`, `url: "http://jervis-mcp:8100/mcp"`).

---

## 10. K8s Job Runner — spouštění coding agentů

**Soubor**: `app/agents/job_runner.py`

### 10.1 Agent typy a limity

| Agent | Image | Concurrent limit | Timeout |
|-------|-------|-------------------|---------|
| claude | `jervis-claude` | 2 | 1800s |
| kilo | `jervis-kilo` | 2 | 1800s |

### 10.2 K8s Job spec

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: jervis-{agent_type}-{task_id[:12]}
  namespace: jervis
  labels:
    app: jervis-agent
    agent-type: {agent_type}
    task-id: {task_id}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 300
  template:
    spec:
      serviceAccountName: jervis-coding-agent  # ← when environment has namespaces
      automountServiceAccountToken: true
      containers:
        - name: agent
          image: {registry}/jervis-{image_name}:latest
          env:
            - TASK_ID, CLIENT_ID, PROJECT_ID
            - WORKSPACE_PATH (absolut na PVC)
            - OLLAMA_URL, KNOWLEDGEBASE_URL
            - ALLOW_GIT (true/false)
            - ANTHROPIC_API_KEY (pro Claude agenta)
            - KUBE_NAMESPACES (comma-separated, when environment available)
          volumeMounts:
            - /opt/jervis/data (PVC shared s orchestrátorem)
          resources:
            requests: 256Mi memory, 250m CPU
            limits: 1Gi memory, 1000m CPU
      restartPolicy: Never
```

**kubectl access:** Agent image includes `kubectl` binary. When `KUBE_NAMESPACES` is set, the ServiceAccount `jervis-coding-agent` (from `jervis` namespace) has a dynamically created Role+RoleBinding in each target namespace granting full resource access. RBAC is created by `EnvironmentK8sService.ensureAgentRbac()` during environment provisioning.

### 10.3 Lifecycle (Non-blocking Async Dispatch)

Coding agent dispatch je **non-blocking** — orchestrátor nepotí na dokončení jobu, ale použije LangGraph `interrupt()` pattern:

```
execute_step() → dispatch_coding_agent() → notify_agent_dispatched() → interrupt()
    ↓ graph checkpoints, thread released (task state → CODING)
AgentTaskWatcher._poll_once() → get_job_status() → [succeeded] → collect_result()
    → POST /internal/tasks/{id}/agent-completed
    → resume_orchestration_streaming(thread_id, result)
    ↓ graph resumes from interrupt, processes result
```

**Krokový detail:**

1. `prepare_workspace()` — zapíše instrukce do `.jervis/`
2. `dispatch_coding_agent()` — vytvoří K8s Job, **vrátí okamžitě** s `{job_name, agent_type}`
3. `notify_agent_dispatched()` — POST na Kotlin, nastaví task state na `CODING`
4. `interrupt({"type": "waiting_for_agent", ...})` — graph se checkpointne, thread uvolněn
5. Agent čte `.jervis/instructions.md`, pracuje v workspace
6. Agent zapíše `.jervis/result.json` s výsledkem
7. `AgentTaskWatcher` polluje CODING tasks (každých 10s)
8. Watcher detekuje completed job → `collect_result()` čte `result.json`
9. Watcher notifikuje Kotlin (→ PROCESSING) a resumuje orchestraci
10. Graph pokračuje od `interrupt()`, zpracuje result

**Klíčové soubory:**
- `app/agents/job_runner.py` — `dispatch_coding_agent()`, `get_job_status()`, `collect_result()`
- `app/agent_task_watcher.py` — background polling service
- `app/graph/nodes/execute.py` — dispatch + interrupt pattern
- `app/graph/nodes/git_ops.py` — same pattern for commit/push

### 10.4 Result format

```json
{
  "taskId": "69aedeb939184882a4a8609c",
  "success": true,
  "summary": "Implemented feature X...",
  "agentType": "claude",
  "changedFiles": ["src/main.kt", "src/test.kt"],
  "branch": "bugfix/UFO-4166",
  "timestamp": "2026-03-09T14:52:48.000Z"
}
```

The `branch` field is critical for MR/PR creation — `AgentTaskWatcher` uses it to call `create_merge_request()` after job completion. Written by `entrypoint-job.sh` (via `_JERVIS_BRANCH` env var) and `claude_sdk_runner.py` (via `_get_current_branch()`).

### 10.5 Direct Coding Task Completion Flow

Tasks dispatched from chat (`sourceUrn="chat:coding-agent"`) follow a two-step completion in `AgentTaskWatcher`:

1. `POST /internal/tasks/{id}/agent-completed` → CODING → PROCESSING
2. `POST /orchestrate/v2/report-status-change` → PROCESSING → DONE
3. If `result.branch` exists → create MR/PR + dispatch code review (async)
4. Memory map TASK_REF vertex → COMPLETED

Fix tasks (`sourceUrn="code-review-fix:{id}"`) reuse existing MR URL, don't create new MR.

See `docs/architecture.md` § "Coding Agent → MR/PR → Code Review Pipeline" for full flow.

---

## 11. Workspace Manager — příprava prostředí

**Soubor**: `app/agents/workspace_manager.py`

### 11.1 Struktura `.jervis/` adresáře

```
workspace/
├── .jervis/
│   ├── instructions.md       # Step instructions pro agenta
│   ├── task.json              # Task metadata (id, client, project, type)
│   ├── kb-context.md          # Pre-fetched KB context
│   ├── environment.json       # Raw environment data
│   └── environment.md         # Rendered environment context
├── .claude/
│   └── mcp.json               # MCP server config (Claude only)
├── CLAUDE.md                  # Claude agent instructions + KB tools
└── (agent-specific configs if needed)
```

### 11.2 Claude MCP config

```json
{
  "mcpServers": {
    "jervis": {
      "type": "http",
      "url": "http://jervis-mcp:8100/mcp",
      "headers": {"Authorization": "Bearer <token>"}
    }
  }
}
```

### 11.3 CLAUDE.md pro coding

Obsahuje:
- Název projektu, klient, popis úlohy
- Forbidden actions: NIKDY nepoužívej git příkazy
- KB tools: 7 nástrojů pro runtime KB přístup (+ kb_resolve_alias)
- Environment tools: 6 nástrojů pro K8s namespace inspekci
- kubectl access section (when environment available): assigned namespace(s), `environment_sync_from_k8s` tool reference
- Pravidla: write result to `.jervis/result.json`

### 11.4 CLAUDE.md pro git delegaci

Permisivní verze — `ALLOW_GIT=true`:
- Povoleno: git add, commit, push, branch
- Zakázáno: force push, reset --hard, rebase
- Used: `git_operations` node deleguje commit/push na Claude agenta

---

## 12. Context Store — hierarchické úložiště

**Soubor**: `app/context/context_store.py`

### 12.1 MongoDB kolekce

```
orchestrator_context:
  _id: ObjectId
  task_id: str
  scope: "step" | "goal" | "epic" | "task" | "agent_result"
  scope_key: "goal/0/step/1"
  summary: str (krátký pro list)
  detail: dict (plný pro on-demand fetch)
  created_at: datetime
  expire_at: datetime (30 days TTL)
```

### 12.2 Scope hierarchy

```
task_id
├── scope=step, key=goal/0/step/0
├── scope=step, key=goal/0/step/1
├── scope=goal, key=goal/0
├── scope=step, key=goal/1/step/0
├── scope=goal, key=goal/1
├── scope=agent_result, key=goal/0/step/0
└── scope=task, key=final
```

### 12.3 Použití

- `save_step_result()` — po execute_step
- `save_goal_summary()` — po advance_goal
- `assemble_step_context()` — pro execute_step (prev step, KB context)
- `assemble_evaluate_context()` — pro evaluate (result, rules)
- `assemble_epic_review_context()` — pro epic review (goal names + status only)

---

## 13. Approval Flow — interrupt/resume mechanismus

### 13.1 Interrupt body

```python
interrupt({
    "type": "approval_request" | "clarification",
    "action": "clarify" | "commit" | "push" | "cloud_model" | "epic_plan" | "generative_design",
    "description": "Human-readable popis",
    "task_id": task.id,
    # + action-specific fields (branch, changed_files, cloud_tier, goals_count, ...)
})
```

### 13.2 Kde se interrupt volá

| Node | Action | Kdy |
|------|--------|-----|
| `intake` | `clarify` | Goal is unclear → mandatory clarification |
| `respond` | `clarify` | Agent needs user input (via `ask_user` tool) |
| `git_operations` | `commit` | Before commit (if `require_approval_commit`) |
| `git_operations` | `push` | Before push (if `require_approval_push && auto_push`) |
| `_helpers.py` | `cloud_model` | Local LLM failed, cloud not auto-enabled |
| `plan_epic` | `epic_plan` | Show wave structure for approval |
| `design` | `generative_design` | Show generated plan for approval |

### 13.3 Resume value

```python
resume_value = {
    "approved": True/False,
    "reason": "user's text input",
    "modification": None,  # reserved
}
```

Pro clarification: `approved=True` vždy, `reason` = user's answer text

### 13.4 State po interrupt

- FOREGROUND task: `DONE` (keeps `orchestratorThreadId`)
  - Clarification/approval emitováno do chatu jako ASSISTANT message
  - User odpovídá přímo v chatu → task reused → resume
- BACKGROUND task: `USER_TASK` (notification v sidebar)
  - User responds in sidebar → new QUEUED

---

## 14. Concurrency Control — multi-orchestration

### 14.1 Tři vrstvy

**Kotlin (early guard)**:
```kotlin
val orchestratingCount = taskRepository.countByState(TaskStateEnum.PROCESSING)
if (orchestratingCount >= maxConcurrent) return false  // skip dispatch
```

**Python**: Žádný umělý limit — orchestrátor zpracovává neomezeně souběžných požadavků. Concurrency řídí router (GPU queue) a Kotlin (Kotlin early guard).

**Per-agent type limits** (K8s Job level):
```python
MAX_CONCURRENT = {"claude": 2, "kilo": 2}
```

### 14.2 Non-blocking dispatch model

Async agent dispatch — Python vrátí HTTP 200 okamžitě, K8s Job běží na pozadí:

```
Thread 1: dispatch → interrupt → thread free
Thread 2: dispatch → interrupt → thread free
  [oba K8s Jobs běží paralelně, AgentTaskWatcher je resumuje]
Thread 1: [watcher resumuje] → process result → done
Thread 2: [watcher resumuje] → process result → done
```

### 14.3 Task state machine

```
NEW → PROCESSING (dispatch to Python)
    → CODING (coding agent K8s Job dispatched)
    → PROCESSING (agent completed, graph resumed)
    → DONE / FAILED
```

### 14.4 Multi-pod concurrency

Orchestrátor nemá globální lock — více podů může zpracovávat požadavky souběžně. Kotlin BackgroundEngine zajišťuje, že na GPU jde vždy jen jeden background task (atomic claim přes DB). Chat (foreground) nemá žádné omezení počtu souběžných požadavků.

---

## 15. Stuck detection a liveness

### 15.1 Timestamp-based stuck detection (Kotlin)

Task-level stuck detection uses DB timestamps instead of in-memory heartbeat trackers (OrchestratorHeartbeatTracker and CorrectionHeartbeatTracker have been removed):

- **`orchestrationStartedAt`**: Set when task enters `PROCESSING`
- **`stateChangedAt`**: Updated on each `/internal/orchestrator-progress` callback
- **`STUCK_THRESHOLD_MINUTES = 15`**: If no progress for 15 min, task is considered stuck

This approach survives server restarts (timestamps are in MongoDB) and eliminates in-memory state synchronization issues.

### 15.2 BackgroundEngine stuck detection

```
every 60s:
  for each task in PROCESSING:
    lastUpdate = task.stateChangedAt ?: task.orchestrationStartedAt
    if lastUpdate == null:
      // Task just dispatched, wait
      continue
    if Duration.between(lastUpdate, now) < 15 minutes:  // STUCK_THRESHOLD_MINUTES
      // Recent activity — all good
      continue
    // Stuck → poll Python directly
    try:
      status = pythonClient.getStatus(threadId)
      orchestratorStatusHandler.handleStatusChange(...)
    catch (connectionError):
      // Python unreachable → reset task for retry
      task.state = QUEUED
      task.orchestratorThreadId = null
```

### 15.3 LLM token-arrival timeout (Python)

```python
TOKEN_TIMEOUT_SECONDS = 300  # 5 min

async def _iter_with_timeout(stream):
    aiter = stream.__aiter__()
    while True:
        chunk = await asyncio.wait_for(aiter.__anext__(), timeout=TOKEN_TIMEOUT_SECONDS)
        yield chunk
    # raises TokenTimeoutError on timeout
```

Note: This is a read timeout on the LLM stream (token arrival monitoring), separate from task-level stuck detection above.

### 15.3a MeetingContinuousIndexer stuck detection

Pipeline 5 stuck detection for correction tasks uses `stateChangedAt` timestamp:
- **`STUCK_CORRECTING_THRESHOLD_MINUTES = 15`**: Meetings in CORRECTING state for >15 min without progress are reset
- Replaces the former `CorrectionHeartbeatTracker` in-memory approach

### 15.3b Python crash handler

The Python orchestrator registers a crash handler (`atexit` + `SIGTERM` signal handler) that sends best-effort error callbacks for all active tasks on unexpected shutdown. This ensures Kotlin's stuck detection doesn't need to wait the full 15-min threshold when the Python process crashes.

### 15.4 AgentTaskWatcher — K8s Job monitoring

**Soubor**: `app/agent_task_watcher.py`

Background asyncio service polling for CODING tasks:

```python
class AgentTaskWatcher:
    async def _poll_once(self):
        # 1. GET /internal/tasks/by-state?state=CODING
        # 2. For each task: check K8s Job status via job_runner.get_job_status()
        # 3. On completion: collect result, notify Kotlin, resume orchestration
```

- Poll interval: `agent_watcher_poll_interval` (default 10s)
- Job timeout determined by `agent_timeout_*` per agent type (e.g. 1800s claude)
- Started/stopped in `main.py` lifespan
- Survives pod restarts — all state in MongoDB (TaskDocument + LangGraph checkpoints)

**Internal Kotlin endpoints used by watcher:**
- `GET /internal/tasks/by-state?state=CODING` — fetch waiting tasks
- `POST /internal/tasks/{taskId}/agent-completed` — mark agent done, transition to PROCESSING
- `POST /internal/tasks/{taskId}/agent-dispatched` — mark task as CODING (called from graph nodes)

---

## 16. Chat Context Persistence — paměť agenta

### 16.1 Tři vrstvy

| Vrstva | Storage | Max tokenů | Obsah |
|--------|---------|------------|-------|
| Recent messages | In request (verbatim) | ~2000 | Last 20 messages as-is |
| Rolling summaries | MongoDB `chat_summaries` | ~1500 | LLM-compressed blocks po 20 zprávách |
| Total count | In request | - | Celkový počet zpráv |

### 16.2 Příprava (Kotlin → Python)

`ChatHistoryService.prepareChatHistoryPayload(taskId)`:
1. Load all `ChatMessageDocument` for task (ordered by sequence)
2. Take last 20 → `recent_messages` (verbatim)
3. Load `ChatSummaryDocument` → take last 15 → `summary_blocks`
4. Return `ChatHistoryPayloadDto`

### 16.3 Komprese (async po dokončení)

`ChatHistoryService.compressIfNeeded(taskId, clientId)`:
1. Count total messages; if ≤ 20 → skip
2. Find last summarized sequence
3. Messages before recent window, after last summary → unsummarized
4. If unsummarized ≥ 20 → POST `/internal/compress-chat` (Python LLM)
5. Store `ChatSummaryDocument`

### 16.4 Summary trust level

Souhrny (rolling summaries) mohou obsahovat halucinace z dřívějších LLM odpovědí. Aby se zabránilo self-reinforcing loop (špatná odpověď → uložena → komprimována → znovu použita jako fakt):

1. **Context assembler** (`context.py`) označuje souhrny prefixem `[Neověřený souhrn]` a přidává varování k celé sekci souhrnů.
2. **System prompt** (`system_prompt.py`) obsahuje explicitní instrukci "KRITICKÁ DISTANCE K HISTORII" — LLM nesmí přebírat fakta ze souhrnů bez ověření přes tools.

### 16.5 Použití v nodech

| Node | Co používá | Jak |
|------|-----------|-----|
| intake | `recent_messages[-5:]` | Klasifikace kontextu ("continuation" vs "new topic") |
| respond | Full history (summaries + recent) | Kompletní konverzační kontext v LLM promptu |
| evidence | `summary_blocks` | Populate `EvidencePack.chat_history_summary` |
| plan | `summary_blocks[].key_decisions` | Key decisions pro plánování (posledních 10) |
| finalize | `total_message_count` + key decisions | Konverzační stats ve finálním reportu |

---

## 17. Correction Agent — korekce přepisů

**Soubor**: `app/whisper/correction_agent.py`

Transcript correction agent sdílí orchestrátor service kvůli přístupu k Ollama GPU.

### 17.1 Endpointy

| Endpoint | Účel |
|----------|------|
| `POST /correction/submit` | Uloží korekční pravidlo do KB |
| `POST /correction/correct` | Opraví segmenty pomocí KB + Ollama |
| `POST /correction/list` | Výpis pravidel pro klienta |
| `POST /correction/delete` | Smaže pravidlo z KB |
| `POST /correction/instruct` | Re-korekce dle NL instrukce |
| `POST /correction/correct-targeted` | Cílená korekce retranskripcí |
| `POST /correction/answer` | Uloží odpovědi na otázky jako pravidla |

### 17.2 Klíčové parametry

- Model: `qwen3-coder-tool:30b` (non-reasoning)
- CHUNK_SIZE: 20 segmentů na LLM call
- OUTPUT_BUDGET: 8192 tokenů
- GPU_CTX_CAP: 49152 (nad tím spill do CPU RAM)
- LLM liveness: 300s (5 min bez tokenu = dead)
- Stuck detection: `STUCK_CORRECTING_THRESHOLD_MINUTES = 15` (timestamp-based, via `stateChangedAt`)
- Korekce uloženy jako KB chunks s `kind="transcript_correction"`

---

## 18. Delegation Graph — přehled

> **Status:** Feature-flagged (`use_delegation_graph = False` default). Nový multi-agent delegační systém běží vedle legacy 14-node grafu. Přepíná se přes `get_orchestrator_graph()`.

### 18.1 Motivace

Stávající orchestrátor je monolitický 14-nodový graf optimalizovaný pro coding úlohy (4 kategorie: ADVICE, SINGLE_TASK, EPIC, GENERATIVE). Nový delegační systém přestavuje orchestrátor na **univerzálního multi-agent asistenta** — nejen programování, ale i projekt management, komunikace, právní analýza, DevOps, bezpečnost a další domény.

**Klíčová změna:** Místo hardcoded cest pro 4 kategorie máme univerzální delegační engine. `plan_delegations` node vybírá z registru 19+ specialist agentů a sestavuje DAG (directed acyclic graph) delegací. Agenti mohou volat sub-agenty rekurzivně (max depth 4).

### 18.2 Graf — vizuální diagram

```
                    ┌──────────┐
                    │  ENTRY   │
                    └────┬─────┘
                         │
                  ┌──────▼──────┐
                  │   intake     │  (reused from legacy, extended with
                  │              │   language detection, session memory)
                  └──────┬──────┘
                         │
                  ┌──────▼──────┐
                  │evidence_pack │  (reused from legacy)
                  └──────┬──────┘
                         │
              ┌──────────▼──────────┐
              │  plan_delegations   │  NEW — LLM-driven agent selection
              │  (reads registry,   │  Outputs: ExecutionPlan (delegations
              │   procedural mem,   │           + parallel groups)
              │   session memory)   │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │ execute_delegation  │◄─┐  NEW — dispatches via DAGExecutor
              │ (parallel groups,   │  │  Progress reporting to Kotlin
              │  agent dispatch)    │  │  Full results → context_store
              └──────────┬──────────┘  │
                         │             │
                    ┌────▼────┐        │
                    │ more    │────yes──┘  (loop back if pending delegations)
                    │pending? │
                    └────┬────┘
                         │ no
              ┌──────────▼──────────┐
              │    synthesize       │  NEW — merges AgentOutput results
              │ (LLM combine,      │  RAG cross-check vs KB
              │  translate to       │  Outputs: final_result
              │  response_language) │
              └──────────┬──────────┘
                         │
                  ┌──────▼──────┐
                  │  finalize    │  (reused from legacy, extended)
                  └──────┬──────┘
                         │
                    ┌────▼────┐
                    │   END   │
                    └─────────┘
```

### 18.3 Porovnání s legacy grafem

| Aspekt | Legacy (14 nodů) | Delegation (7 nodů) |
|--------|------------------|---------------------|
| **Routing** | Hardcoded 4 cesty (ADVICE/SINGLE_TASK/EPIC/GENERATIVE) | Univerzální delegation engine |
| **Agenti** | 2 coding agenti (Claude/Kilo) | 19+ specialist agentů |
| **Domény** | Pouze kód + tracker | Kód, DevOps, PM, komunikace, právní, finanční, osobní... |
| **Execution** | Sekvenční (step by step) | DAG s paralelními skupinami |
| **Memory** | Chat history + KB prefetch | + Session Memory + Procedural Memory |
| **Jazyk** | Odpověď vždy česky | Detekce jazyka vstupu, odpověď v `response_language` |
| **Graph builder** | `build_orchestrator_graph()` | `build_delegation_graph()` |

### 18.4 Graph builder — kód

**Soubor**: `app/graph/orchestrator.py`

```python
def build_delegation_graph() -> StateGraph:
    """Build 7-node delegation graph (new multi-agent system)."""
    graph = StateGraph(OrchestratorState)

    # Reused nodes
    graph.add_node("intake", intake)                        # Extended
    graph.add_node("evidence_pack", evidence_pack)          # Reused
    graph.add_node("finalize", finalize)                    # Extended

    # New delegation nodes
    graph.add_node("plan_delegations", plan_delegations)
    graph.add_node("execute_delegation", execute_delegation)
    graph.add_node("synthesize", synthesize)

    # Edges
    graph.set_entry_point("intake")
    graph.add_edge("intake", "evidence_pack")
    graph.add_edge("evidence_pack", "plan_delegations")
    graph.add_edge("plan_delegations", "execute_delegation")
    graph.add_conditional_edges(
        "execute_delegation",
        _route_after_execution,
        {
            "execute_delegation": "execute_delegation",  # More pending
            "synthesize": "synthesize",                  # All done
        },
    )
    graph.add_edge("synthesize", "finalize")
    graph.add_edge("finalize", END)

    return graph
```

### 18.5 Graph switching — feature flag

```python
def get_orchestrator_graph():
    """Feature flag: delegation graph vs legacy graph."""
    global _compiled_graph
    if _compiled_graph is None:
        if _checkpointer is None:
            raise RuntimeError("Checkpointer not initialized.")

        if settings.use_delegation_graph:
            graph = build_delegation_graph()
        else:
            graph = build_orchestrator_graph()  # Legacy 14-node

        _compiled_graph = graph.compile(checkpointer=_checkpointer)
    return _compiled_graph
```

**Invariant:** Oba grafy sdílejí stejný `MongoDBSaver` checkpointer, stejnou `OrchestratorState` definici, a stejné API endpointy. Kotlin server nepotřebuje žádné změny.

---

## 19. Delegation Graph — nové nodes

### 19.1 plan_delegations

**Soubor**: `app/graph/nodes/plan_delegations.py`
**Účel**: LLM-driven výběr agentů a sestavení execution plánu

**Vstupy** (ze state):
- `evidence_pack` — KB context, tracker artifacts
- `task_category`, `task_action` — z intake klasifikace
- `session_memory` — recent decisions pro tento client/project
- `response_language` — detekovaný jazyk (z intake)

**Kroky**:
1. Načte `AgentRegistry.get_capability_summary()` — textový přehled všech dostupných agentů, jejich domén a nástrojů
2. Hledá v Procedural Memory (`find_procedure(trigger_pattern, client_id)`) — existuje naučený postup pro tento typ úkolu?
3. Načte Session Memory — čerstvá rozhodnutí pro tento client/project (max 50 entries, 7 dní TTL)
4. Sestaví LLM prompt s kontextem:
   - User query + evidence pack
   - Seznam agentů s capabilities
   - Procedural memory hit (pokud existuje)
   - Session memory entries
5. LLM structured output → `ExecutionPlan`:

```python
class ExecutionPlan(BaseModel):
    delegations: list[DelegationMessage]     # Konkrétní delegace na agenty
    parallel_groups: list[list[str]]         # Skupiny delegation_ids pro paralelní běh
    domain: DomainType                       # Primární doména úkolu
```

**LLM output format**:
```json
{
  "domain": "code",
  "delegations": [
    {
      "delegation_id": "del-001",
      "agent_name": "research",
      "task_summary": "Find architecture docs for auth module",
      "expected_output": "Summary of current auth architecture"
    },
    {
      "delegation_id": "del-002",
      "agent_name": "coding",
      "task_summary": "Implement OAuth2 login endpoint",
      "expected_output": "Working implementation with tests",
      "constraints": ["no changes to database schema"]
    }
  ],
  "parallel_groups": [["del-001"], ["del-002"]]
}
```

**Poznámka k sekvenčnosti**: Groups run sequentially (group 1 before group 2), delegations within a group run in parallel. V příkladu výše: research first, then coding.

**Output**: `execution_plan`, `delegation_states` (všechny v `PENDING`), `domain`, `response_language`

### 19.2 execute_delegation

**Soubor**: `app/graph/nodes/execute_delegation.py`
**Účel**: Dispatch delegací na agenty přes DAGExecutor

**Kroky**:
1. Načte `execution_plan` ze state
2. Najde další skupinu pending delegací
3. Pro každou delegaci ve skupině:
   a. Sestaví kontext (`assemble_delegation_context` s token budgetem dle depth)
   b. Resolve agent z `AgentRegistry.get(agent_name)`
   c. Nastaví `delegation_states[id].status = RUNNING`
   d. Report progress do Kotlin:
      ```python
      await kotlin_client.report_progress(
          task_id=..., node="execute_delegation",
          message=f"Delegating to {agent_name}: {task_summary[:60]}",
          delegation_id=delegation_id,
          delegation_agent=agent_name,
          delegation_depth=0,
      )
      ```
4. Spustí delegace přes `DAGExecutor`:
   - Paralelní v rámci skupiny (`asyncio.gather`)
   - Sekvenční mezi skupinami
5. Pro každý dokončený výsledek:
   a. `delegation_states[id].status = COMPLETED` (nebo `FAILED`)
   b. `delegation_results[id] = output.result` (summary)
   c. Full result uložen do `context_store` se scope `agent_result`
   d. Přidá do `completed_delegations`
6. Pokud zbývají pending skupiny → route back do `execute_delegation`
7. Pokud vše done → route do `synthesize`

**Routing logic** (`_route_after_execution`):
```python
def _route_after_execution(state: dict) -> str:
    plan = state.get("execution_plan", {})
    completed = set(state.get("completed_delegations", []))
    all_ids = {d["delegation_id"] for d in plan.get("delegations", [])}
    if completed >= all_ids:
        return "synthesize"
    return "execute_delegation"
```

**Output**: Updated `delegation_states`, `completed_delegations`, `delegation_results`, `_delegation_outputs`

### 19.3 synthesize

**Soubor**: `app/graph/nodes/synthesize.py`
**Účel**: Sloučení výsledků z více agentů do koherentní odpovědi

**Kroky**:
1. Načte `_delegation_outputs` — list `AgentOutput` ze všech delegací
2. Pokud jen 1 výsledek a `confidence >= 0.8` → přímo použije jako `final_result`
3. Pokud více výsledků → LLM kombinuje:
   - Input: Všechny agent results + original query + evidence pack
   - Instrukce: "Combine these agent results into a coherent, complete response"
4. **RAG cross-check** — pokud jakýkoli agent nastavil `needs_verification: true`:
   - Extrahuje klíčová tvrzení z výsledku
   - Hledá v KB protichůdné informace (`kb_search`)
   - Pokud rozpor nalezen → přidá varování do odpovědi
5. **Překlad** do `response_language` (z intake):
   - Celý interní chain běží anglicky
   - Finální odpověď se přeloží do detekovaného jazyka vstupu
6. Nastaví `final_result`

**Output**: `final_result`, updated `artifacts`

---

## 20. OrchestratorState — delegation fields

Nový delegační graf přidává tyto fieldy k existujícímu `OrchestratorState`. Stávající fieldy zůstávají nezměněné pro backward compatibility.

```python
class OrchestratorState(TypedDict, total=False):
    # --- Existing fields (unchanged, see section 6) ---
    task: dict
    rules: dict
    environment: dict | None
    # ... all existing fields preserved ...

    # --- NEW: Delegation system ---
    execution_plan: dict | None              # ExecutionPlan.model_dump()
    delegation_states: dict                  # {delegation_id: DelegationState.model_dump()}
    active_delegation_id: str | None         # Currently running delegation
    completed_delegations: list              # List of completed delegation IDs
    delegation_results: dict                 # {delegation_id: result_summary}
    response_language: str                   # ISO 639-1 code (cs/en/es/de...)
    domain: str | None                       # DomainType (code/devops/legal/...)
    session_memory: list                     # Recent SessionEntry dicts for this client/project
    _delegation_outputs: list                # List of AgentOutput.model_dump() (transient)
```

### 20.1 Field descriptions

| Field | Type | Set by | Used by | Description |
|-------|------|--------|---------|-------------|
| `execution_plan` | dict | `plan_delegations` | `execute_delegation` | DAG of delegations with parallel groups |
| `delegation_states` | dict | `plan_delegations`, `execute_delegation` | `execute_delegation`, `synthesize` | Status tracking per delegation (PENDING/RUNNING/COMPLETED/FAILED) |
| `active_delegation_id` | str | `execute_delegation` | `execute_delegation` | Currently executing delegation (for checkpoint/restore) |
| `completed_delegations` | list | `execute_delegation` | `execute_delegation` (routing) | Set of finished delegation IDs |
| `delegation_results` | dict | `execute_delegation` | `synthesize`, `finalize` | Summary result per delegation |
| `response_language` | str | `intake` | `synthesize`, `finalize` | Detected language of user input (default "en") |
| `domain` | str | `plan_delegations` | Progress reporting | Primary domain of the task |
| `session_memory` | list | `intake` | `plan_delegations` | Recent decisions loaded from MongoDB `session_memory` collection |
| `_delegation_outputs` | list | `execute_delegation` | `synthesize` | Full AgentOutput objects (transient, not checkpointed) |

### 20.2 Initial state (delegation graph)

```python
def _build_initial_state_delegation(request: OrchestrateRequest) -> dict:
    """Build initial state for delegation graph."""
    state = _build_initial_state(request)  # All legacy fields
    state.update({
        # Delegation system
        "execution_plan": None,
        "delegation_states": {},
        "active_delegation_id": None,
        "completed_delegations": [],
        "delegation_results": {},
        "response_language": "en",  # Detected in intake
        "domain": None,
        "session_memory": [],
        "_delegation_outputs": [],
    })
    return state
```

---

## 21. DAG Executor — paralelní execution

**Soubor**: `app/graph/dag_executor.py`

### 21.1 Koncept

DAG Executor provádí delegace podle `ExecutionPlan.parallel_groups`:
- **Sekvenční** mezi skupinami (group 1 musí být done před group 2)
- **Paralelní** v rámci skupiny (nezávislé delegace běží současně)
- Respektuje závislosti mezi delegacemi

### 21.2 Implementace

```python
class DAGExecutor:
    """Execute delegations respecting parallel groups and dependencies."""

    def __init__(self, registry: AgentRegistry, context_store: ContextStore):
        self._registry = registry
        self._store = context_store

    async def execute_group(
        self,
        delegations: list[DelegationMessage],
        state: dict,
    ) -> list[AgentOutput]:
        """Execute one parallel group of delegations."""
        tasks = []
        for msg in delegations:
            agent = self._registry.get(msg.agent_name)
            if agent is None:
                # Fallback to LegacyAgent if specialist not found
                agent = self._registry.get("legacy")
            tasks.append(self._execute_one(agent, msg, state))

        # asyncio.gather — parallel execution within group
        results = await asyncio.gather(*tasks, return_exceptions=True)

        outputs = []
        for msg, result in zip(delegations, results):
            if isinstance(result, Exception):
                outputs.append(AgentOutput(
                    delegation_id=msg.delegation_id,
                    agent_name=msg.agent_name,
                    success=False,
                    result=f"Agent failed: {result}",
                    confidence=0.0,
                ))
            else:
                outputs.append(result)

            # Store full result in context_store
            await self._store.save_agent_result(
                task_id=state["task"]["id"],
                delegation_id=msg.delegation_id,
                output=result if not isinstance(result, Exception) else None,
            )

        return outputs

    async def _execute_one(
        self, agent: BaseAgent, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute a single delegation with timeout."""
        return await asyncio.wait_for(
            agent.execute(msg, state),
            timeout=settings.delegation_timeout,
        )
```

### 21.3 Token budgets per depth

Kontext pro agenta je omezen dle hloubky delegace (depth):

| Depth | Budget | Kdo |
|-------|--------|-----|
| 0 | 48,000 tokens | Orchestrátor (direct delegation) |
| 1 | 16,000 tokens | First-level sub-delegation |
| 2 | 8,000 tokens | Second-level sub-delegation |
| 3-4 | 4,000 tokens | Deep sub-delegations |

```python
def _get_token_budget(depth: int) -> int:
    budgets = {
        0: settings.token_budget_depth_0,   # 48000
        1: settings.token_budget_depth_1,   # 16000
        2: settings.token_budget_depth_2,   # 8000
    }
    return budgets.get(depth, settings.token_budget_depth_3)  # 4000
```

### 21.4 Constraint enforcement

- **Max depth 4** — Agent na depth 3 nemůže volat sub-agenta na depth 5
- **Cycle detection** — Stack delegací se trackuje, nelze volat agenta co už je ve stacku
- **Summarization up** — Rodič NIKDY nevidí plný output sub-agenta, jen summary (max 500 znaků). Plný výsledek je v `context_store`.

---

## 22. Agent Communication Protocol

### 22.1 DelegationMessage — vstup pro agenta

```python
class DelegationMessage(BaseModel):
    delegation_id: str                # Unique ID (e.g. "del-001")
    parent_delegation_id: str | None  # For sub-delegations
    depth: int = 0                    # 0=orchestrator, 1-4=sub-agents
    agent_name: str                   # Target agent name
    task_summary: str                 # What the agent should do (ENGLISH)
    context: str = ""                 # Token-budgeted context
    constraints: list[str]            # Restrictions (forbidden files, max changes, etc.)
    expected_output: str = ""         # What orchestrator expects back
    response_language: str = "en"     # ISO 639-1 for final response
    # Data isolation
    client_id: str = ""
    project_id: str | None = None
    group_id: str | None = None       # If set, agent sees KB of entire group
```

### 22.2 AgentOutput — výstup agenta

```python
class AgentOutput(BaseModel):
    delegation_id: str
    agent_name: str
    success: bool
    result: str = ""                  # Main output (text answer, summary)
    structured_data: dict             # Structured data (diff, issues, etc.)
    artifacts: list[str]              # Created files, commits, etc.
    changed_files: list[str]
    sub_delegations: list[str]        # Sub-delegation IDs (for tracing)
    confidence: float = 1.0           # 0.0-1.0
    needs_verification: bool = False  # Request KB cross-check
```

### 22.3 Structured response format

All agents respond with structured format (enforced by `BaseAgent._agentic_loop`):

```
STATUS: 1|0|P
RESULT: <complete, compact content>
ARTIFACTS: <files, commits>
ISSUES: <problems, blockers>
CONFIDENCE: 0.0-1.0
NEEDS_VERIFICATION: true/false
```

- `STATUS: 1` = success, `0` = failure, `P` = partial (needs more work)
- No truncation of agent responses. Agents are instructed to be maximally compact but include all substantive content.
- Full results stored in `context_store` for retrieval. Only summaries passed up the delegation chain.

### 22.4 Jazyková pravidla

- **Intake node** detekuje jazyk vstupního requestu a ukládá do `response_language`
- **Celý interní chain** běží ANGLICKY (LLM instructions, delegation messages, agent outputs)
- **Finální odpověď** (synthesize/finalize) se přeloží do `response_language`
- **Proč anglicky interně:** LLM modely jsou nejpřesnější v angličtině, menší chybovost, menší token count

### 22.5 Failure handling

| Typ selhání | Detekce | Akce |
|-------------|---------|------|
| **Soft failure** | `confidence < 0.5` | Orchestrátor zkusí jiného agenta nebo eskaluje na uživatele |
| **Hard failure** | Exception/timeout | Retry 1x, pak eskalace |
| **Quality failure** | RAG cross-check vs KB neprošel | Vrátí agentovi s vysvětlením co je špatně |

### 22.6 LLM eskalační řetězec (per agent)

```
1. LOCAL_FAST (qwen3-coder-tool, 8k ctx)       → rychlé, jednoduché úkoly
2. LOCAL_STANDARD (qwen3-coder-tool, 32k ctx)   → standardní
3. LOCAL_LARGE (qwen3-coder-tool, 49k ctx)      → max local
4. CLOUD (Anthropic/OpenAI/Gemini)               → až když local nestačí
```

Agent netuší kdo ho odbavuje — Ollama Router řídí GPU vs CPU routing transparentně.

---

## 23. Specialist Agents — registr 19 agentů

### 23.1 AgentRegistry

**Soubor**: `app/agents/registry.py`

```python
class AgentRegistry:
    """Singleton registry of all available specialist agents."""
    _instance = None
    _agents: dict[str, BaseAgent]

    @classmethod
    def instance(cls) -> AgentRegistry

    def register(self, agent: BaseAgent) -> None
    def get(self, name: str) -> BaseAgent | None
    def list_agents(self) -> list[AgentCapability]
    def find_for_domain(self, domain: DomainType) -> list[BaseAgent]
    def get_capability_summary(self) -> str   # Text summary for LLM in plan_delegations
```

`get_capability_summary()` vrací textový přehled pro LLM prompt:
```
Available agents:
- coding: Delegates to coding agents (Claude/Kilo), manages workspace. Domains: code
- research: KB search, web search, code exploration, filesystem tools. Domains: research
- issue_tracker: CRUD issues in Jira/GitHub/GitLab via Kotlin API. Domains: project_management, code
- wiki: CRUD wiki pages in Confluence/GitHub/GitLab. Domains: research, communication
- documentation: Generates/updates project documentation. Domains: code, research
- devops: CI/CD, Docker, K8s, deployment operations. Domains: devops
...
```

### 23.2 BaseAgent

**Soubor**: `app/agents/base.py`

```python
class BaseAgent(ABC):
    name: str                         # Unique agent name
    description: str                  # For orchestrator LLM prompt
    domains: list[DomainType]         # Where agent operates
    tools: list[dict]                 # OpenAI function-calling schemas
    can_sub_delegate: bool = True     # Can call sub-agents?
    max_depth: int = 4

    @abstractmethod
    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput:
        """Main execution."""

    async def _agentic_loop(
        self, msg: DelegationMessage, state: dict,
        system_prompt: str, max_iterations: int = 10,
    ) -> AgentOutput:
        """Shared agentic loop: LLM call → tool calls → iterate."""

    async def _call_llm(self, messages, tools=None, model_tier=None) -> str:
        """LLM calling with tier selection and token-arrival liveness."""

    async def _execute_tool(self, tool_name, arguments, state) -> str:
        """Execute registered tool via ToolExecutor."""

    async def _sub_delegate(
        self, target_agent_name, task_summary, context, parent_msg, state,
    ) -> AgentOutput:
        """Delegate to another agent (depth+1, cycle detection)."""
```

### 23.3 Agent catalog

#### Tier 1 — Core agenti

| # | Agent | Soubor | Odpovědnost | Sub-deleguje na |
|---|-------|--------|-------------|-----------------|
| 1 | **CodingAgent** | `code_agent.py` | K8s Job delegace (Claude/Kilo), workspace, results | - |
| 2 | **GitAgent** | `git_agent.py` | Commit, push, branch, PR/MR, merge conflicts | CodingAgent |
| 3 | **CodeReviewAgent** | `review_agent.py` | Code review, soulad se zadáním, forbidden files | CodingAgent, TestAgent, ResearchAgent |
| 4 | **TestAgent** | `test_agent.py` | Generování testů, spouštění, analýza | CodingAgent |
| 5 | **ResearchAgent** | `research_agent.py` | KB search, web search, code exploration, filesystem | - |

> **CodingAgent** je centrální brána ke coding agentům. Centralizovaná kontrola workspace, job management, cost tracking.

#### Tier 2 — DevOps & Project Management

| # | Agent | Soubor | Odpovědnost | Sub-deleguje na |
|---|-------|--------|-------------|-----------------|
| 6 | **IssueTrackerAgent** | `tracker_agent.py` | CRUD issues (Jira/GitHub/GitLab), search, transitions | ResearchAgent |
| 7 | **WikiAgent** | `wiki_agent.py` | CRUD wiki stránek (Confluence/GitHub/GitLab) | ResearchAgent |
| 8 | **DocumentationAgent** | `documentation_agent.py` | Generuje/updatuje docs, READMEs, API docs | ResearchAgent |
| 9 | **DevOpsAgent** | `devops_agent.py` | CI/CD, Docker, K8s, deployment | - |
| 10 | **ProjectManagementAgent** | `project_management_agent.py` | Sprint planning, epic management | IssueTrackerAgent |
| 11 | **SecurityAgent** | `security_agent.py` | Security analýza, vulnerability scan | ResearchAgent |

#### Tier 3 — Komunikace & Administrativa

| # | Agent | Soubor | Odpovědnost | Sub-deleguje na |
|---|-------|--------|-------------|-----------------|
| 12 | **CommunicationAgent** | `communication_agent.py` | Hub pro veškerou komunikaci, drafty, reporty | EmailAgent |
| 13 | **EmailAgent** | `email_agent.py` | Read/compose/search emailů | - |
| 14 | **CalendarAgent** | `calendar_agent.py` | Termíny, reminders, scheduling | - |
| 15 | **AdministrativeAgent** | `administrative_agent.py` | Plánování cest, logistika | CalendarAgent |

#### Tier 4 — Byznysová podpora

| # | Agent | Soubor | Odpovědnost | Sub-deleguje na |
|---|-------|--------|-------------|-----------------|
| 16 | **LegalAgent** | `legal_agent.py` | Smlouvy, NDA, compliance | ResearchAgent |
| 17 | **FinancialAgent** | `financial_agent.py` | Rozpočet, faktury, odhady | - |
| 18 | **PersonalAgent** | `personal_agent.py` | Nákupy, dovolená, osobní asistence | CalendarAgent |
| 19 | **LearningAgent** | `learning_agent.py` | Tutoriály, evaluace technologií | ResearchAgent |

#### Special — Fallback

| Agent | Soubor | Odpovědnost |
|-------|--------|-------------|
| **LegacyAgent** | `legacy_agent.py` | Wrapper stávající logiky — fallback pokud specialist selže |

### 23.4 Implementační vzor (sdílený)

Všichni specialist agenti sdílejí stejný vzor:

```python
class SpecialistAgent(BaseAgent):
    name = "specialist_name"
    description = "What this agent does"
    domains = [DomainType.CODE, DomainType.RESEARCH]
    tools = [TOOL_KB_SEARCH, TOOL_SPECIFIC, ...]
    can_sub_delegate = True

    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput:
        # 1. Optional: sub-delegate to ResearchAgent for context
        if self._needs_research(msg):
            research_output = await self._sub_delegate(
                target_agent_name="research",
                task_summary=f"Gather context for: {msg.task_summary}",
                context=msg.context,
                parent_msg=msg,
                state=state,
            )
            enriched_context = f"{msg.context}\n---\n{research_output.result}"
            msg = msg.model_copy(update={"context": enriched_context})

        # 2. Build agent-specific system prompt
        system_prompt = "You are the SpecialistAgent..."

        # 3. Agentic loop: LLM call → tool calls → iterate
        return await self._agentic_loop(
            msg=msg, state=state,
            system_prompt=system_prompt,
            max_iterations=10,
        )
```

### 23.5 Registrace agentů (startup)

V `app/main.py` lifespan:

```python
from app.agents.registry import AgentRegistry
from app.agents.specialists.tracker_agent import IssueTrackerAgent
from app.agents.specialists.wiki_agent import WikiAgent
# ... all 19 agents

registry = AgentRegistry.instance()
registry.register(IssueTrackerAgent())
registry.register(WikiAgent())
# ... all agents + LegacyAgent
```

---

## 24. Memory Integration — 4 vrstvy

### 24.1 Přehled vrstev

| Vrstva | Storage | TTL | Obsah | Použití v delegation grafu |
|--------|---------|-----|-------|---------------------------|
| **Working Memory** | OrchestratorState (checkpoint) | Orchestrace | Aktuální stav, delegation stack | Celý graf |
| **Episodic Memory** | MongoDB `context_store` | 30 dní | Výsledky delegací, interakce | `execute_delegation` ukládá, `synthesize` čte |
| **Semantic Memory** | KB (Weaviate + ArangoDB) | Permanentní | Fakta, konvence, pravidla | `evidence_pack`, `synthesize` (cross-check) |
| **Procedural Memory** | ArangoDB `ProcedureNode` | Permanentní (decay) | Naučené workflow postupy | `plan_delegations` |

Plus:

| Vrstva | Storage | TTL | Obsah | Použití |
|--------|---------|-----|-------|---------|
| **Session Memory** | MongoDB `session_memory` | 7 dní | Per-client/project rozhodnutí | `intake` loads, `plan_delegations` reads |

### 24.2 Session Memory

**Soubor**: `app/context/session_memory.py`
**Collection**: MongoDB `session_memory`

```python
class SessionEntry(BaseModel):
    timestamp: str
    source: str               # "chat" | "background" | "orchestrator_decision"
    summary: str              # Max 200 chars
    details: dict | None
    task_id: str | None

class SessionMemoryPayload(BaseModel):
    client_id: str
    project_id: str | None
    entries: list[SessionEntry]   # Max 50 entries per client/project
```

**Lifecycle:**
1. `intake` node loads session memory for `client_id + project_id`
2. `plan_delegations` reads session memory entries to inform agent selection
3. After orchestration completes, key decisions saved to session memory
4. TTL: 7 days auto-expiry. Important items also saved to KB (permanent).

**Proč ne jen KB:** Session Memory = fast key-value lookup pro "co se stalo před hodinou". KB = semantic search pro "najdi mi všechno o technologii X". Session Memory je cache, KB je storage.

### 24.3 Procedural Memory

**Soubor**: `app/context/procedural_memory.py`
**Storage**: ArangoDB `ProcedureNode` (via KB service REST API)

```python
class ProcedureNode(BaseModel):
    trigger_pattern: str            # "email_with_question", "task_completion", "bug_report"
    procedure_steps: list[ProcedureStep]
    success_rate: float = 0.0       # 0.0-1.0
    last_used: str | None
    usage_count: int = 0
    source: str = "learned"         # "learned" | "user_defined" | "default"
    client_id: str = ""             # Per-client procedures

class ProcedureStep(BaseModel):
    agent: str                      # Agent name (e.g. "code_review")
    action: str                     # What to do
    parameters: dict                # Agent-specific params
```

**Příklady uložených postupů:**
- `task_completion`: Code review → Deploy → Test → Close issue → Notify
- `bug_report`: Search KB → Analyze code → Fix → Test → PR
- `email_deadline_question`: Find issue → Check status → Estimate deadline → Reply

**Učení:**
1. Po úspěšné orchestraci se vzor uloží (`source: "learned"`)
2. Při podobném úkolu se použije jako šablona v `plan_delegations`
3. Pokud postup neexistuje → orchestrátor se ZEPTÁ uživatele → odpověď uloží jako nový postup (`source: "user_defined"`)
4. `user_defined` mají vždy vyšší prioritu než `learned`
5. Usage-decay: nepoužívané postupy postupně klesají v prioritě

### 24.4 Context assembly s token budgets

**Soubor**: `app/context/context_assembler.py`

```python
async def assemble_delegation_context(
    delegation: DelegationMessage,
    evidence_pack: dict,
    session_memory: list,
) -> str:
    """Build context for agent delegation with token budget."""
    budget = _get_token_budget(delegation.depth)

    # Priority order (higher priority = more budget):
    # 1. Task description + constraints (always included)
    # 2. Evidence pack (KB results, tracker artifacts)
    # 3. Session memory (recent decisions)
    # 4. Chat history summary

    context_parts = []
    remaining = budget

    # Always include task
    task_section = f"Task: {delegation.task_summary}\n"
    context_parts.append(task_section)
    remaining -= _count_tokens(task_section)

    # Evidence (up to 60% of remaining)
    evidence_budget = int(remaining * 0.6)
    evidence_section = _trim_to_budget(evidence_pack, evidence_budget)
    context_parts.append(evidence_section)
    remaining -= _count_tokens(evidence_section)

    # Session memory (up to 20% of remaining)
    if session_memory:
        mem_budget = int(remaining * 0.5)
        mem_section = _trim_to_budget(session_memory, mem_budget)
        context_parts.append(mem_section)

    return "\n\n".join(context_parts)
```

### 24.5 Retention policy — co uložit vs zahodit

**Soubor**: `app/context/retention_policy.py`

Po dokončení orchestrace:

| Co | Kam | Kdy |
|----|-----|-----|
| User decisions (z chatu, z approval) | KB (permanent) + Session Memory (7d) | Vždy |
| Agent results (success) | `context_store` (30d) | Vždy |
| Agent results (failure) | `context_store` (7d shorter TTL) | Vždy |
| Successful workflow pattern | Procedural Memory | Pokud `use_procedural_memory` flag |
| Key facts discovered | KB | Pokud `confidence >= 0.8` |
| Routine status checks | Nikam (zahodit) | - |

---

## 25. Feature Flags a backward compatibility

### 25.1 Feature flags

**Soubor**: `app/config.py` — Settings class

```python
# Feature flags (multi-agent system) — all default to False
use_delegation_graph: bool = False       # Main switch: 7-node delegation vs 14-node legacy
use_specialist_agents: bool = False      # 19 specialist agents vs LegacyAgent fallback
use_dag_execution: bool = False          # Parallel DAG execution vs sequential
use_procedural_memory: bool = False      # KB procedure learning + lookup
use_graph_agent: bool = False            # Graph Agent — vertex/edge DAG execution (overrides all above)
```

| Flag | Default | Effect when True | Effect when False |
|------|---------|-----------------|-------------------|
| `use_graph_agent` | `False` | `run_orchestration` uses Graph Agent (LangGraph-based vertex/edge DAG with responsibility-typed vertices) | Falls through to delegation/legacy graph |
| `use_delegation_graph` | `False` | `get_orchestrator_graph()` returns 7-node delegation graph | Returns legacy 14-node graph |
| `use_specialist_agents` | `False` | `plan_delegations` selects from 19 registered agents | Routes everything to `LegacyAgent` |
| `use_dag_execution` | `False` | `execute_delegation` uses `DAGExecutor` (parallel) | Sequential execution only |
| `use_procedural_memory` | `False` | `plan_delegations` looks up learned procedures in KB | No procedure lookup |

**Priority:** `use_graph_agent` is checked first — if True, delegation_graph and legacy graph are never used.

### 25.1b Centralized LLM & handler settings

All handler constants are in `app/config.py` Settings class (env prefix `ORCHESTRATOR_`):

```python
# LLM token budgets
default_output_tokens: int = 4096        # Output token reserve for tier estimation
gpu_vram_token_boundary: int = 40_000    # P40 VRAM limit — above this spills to CPU

# Handler iterations
chat_max_iterations: int = 6
chat_max_iterations_long: int = 3
background_max_iterations: int = 15
respond_max_iterations: int = 8

# Token estimation
token_estimate_ratio: int = 4            # Chars-per-token (rough, cs/en)
```

All handlers (foreground, background, respond, plan, synthesize) use:
- `estimate_tokens()` from `app.config` — single implementation
- `settings.default_output_tokens` — no hardcoded `4096`
- `settings.gpu_vram_token_boundary` — no hardcoded `40_000`
- `clamp_tier()` from `app.llm.provider` — unified tier clamping
- `extract_tool_calls()` from `app.tools.ollama_parsing` — unified Ollama workaround

### 25.2 Backward compatibility guarantees

1. **API endpointy identické** — Kotlin server nepotřebuje žádné změny
   - `POST /orchestrate/stream` — unchanged
   - `POST /approve/{thread_id}` — unchanged
   - `GET /status/{thread_id}` — unchanged
   - `GET /health` — unchanged
2. **Legacy graf zachován** — `build_orchestrator_graph()` se NEMAŽE. Zůstává jako default.
3. **Feature flags defaultují na False** — Nový systém je opt-in
4. **MongoDBSaver checkpointer sdílený** — Oba grafy používají stejný checkpointer
5. **Backward compatible progress** — Nové optional fieldy v progress reportech:

```python
# Existing fields (unchanged):
taskId, clientId, node, message, percent,
goalIndex, totalGoals, stepIndex, totalSteps

# New optional fields (Kotlin ignores if not supported):
delegationId: str | None          # ID of current delegation
delegationAgent: str | None       # Name of agent executing
delegationDepth: int | None       # Recursion depth (0-4)
thinkingAbout: str | None         # What orchestrator is considering (for "thinking" UI)
```

### 25.3 Rollback procedure

Pokud nový systém selže:
1. Nastavit `use_delegation_graph = False` v config/env
2. Restart orchestrator podu
3. Všechny nové orchestrace půjdou přes legacy graf
4. In-flight orchestrace (v checkpointu) se zastaví — safety-net polling je resetuje

### 25.4 Přechod na nový systém

Doporučená sekvence zapínání:
1. `use_delegation_graph = True` + `use_specialist_agents = False` → nový graf s LegacyAgent (test graph flow)
2. `use_specialist_agents = True` + `use_dag_execution = False` → specialist agenti, sekvenční (test agents)
3. `use_dag_execution = True` → full parallel execution (test performance)
4. `use_procedural_memory = True` → learning enabled (test long-term)

---

## 26. Kotlin integrace — kompletní API

### 26.1 PythonOrchestratorClient (Kotlin → Python)

```kotlin
class PythonOrchestratorClient(baseUrl: String) {
    // Orchestrace
    suspend fun orchestrate(request): OrchestrateResponseDto           // blocking (legacy)
    suspend fun orchestrateStream(request): StreamStartResponseDto?    // fire-and-forget (primary)
    suspend fun approve(threadId, approved, reason)                     // fire-and-forget resume
    suspend fun cancelOrchestration(threadId)
    suspend fun resume(threadId): OrchestrateResponseDto               // blocking resume (legacy)
    suspend fun getStatus(threadId): Map<String, String>               // safety-net polling

    // Health
    suspend fun isHealthy(): Boolean
    suspend fun isBusy(): Boolean
    fun streamUrl(threadId): String

    // Chat compression
    suspend fun compressChat(request): CompressChatResponseDto

    // Correction agent
    suspend fun submitCorrection(request)
    suspend fun correctTranscript(request)
    suspend fun listCorrections(request)
    suspend fun deleteCorrection(sourceUrn)
    suspend fun correctWithInstruction(request)
    suspend fun correctTargeted(request)
    suspend fun answerCorrectionQuestions(request)
}
```

### 26.2 Internal endpoints (Python → Kotlin push)

**KtorRpcServer** registruje:

```
POST /internal/orchestrator-progress
  Body: { taskId, node, message, percent, goalIndex, totalGoals, stepIndex, totalSteps, clientId }
  → Update stateChangedAt on TaskDocument (timestamp-based stuck detection)
  → Emit OrchestratorTaskProgress to UI

POST /internal/orchestrator-status
  Body: { taskId, status, summary, error, interruptAction, interruptDescription, branch, artifacts, threadId }
  → OrchestratorStatusHandler.handleStatusChange(...)
  → Emit OrchestratorTaskStatusChange to UI

POST /internal/correction-progress
  Body: { meetingId, phase, chunkIndex, totalChunks, segmentsProcessed, totalSegments, message }
  → Update stateChangedAt on meeting document (timestamp-based stuck detection)
  → Emit notification to UI

POST /internal/memory-map-changed
  Body: (empty)
  → NotificationRpcImpl.emitMemoryMapChanged() [broadcast to ALL connected clients]
  → UI re-fetches Paměťová mapa via getGraph("master") [500ms debounce]
  Triggered by: vertex status changes (PENDING→RUNNING→COMPLETED), TASK_REF linking
  Only fires for MEMORY_MAP graphs (not TASK_SUBGRAPH thinking maps)
```

### 26.3 AgentOrchestratorService

```kotlin
class AgentOrchestratorService(
    pythonOrchestratorClient, preferenceService, czechKeyboardNormalizer,
    taskService, taskRepository, environmentService,
    clientService, projectService, chatHistoryService,
) {
    // Entry points
    suspend fun enqueueChatTask(...)     // Create FOREGROUND task
    suspend fun handle(text, ctx, ...)   // Direct handle (legacy)
    suspend fun run(task, userInput, ...) // Main router:
        // Path 1: orchestratorThreadId != null → resumePythonOrchestrator()
        // Path 2: new → dispatchToPythonOrchestrator()
        // Path 3: unavailable → error response

    private suspend fun dispatchToPythonOrchestrator(...):
        // 1. Guard: PROCESSING count == 0
        // 2. isHealthy()
        // 3. Load rules, environment, names, chat history
        // 4. Build OrchestrateRequestDto
        // 5. POST /orchestrate/stream → get thread_id
        // 6. Save PROCESSING + orchestratorThreadId

    private suspend fun resumePythonOrchestrator(...):
        // Distinguish clarification vs approval
        // POST /approve/{thread_id}
        // Save PROCESSING
}
```

### 26.4 OrchestratorStatusHandler

```kotlin
class OrchestratorStatusHandler(
    taskRepository, taskService, userTaskService,
    agentOrchestratorRpc, chatMessageRepository, chatHistoryService,
) {
    suspend fun handleStatusChange(taskId, status, summary, error,
        interruptAction, interruptDescription, branch, artifacts):

        when (status):
            "running"     → no-op (timestamp-based stuck detection handles liveness)
            "interrupted" → handleInterrupted(task, action, description)
            "done"        → handleDone(task, summary)
            "error"       → handleError(task, error)

    private suspend fun handleInterrupted(task, action, description):
        // FOREGROUND: emit to chat stream + save ASSISTANT message + DONE
        // BACKGROUND: create USER_TASK notification

    private suspend fun handleDone(task, summary):
        // FOREGROUND: emit response + save ASSISTANT message
        // Check inline messages (arrived during orchestration)
        //   → if yes: re-queue to QUEUED
        //   → if no: DONE (terminal)
        // BACKGROUND: delete task after completion
        // Async: chatHistoryService.compressIfNeeded()

    private suspend fun handleError(task, error):
        // Emit error + save error message
        // Create USER_TASK + set ERROR state
}
```

### 26.5 BackgroundEngine (relevantní loops)

```kotlin
// Execution loop (orchestrator): three-tier priority — FOREGROUND > BACKGROUND > IDLE
private suspend fun runExecutionLoop():
    while (true):
        // 0. Preemption: FG preempts BG+IDLE, BG preempts IDLE
        checkPreemption(runningTask)
        // 1. FOREGROUND (chat) — highest priority
        task = getNextForegroundTask()
        // 2. BACKGROUND (user-scheduled) — if no FG and no active chat
        if task == null: task = getNextBackgroundTask()
        // 3. IDLE (system idle work) — only when truly idle
        if task == null: task = getNextIdleTask()
        if task != null:
            agentOrchestratorService.run(task, task.content)

// Orchestrator result loop: safety-net for PROCESSING
private suspend fun runOrchestratorResultLoop():
    while (true):
        delay(60_000)                  // 60s interval
        tasks = findAll(PROCESSING)
        for task in tasks:
            checkOrchestratorTaskStatus(task)
            // → timestamp stuck check → Python poll → status handler
```

---

## 27. Konfigurace a deployment

### 27.1 Python config (`app/config.py`)

```python
class Settings:
    host = "0.0.0.0"
    port = 8090
    mongodb_url = env("MONGODB_URL", "mongodb://localhost:27017")
    kotlin_server_url = env("KOTLIN_SERVER_URL", "http://jervis-server:5500")
    knowledgebase_url = env("KNOWLEDGEBASE_URL", "http://jervis-knowledgebase:8080")
    ollama_url = env("OLLAMA_URL", "http://192.168.100.117:11434")
    k8s_namespace = env("K8S_NAMESPACE", "jervis")
    data_root = env("DATA_ROOT", "/opt/jervis/data")
    container_registry = env("CONTAINER_REGISTRY", "registry.damek-soft.eu/jandamek")

    # LLM models
    default_local_model = "qwen3-coder-tool:30b"
    default_cloud_model = "claude-sonnet-4-5-20250929"
    default_premium_model = "claude-opus-4-6"
    default_openai_model = "gpt-4o"
    default_large_context_model = "gemini-2.5-pro"

    # API keys (optional)
    anthropic_api_key = env("ANTHROPIC_API_KEY", None)
    openai_api_key = env("OPENAI_API_KEY", None)
    google_api_key = env("GOOGLE_API_KEY", None)

    # Agent timeouts
    agent_timeouts = {
        "claude": 1800, "kilo": 1800,
    }
    job_ttl_seconds = 300

    # Feature flags (multi-agent delegation system)
    use_delegation_graph: bool = False       # 7-node delegation vs 14-node legacy
    use_specialist_agents: bool = False      # 19 agents vs LegacyAgent
    use_dag_execution: bool = False          # Parallel DAG execution
    use_procedural_memory: bool = False      # KB procedure learning

    # Delegation settings
    max_delegation_depth: int = 4
    delegation_timeout: int = 300

    # Token budgets per depth
    token_budget_depth_0: int = 48000
    token_budget_depth_1: int = 16000
    token_budget_depth_2: int = 8000
    token_budget_depth_3: int = 4000

    # Context budgeting (ChatContextAssembler)
    total_context_window: int = 32_768    # Model context window
    system_prompt_reserve: int = 2_000    # Tokens for system prompt + tools
    response_reserve: int = 4_000         # Tokens for LLM response
    recent_message_count: int = 100       # Max verbatim messages (budget limits actual inclusion)
    max_summary_blocks: int = 15          # Max compressed summaries
    compress_threshold: int = 20          # Compress at >=N unsummarized msgs
    compress_max_retries: int = 2         # Compression retry limit
    max_tool_result_in_msg: int = 2_000   # Max chars per tool result
    token_estimate_ratio: int = 4         # Chars-per-token ratio

    # Chat handler constants
    chat_max_iterations: int = 6          # Max agentic loop iters (normal)
    chat_max_iterations_long: int = 3     # Max iters for long messages
    decompose_threshold: int = 8000       # Chars to trigger decomposition
    summarize_threshold: int = 16000      # Chars to trigger summarization
    subtopic_max_iterations: int = 3      # Max iters per sub-topic
    max_subtopics: int = 5               # Max sub-topics from decomposition

    # Background handler constants
    background_max_iterations: int = 15   # Max agentic loop iters for bg

    # Streaming
    stream_chunk_size: int = 40           # Chars per fake-streaming chunk

    # Guidelines cache
    guidelines_cache_ttl: int = 300       # TTL seconds (5 min)

    # Session memory
    session_memory_ttl_days: int = 7
    session_memory_max_entries: int = 50
```

> **All settings use env prefix `ORCHESTRATOR_`**, e.g. `ORCHESTRATOR_TOTAL_CONTEXT_WINDOW=65536`.

### 27.2 K8s Deployment

```yaml
# k8s/app_orchestrator.yaml
Deployment: jervis-orchestrator
  replicas: 1
  serviceAccountName: jervis-orchestrator  # RBAC pro K8s Jobs
  image: registry.damek-soft.eu/jandamek/jervis-orchestrator:latest
  port: 8090
  resources:
    requests: 256Mi, 250m
    limits: 1Gi, 2000m
  volumeMounts:
    - /opt/jervis/data (PVC: jervis-data-pvc)
  probes:
    liveness: GET /health (30s interval, 10s timeout)
    readiness: GET /health (15s interval, 10s timeout)

Service: jervis-orchestrator
  port: 8090 → 8090
```

### 27.3 RBAC

```yaml
# k8s/orchestrator-rbac.yaml
ServiceAccount: jervis-orchestrator
Role: jervis-orchestrator-role
  - resources: [jobs, pods, pods/log]
    verbs: [get, list, watch, create, delete]
RoleBinding: jervis-orchestrator → jervis-orchestrator-role
```

### 27.4 Build & Deploy

```bash
k8s/build_orchestrator.sh
# → Docker build --platform linux/amd64
# → Docker push registry.damek-soft.eu/jandamek/jervis-orchestrator:v{N}
# → kubectl apply -f k8s/orchestrator-rbac.yaml
# → kubectl apply -f k8s/app_orchestrator.yaml
# → kubectl set image deployment/jervis-orchestrator ...
```

---

## 28. Datové modely — kompletní referenční seznam

### 28.1 Python modely (`app/models.py`)

```python
# === Legacy Enums ===
AgentType        # claude, kilo
Complexity       # simple, medium, complex, critical
ModelTier        # local_fast/standard/large, cloud_openrouter/reasoning/coding/premium/large_context
TaskCategory     # advice, single_task, epic, generative
TaskAction       # respond, code, tracker_ops, mixed
StepType         # respond, code, tracker
RiskLevel        # LOW, MEDIUM, HIGH, CRITICAL

# === Legacy Core models ===
CodingTask       # id, client_id, project_id, workspace_path, query, agent_preference
Goal             # id, title, description, complexity, dependencies
CodingStep       # index, instructions, step_type, agent_type, files, tracker_operations
StepResult       # step_index, success, summary, agent_type, changed_files
Evaluation       # acceptable, checks, diff
GoalSummary      # goal_id, title, summary, changed_files, key_decisions

# Clarification
ClarificationQuestion  # id, question, options, required

# Evidence
EvidencePack     # kb_results, tracker_artifacts, chat_history_summary, external_refs, facts, unknowns

# Chat history
ChatHistoryMessage   # role, content, timestamp, sequence
ChatSummaryBlock     # sequence_range, summary, key_decisions, topics, is_checkpoint, checkpoint_reason
ChatHistoryPayload   # recent_messages, summary_blocks, total_message_count

# Approval
ApprovalRequest  # action_type, description, details, risk_level, reversible
ApprovalResponse # approved, modification, reason

# API
OrchestrateRequest   # task_id, client_id, ..., rules, environment, chat_history
OrchestrateResponse  # task_id, success, summary, branch, artifacts, step_results, thread_id
ProjectRules         # branch_naming, commit_prefix, require_*, auto_*, forbidden_files

# === NEW: Delegation system models ===

# Enums
DomainType           # code, devops, project_management, communication, legal, financial,
                     # administrative, personal, security, research, learning
DelegationStatus     # pending, running, completed, failed, interrupted

# Delegation protocol
DelegationMessage    # delegation_id, parent_delegation_id, depth, agent_name, task_summary,
                     # context, constraints, expected_output, response_language,
                     # client_id, project_id, group_id
AgentOutput          # delegation_id, agent_name, success, result, structured_data,
                     # artifacts, changed_files, sub_delegations, confidence, needs_verification
DelegationState      # delegation_id, agent_name, status, result_summary,
                     # sub_delegation_ids, checkpoint_data
ExecutionPlan        # delegations, parallel_groups, domain

# Agent registry
AgentCapability      # name, description, domains, can_sub_delegate, max_depth, tool_names

# Memory
SessionEntry         # timestamp, source, summary, details, task_id
SessionMemoryPayload # client_id, project_id, entries
ProcedureStep        # agent, action, parameters
ProcedureNode        # trigger_pattern, procedure_steps, success_rate, last_used,
                     # usage_count, source, client_id

# Monitoring
DelegationMetrics    # delegation_id, agent_name, start_time, end_time,
                     # token_count, llm_calls, sub_delegation_count, success
```

### 28.2 Kotlin DTOs (`PythonOrchestratorClient.kt`)

```kotlin
// Orchestrace
OrchestrateRequestDto      // task_id, client_id, ..., chat_history
OrchestrateResponseDto     // task_id, success, summary, isInterrupted
StreamStartResponseDto     // thread_id, stream_url
ApprovalResponseDto        // approved, modification, reason
ProjectRulesDto            // branch_naming, commit_prefix, require_*, auto_*
StepResultDto              // step_index, success, summary, agent_type, changed_files

// Chat history
ChatHistoryPayloadDto      // recent_messages, summary_blocks, total_message_count
ChatHistoryMessageDto      // role, content, timestamp, sequence
ChatSummaryBlockDto        // sequence_range, summary, key_decisions, topics, is_checkpoint

// Chat compression
CompressChatRequestDto     // messages, previous_summary, client_id, task_id
CompressChatResponseDto    // summary, key_decisions, topics, is_checkpoint, checkpoint_reason

// Correction agent
CorrectionSubmitRequestDto, CorrectionSubmitResultDto
CorrectionRequestDto, CorrectionResultDto, CorrectionSegmentDto
CorrectionQuestionPythonDto, CorrectionAnswerRequestDto, CorrectionAnswerItemDto
CorrectionListRequestDto, CorrectionListResultDto, CorrectionChunkDto
CorrectionInstructRequestDto, CorrectionInstructResultDto
CorrectionTargetedRequestDto, CorrectionDeleteRequestDto
```

### 28.3 MongoDB kolekce (orchestrátor-related)

| Kolekce | Účel | Indexy |
|---------|------|--------|
| `jervis_checkpoints.*` | LangGraph graph state | thread_id |
| `orchestrator_context` | Hierarchické context store | (task_id, scope, scope_key), TTL 30d |
| `orchestrator_locks` | Distributed lock | _id = "orchestration_slot" |
| `chat_messages` | Jednotlivé zprávy | (taskId, sequence), taskId, correlationId |
| `chat_summaries` | Komprimované souhrny | (taskId, sequenceEnd), taskId |
| `tasks` | TaskDocument lifecycle | state, clientId, projectId, type |
| `session_memory` | **NEW:** Per-client/project session memory | (client_id, project_id), TTL 7d |
| `delegation_metrics` | **NEW:** Per-agent delegation metrics | (delegation_id), (agent_name, start_time) |

---

## 29. Souborová mapa

### Python orchestrátor

```
backend/service-orchestrator/
├── app/
│   ├── main.py                          # FastAPI app, endpoints, concurrency, crash handler (atexit + SIGTERM)
│   ├── config.py                        # Environment-based configuration (+feature flags)
│   ├── agent_task_watcher.py            # Background watcher for async K8s Job monitoring
│   ├── models.py                        # Pydantic models (ALL data structures + delegation models)
│   ├── chat/
│   │   ├── __init__.py
│   │   ├── context.py                   # Chat context assembler (MongoDB read/write)
│   │   ├── router.py                    # FastAPI router: /chat, /orchestrate/v2, /internal/*
│   │   ├── models.py                    # NEW v6: ChatRequest, ChatStreamEvent, ChatEventType
│   │   ├── system_prompt.py             # NEW v6: Runtime context fetch + system prompt builder
│   │   ├── tools.py                     # NEW v6: 8 chat-specific tool definitions
│   │   └── handler.py                   # NEW v6: Foreground SSE agentic loop (15 iterations)
│   ├── background/
│   │   ├── __init__.py                  # NEW v6
│   │   ├── escalation.py               # NEW v6: EscalationTracker, needs_escalation()
│   │   ├── tools.py                     # NEW v6: Background tool subset (~30 tools)
│   │   └── handler.py                   # NEW v6: Simplified background agentic loop
│   ├── graph/
│   │   ├── orchestrator.py              # LangGraph StateGraph, state, routing, streaming
│   │   │                                #   build_orchestrator_graph() — legacy 14-node
│   │   │                                #   build_delegation_graph()  — NEW 7-node delegation
│   │   │                                #   get_orchestrator_graph()  — feature flag switch
│   │   ├── dag_executor.py              # NEW: DAG parallel execution engine
│   │   └── nodes/
│   │       ├── __init__.py              # Re-exports all nodes
│   │       ├── _helpers.py              # LLM wrapper, JSON parsing, cloud escalation
│   │       ├── intake.py                # Classification, clarification (+language detection)
│   │       ├── evidence.py              # KB + tracker artifact fetch
│   │       ├── respond.py               # Direct answers (ADVICE + SINGLE_TASK/respond)
│   │       ├── plan.py                  # SINGLE_TASK planning (respond/code/tracker/mixed)
│   │       ├── execute.py               # Step execution (respond/code/tracker dispatch)
│   │       ├── evaluate.py              # Result evaluation, routing, step/goal advancement
│   │       ├── git_ops.py               # Git commit/push with approval gates
│   │       ├── finalize.py              # Final report generation
│   │       ├── coding.py                # Decompose, select_goal, plan_steps
│   │       ├── epic.py                  # EPIC planning + wave execution (Phase 3)
│   │       ├── design.py                # GENERATIVE design (Phase 3)
│   │       ├── plan_delegations.py      # NEW: LLM-driven agent selection
│   │       ├── execute_delegation.py    # NEW: Dispatch + monitoring via DAGExecutor
│   │       └── synthesize.py            # NEW: Merge agent results + RAG cross-check
│   ├── llm/
│   │   ├── provider.py                  # LLM abstraction (litellm), streaming, token-arrival liveness
│   │   └── (gpu_router.py removed — auto-reservation handled by router)
│   ├── agents/
│   │   ├── __init__.py
│   │   ├── base.py                      # NEW: BaseAgent abstract class, agentic loop
│   │   ├── registry.py                  # NEW: AgentRegistry singleton
│   │   ├── legacy_agent.py              # NEW: Wrapper of existing 14-node logic (fallback)
│   │   ├── job_runner.py                # K8s Job creation, async dispatch, status polling, result reading
│   │   ├── workspace_manager.py         # .jervis/ files, CLAUDE.md, MCP config
│   │   └── specialists/                 # NEW: 19 specialist agents
│   │       ├── __init__.py
│   │       ├── code_agent.py            # CodingAgent — K8s Job delegation
│   │       ├── git_agent.py             # GitAgent — git operations
│   │       ├── review_agent.py          # CodeReviewAgent — code review
│   │       ├── test_agent.py            # TestAgent — test generation/execution
│   │       ├── research_agent.py        # ResearchAgent — KB/web/code search
│   │       ├── tracker_agent.py         # IssueTrackerAgent — issue CRUD
│   │       ├── wiki_agent.py            # WikiAgent — wiki page CRUD
│   │       ├── documentation_agent.py   # DocumentationAgent — docs generation
│   │       ├── devops_agent.py          # DevOpsAgent — CI/CD, K8s
│   │       ├── project_management_agent.py  # ProjectManagementAgent — sprint/epic
│   │       ├── security_agent.py        # SecurityAgent — security analysis
│   │       ├── communication_agent.py   # CommunicationAgent — messaging hub
│   │       ├── email_agent.py           # EmailAgent — email operations
│   │       ├── calendar_agent.py        # CalendarAgent — scheduling
│   │       ├── administrative_agent.py  # AdministrativeAgent — logistics
│   │       ├── legal_agent.py           # LegalAgent — contracts, compliance
│   │       ├── financial_agent.py       # FinancialAgent — budget, invoices
│   │       ├── personal_agent.py        # PersonalAgent — personal assistant
│   │       └── learning_agent.py        # LearningAgent — tutorials, evaluations
│   ├── context/
│   │   ├── context_store.py             # MongoDB hierarchical context store (+scope=delegation)
│   │   ├── context_assembler.py         # Per-node LLM context assembly (+token budgets)
│   │   ├── distributed_lock.py          # MongoDB distributed lock
│   │   ├── session_memory.py            # NEW: Per-client/project session memory (7d TTL)
│   │   ├── procedural_memory.py         # NEW: KB procedure lookup/save
│   │   ├── summarizer.py               # NEW: AgentOutput summarization
│   │   └── retention_policy.py          # NEW: What to save vs discard
│   ├── kb/
│   │   └── prefetch.py                  # KB context pre-fetch for agents and orchestrator
│   ├── tools/
│   │   ├── definitions.py               # Tool schemas (+per-agent tool sets)
│   │   ├── executor.py                  # NEW: Tool execution engine for agents
│   │   └── kotlin_client.py             # Push client (progress, status, streaming tokens → Kotlin)
│   ├── monitoring/
│   │   └── delegation_metrics.py        # NEW: Per-agent delegation metrics
│   └── whisper/
│       └── correction_agent.py          # Transcript correction (KB + Ollama)
├── Dockerfile
└── requirements.txt
```

### Kotlin server (orchestrátor-related)

```
backend/server/src/main/kotlin/com/jervis/
├── configuration/
│   └── PythonOrchestratorClient.kt      # REST client + all DTOs
├── entity/
│   ├── ChatMessageDocument.kt           # Individual chat messages
│   └── ChatSummaryDocument.kt           # Compressed chat summary blocks
├── repository/
│   ├── ChatMessageRepository.kt         # Message CRUD + search
│   └── ChatSummaryRepository.kt         # Summary CRUD
├── rpc/
│   ├── KtorRpcServer.kt                 # /internal/ push endpoints
│   └── AgentOrchestratorRpcImpl.kt      # Chat RPC + emit helpers
├── service/
│   ├── agent/coordinator/
│   │   ├── AgentOrchestratorService.kt  # Dispatch + resume logic
│   │   ├── OrchestratorStatusHandler.kt # State transitions (push + poll)
│   │   # (OrchestratorHeartbeatTracker removed — replaced by timestamp-based stuck detection via DB fields)
│   ├── background/
│   │   └── BackgroundEngine.kt          # 4 loops: indexing, execution, scheduler, result
│   └── chat/
│       ├── ChatMessageService.kt        # Message CRUD service
│       └── ChatHistoryService.kt        # History payload + async compression
```

---

## Common Bugs and Fixes

### Respond Node Tool-Use Loop (Fixed 2026-02-11)

**Symptom:** Agent executes 1 tool call, then immediately logs "max iterations (5) reached" and returns answer.

**Root Cause:** Incorrect indentation in `respond.py`. The "max iterations reached" block (lines 180-192) was INSIDE the while loop instead of OUTSIDE. This caused it to execute after EVERY tool execution instead of only when the loop limit was reached.

**Incorrect Code:**
```python
while iteration < _MAX_TOOL_ITERATIONS:
    iteration += 1
    response = await llm_with_cloud_fallback(...)
    
    if not tool_calls or finish_reason == "stop":
        return {"final_result": message.content}
    
    # Execute tools
    for tool_call in tool_calls:
        result = execute_tool(...)
        messages.append({"role": "tool", ...})
    
    # ❌ WRONG - This is INSIDE the while loop!
    logger.warning("Respond: max iterations reached")
    final_response = await llm_with_cloud_fallback(...)
    return {"final_result": answer}
```

**Fixed Code:**
```python
while iteration < _MAX_TOOL_ITERATIONS:
    iteration += 1
    response = await llm_with_cloud_fallback(...)
    
    if not tool_calls or finish_reason == "stop":
        return {"final_result": message.content}
    
    # Execute tools
    for tool_call in tool_calls:
        result = execute_tool(...)
        messages.append({"role": "tool", ...})
    # Continue loop - will call LLM again with tool results

# ✅ CORRECT - This is OUTSIDE the while loop
logger.warning("Respond: max iterations reached")
final_response = await llm_with_cloud_fallback(...)
return {"final_result": answer}
```

**Fix:** De-indent the "max iterations" block by one level (4 spaces) so it executes only AFTER the while loop exits.

**Commit:** `6f257acd` — "fix: chat UI improvements and respond loop bug"

### Empty Response from LLM with Tool Calls (Fixed 2026-02-11)

**Symptom:** Error "Empty response from local model" when LLM makes a tool call.

**Root Cause:** Validation in `_helpers.py` line 85-87 only checked `message.content`, which is None/empty when the LLM calls a tool. The actual response is in `message.tool_calls`, not `content`.

**Incorrect Code:**
```python
content = response.choices[0].message.content
if not content or not content.strip():
    raise ValueError("Empty response from local model")  # ❌ WRONG
```

**Fixed Code:**
```python
message = response.choices[0].message
content = message.content
tool_calls = getattr(message, "tool_calls", None)

# Valid response = has content OR has tool_calls
if (not content or not content.strip()) and not tool_calls:
    raise ValueError("Empty response from local model")  # ✅ CORRECT
```

**Fix:** Accept either `content` OR `tool_calls` as a valid response. Only raise error if BOTH are missing.

**Commit:** `60205503` — "fix: accept tool_calls as valid LLM response"

---

## Architecture Decisions

### PVC for Orchestrator: NO (Decided 2026-02-11)

**Question:** Should orchestrator have PVC mount for filesystem tools access?

**Decision:** **NO** — Orchestrator should remain stateless and use knowledge APIs instead.

**Reasoning:**

1. **Separation of Concerns:**
   - Orchestrator = BRAIN (decides what to do)
   - Coding Agents = HANDS (execute tasks, access code)
   - Filesystem access is agent responsibility, not orchestrator

2. **Scalability:**
   - Stateless orchestrator can scale horizontally
   - PVC would tie orchestrator to specific nodes
   - Multiple orchestrator pods would need shared PVC (complexity)

3. **Security:**
   - Orchestrator with filesystem access = larger attack surface
   - Agents already have controlled workspace access
   - Principle of least privilege

4. **Existing Solutions:**
   - **Knowledge Base:** Indexed, searchable project knowledge (fast)
   - **web_search:** External information via SearXNG
   - **kb_search:** Internal project-specific knowledge
   - **Coding agents:** Direct code access when needed

**Alternative Approach:**
If orchestrator needs code exploration:
1. Use `kb_search` tool for indexed knowledge
2. Dispatch to coding agent (Claude/Kilo) for deeper exploration
3. Agent can use filesystem tools and report back

**Conclusion:** Keep orchestrator stateless. Use tools (web_search, kb_search) + agent dispatch for information gathering.

---

## Memory Agent

> **Spec:** See `docs/orchestrator-memory-spec.md` for full architecture.

### Overview

The Memory Agent provides structured working memory between turns, context switching when users change topics, and immediate availability of recently stored data. It adds two graph nodes (`memory_load`, `memory_flush`) and three LLM tools to every orchestration.

### New Graph Nodes

**`memory_load`** — runs between `intake` and `evidence_pack`:
- Creates/restores `MemoryAgent` from serialized state (or cold-starts from KB)
- Detects context switches via LLM classification: `CONTINUE`, `SWITCH`, `AD_HOC`, `NEW_AFFAIR`
- Parks current affair and activates target on SWITCH
- Composes token-budgeted context string for downstream nodes
- Returns: `memory_agent` (dict), `memory_context` (str), `context_switch_type` (str)

**`memory_flush`** — runs between `respond`/`synthesize` and `finalize`:
- Appends current query + response to active affair messages (max 20)
- Drains write buffer (pending KB writes)
- Returns: updated `memory_agent` (dict)

### New State Fields

| Field | Type | Description |
|-------|------|-------------|
| `memory_agent` | `dict \| None` | Serialized MemoryAgent (affairs, session, LQM reference) |
| `memory_context` | `str \| None` | Composed context string injected into respond node |
| `context_switch_type` | `str \| None` | Last detected switch type |

### New LLM Tools (respond node)

| Tool | Description |
|------|-------------|
| `memory_store` | Store facts/decisions/orders to active affair + KB write buffer |
| `memory_recall` | Search LQM write buffer + affairs + KB with scope (current/all/kb_only) |
| `list_affairs` | List active + parked affairs with details |

### Key Components (`app/memory/`)

- **`models.py`** — `Affair`, `AffairStatus`, `SessionContext`, `ContextSwitchResult`, `PendingWrite`, `WritePriority`
- **`content_reducer.py`** — **Central content reduction module.** `reduce_for_prompt()` (async LLM reduction), `reduce_messages_for_prompt()` (batch message fitting), `trim_for_display()` (display-only truncation). Replaces all hard-coded `[:N]` truncation. Supports cloud escalation via `state` parameter (auto-Gemini for content exceeding current model's context)
- **`lqm.py`** — Local Quick Memory: 3-layer RAM cache (hot affairs dict, async write buffer queue, LRU warm cache with TTL)
- **`context_switch.py`** — LLM-based context switch detection (Czech prompt, confidence threshold 0.7). Uses `reduce_for_prompt` for summary/message in classification prompt
- **`affairs.py`** — Affair lifecycle: create, park (with LLM summarization), resume, resolve, load from KB. Uses `reduce_messages_for_prompt` for budget-aware message building
- **`composer.py`** — Token-budgeted context composition (40% active affair, 10% parked, 15% user context). **Async** — uses LLM reduction for large summaries/facts/messages instead of hard truncation
- **`agent.py`** — `MemoryAgent` facade; process-global LQM singleton, per-orchestration agent instances
- **`consolidation.py`** — Topic-aware memory consolidation. Uses `reduce_for_prompt` for combined summaries and `reduce_messages_for_prompt` for affair messages

### Graph Flow (when enabled)

```
intake → memory_load → evidence_pack → ... → respond → memory_flush → finalize
```

For delegation graph:
```
intake → memory_load → evidence_pack → plan_delegations → execute_delegation → synthesize → memory_flush → finalize
```

### Outcome Ingestion

When Memory Agent is active, ADVICE tasks with affair context become significant for KB ingestion. The extraction prompt is enriched with active affair title, key facts, and parked affair titles.

---

## TODO / Future Improvements

### Short-Term Conversation Memory (Priority: HIGH) — PARTIALLY ADDRESSED

**Problem:** Agent doesn't retain context from previous iterations in the same conversation.

**Status:** Partially addressed by Session Memory (section 24.2) and Chat History (section 16). Session Memory provides per-client/project 7-day cache of recent decisions. Chat History provides verbatim last 20 messages + rolling summaries.

**Remaining gap:** Session Memory is cross-task (shared across orchestrations for same client/project). Within a single orchestration, agent nodes still rely on chat_history for conversational context. The agentic loop within specialist agents (section 23.4) does not yet carry forward intermediate LLM conversation turns between tool calls.

### Multi-Agent System Rollout (Priority: HIGH)

**Status:** Foundation in progress (see sections 18-25). Key implementation tasks remaining:

1. **Complete specialist agent implementations** — 6 of 19 agents have initial implementations (tracker, wiki, documentation, devops, project_management, security). Remaining 13 need implementation.
2. **base.py + registry.py** — BaseAgent and AgentRegistry need to be created (spec defined in plan).
3. **New graph nodes** — `plan_delegations.py`, `execute_delegation.py`, `synthesize.py` need implementation.
4. **DAG executor** — `dag_executor.py` needs implementation.
5. **Memory layers** — `session_memory.py`, `procedural_memory.py`, `summarizer.py`, `retention_policy.py` need implementation.
6. **Tool executor** — `tools/executor.py` for agent tool dispatch.
7. **Integration testing** — End-to-end delegation flow with feature flags.

---

## 30. Hardening (W-9 to W-23) — Robustness Improvements

> **Implemented:** 2026-02-21 | **Scope:** respond node, executor, context, provider, distributed lock

### 30.1 Overview

Systematic hardening of the Python orchestrator addressing 15 weak spots identified in the post-v5 audit. All changes are backward-compatible and use local-first approach (cloud never called unless explicitly allowed in project rules).

### 30.2 Changes by File

#### `app/tools/executor.py`
- **W-11: Tool Result Size Bound** — `MAX_TOOL_RESULT_CHARS = 8000`. All tool results are truncated via `_truncate_result()` before returning. Preserves 70% head + 20% tail with truncation marker.
- **W-22: Tool Execution Timeout** — `_TOOL_EXECUTION_TIMEOUT_S = 120`. Exported for use in respond node.

#### `app/graph/nodes/respond.py`
- **W-22: Tool Execution Timeout** — Each `execute_tool()` call wrapped in `asyncio.wait_for(timeout=120s)`. Timeout returns error string (not exception).
- **W-17: JSON Workaround Validation** — Ollama tool_call JSON parsing now validates structure: checks `tool_calls` is list, each entry is dict, has `function.name`. Invalid entries are skipped with warning.
- **W-13: Quality Escalation** — Short answer detection: if answer < 40 chars, retries once with "expand your answer" system message. `_MIN_ANSWER_CHARS = 40`, `_MAX_SHORT_RETRIES = 1`.
- **W-12: Real Token Streaming** — New `_stream_answer_realtime()` uses `llm_provider.stream_completion()` for real-time token emission. Falls back to fake chunked streaming on error.
- **W-19: User Message Save** — User query and assistant answer saved to MongoDB via `chat_context_assembler.save_message()` for chat history persistence.

#### `app/graph/nodes/_helpers.py`
- **W-14: Context Overflow Guard** — Before LLM call, validates `context_tokens` fits selected tier's `num_ctx`. If exceeded, calls `_truncate_messages_to_budget()` which removes oldest tool results first, then middle messages, while protecting system message and last 4 messages.

#### `app/chat/context.py`
- **W-20: Sequence Number Race** — `get_next_sequence()` uses atomic `findOneAndUpdate` on `chat_sequence_counters` collection instead of `count_documents + 1`.
- **W-15: Compression Error Handling** — `_compress_block()` retries `COMPRESS_MAX_RETRIES = 2` times with exponential backoff. On exhaustion, saves placeholder marker so block isn't re-attempted. `maybe_compress()` accepts `done_callback` for completion notification. Compression prompt is content-complete (no arbitrary char limit), preserves KB references (sourceUrn, correlationId, ticket IDs), and tags multi-project summaries with `[Projekt X]:` prefixes. Input messages are truncated to 2000 chars (not 500).
- **W-10: Checkpoint Message Growth** — `save_message()` truncates TOOL role messages to `MAX_TOOL_RESULT_IN_MSG = 2000` chars before MongoDB write.

#### `app/llm/provider.py`
- **W-21: REMOVED** — LLM rate limiting semaphores removed. Router manages all GPU concurrency via its request queue (priority-based dispatch, CRITICAL preemption). No artificial limits on orchestrator side.

#### `app/graph/nodes/finalize.py`
- **W-16: Background Quality Escalation** — Logs warning when background task has failed steps (quality check without LLM summary generation).

#### `app/memory/lqm.py`
- **W-18: Global Cache Race** — Added `asyncio.Lock` for affair mutations in LQM. Defensive measure for concurrent asyncio coroutines.

### 30.3 New MongoDB Collections

| Collection | Purpose | Document schema |
|-----------|---------|-----------------|
| `chat_sequence_counters` | W-20: Atomic sequence numbers | `{_id: "seq_{taskId}", counter: int}` |

### 30.4 New Constants

| Constant | Value | File | Purpose |
|----------|-------|------|---------|
| `MAX_TOOL_RESULT_CHARS` | 8000 | executor.py | W-11: Max tool result size |
| `_TOOL_EXECUTION_TIMEOUT_S` | 120 | executor.py | W-22: Per-tool timeout |
| `_MIN_ANSWER_CHARS` | 40 | respond.py | W-13: Short answer threshold |
| `_MAX_SHORT_RETRIES` | 1 | respond.py | W-13: Retry limit |
| `COMPRESS_MAX_RETRIES` | 2 | context.py | W-15: Compression retry limit |
| `MAX_TOOL_RESULT_IN_MSG` | 2000 | context.py | W-10: Stored message truncation |

### 30.5 Test Suite

Tests in `backend/service-orchestrator/tests/test_hardening.py`:
- Unit tests for truncation, context guard, JSON validation, escalation policy
- No MongoDB/LLM required — pure unit tests
- Run: `cd backend/service-orchestrator && python -m pytest tests/`

### 30.6 Cloud Safety

**CRITICAL:** Cloud models are NEVER called unless explicitly allowed in project rules (`auto_use_anthropic`, `auto_use_openai`, `auto_use_gemini`, `auto_use_openrouter`). All hardening changes (W-14 context guard, W-13 quality retry) use local Ollama only. The `llm_with_cloud_fallback` in `_helpers.py` checks `auto_providers(rules)` which derives from project settings — this is the ONLY path to cloud and it respects project configuration. OpenRouter is unrestricted — when enabled, it can handle any task type and any context size, routing via the priority model list configured in OpenRouter settings.

---

## 31. v6 Architecture — Dedicated Chat & Background Handlers

> **Implemented:** 2026-02-21 | **Scope:** New foreground chat handler, simplified background handler, model escalation, runtime context, chat-specific tools

### 31.1 Motivation

The v5 architecture routed both foreground (interactive chat) and background (autonomous tasks) through the same 14-node LangGraph orchestrator. This caused:

- **Foreground latency:** Every chat message traversed 14 nodes even for simple Q&A
- **Streaming complexity:** Progress streaming was bolted onto graph nodes
- **Inflexible tool sets:** Same tools for chat and background, no chat-specific capabilities
- **No model escalation:** Background tasks couldn't recover from model failures

v6 introduces **two dedicated handlers**, independent from LangGraph, with purpose-built tool sets and model escalation.

### 31.2 Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Kotlin Server                             │
│                                                              │
│  AgentOrchestratorRpcImpl ──┐                               │
│                              │  POST /chat                   │
│  BackgroundEngine ──────────┤  POST /orchestrate/v2          │
│                              │  POST /orchestrate (legacy)    │
└──────────────────────────────┼──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│               Python Orchestrator (FastAPI)                   │
│                                                              │
│  /chat ──────────────→ app/chat/handler.py                  │
│                          • 45 tools (37 base + 8 chat)       │
│                          • Chat SSE streaming via kotlin_client│
│                          • Runtime context system prompt      │
│                          • MongoDB save + compression         │
│                                                              │
│  /orchestrate/v2 ────→ app/background/handler.py            │
│                          • ~30 tools (background subset)      │
│                          • No streaming (status push)         │
│                          • Model tier escalation              │
│                          • Fire-and-forget via asyncio.task   │
│                                                              │
│  /orchestrate ───────→ app/graph/orchestrator.py (legacy)   │
│                          • 14-node LangGraph (kept for now)   │
└─────────────────────────────────────────────────────────────┘
```

### 31.3 Foreground Chat Handler (`app/chat/handler.py`)

**Entry point:** `POST /chat` → `handle_chat(ChatRequest) → dict`

**Flow:**

1. **Save user message** to MongoDB via `chat_context_assembler.save_message()`
2. **Fetch runtime context** — clients/projects, pending user tasks, unclassified meetings (cached 5min)
3. **Load memory context** — KB search for task-relevant context
4. **Build system prompt** — persona rules + dynamic runtime sections
5. **Assemble history** — last 20 messages + summaries from MongoDB
6. **Agentic loop** (max 15 iterations):
   - Call LLM with messages + 45 tools
   - Parse tool_calls (native or Ollama JSON workaround)
   - Execute tools (120s timeout per tool) with **effective scope** (updated by `switch_context`)
   - Detect tool loop (same tool+args 3× → force answer)
   - Append results → next iteration
   - **Scope tracking:** `effective_client_id`/`effective_project_id` initialized from request, updated by `switch_context` and tool arguments. All tool calls use effective scope, not stale request scope.
   - **Project boundary:** On `switch_context`, a `[KONTEXT PŘEPNUT]` boundary message is saved to MongoDB so summaries and context assembly can distinguish project contexts. The LLM is instructed to not carry information from previous project to the new one.
7. **Stream answer** — chunked tokens via `kotlin_client.emit_streaming_token()`
8. **Short answer retry** — if < 40 chars, retry once with "expand" instruction
9. **Save assistant message** to MongoDB
10. **Fire-and-forget compression** — `asyncio.create_task(maybe_compress())`

**Constants:**

| Constant | Value | Purpose |
|----------|-------|---------|
| `_MAX_ITERATIONS` | 15 | Max agentic loop turns |
| `_MIN_ANSWER_CHARS` | 40 | Short answer detection (W-13) |
| `_MAX_SHORT_RETRIES` | 1 | Short answer retry limit |
| `_STREAM_CHUNK_SIZE` | 12 | Token chunk size for streaming |
| `_RUNTIME_CTX_TTL` | 300.0 | Runtime context cache TTL (5 min) |

**Communication:**
- **Kotlin → Python:** `POST /chat` with `ChatRequest` JSON
- **Python → Kotlin:** `POST /internal/streaming-token` (real-time tokens)
- **Python → MongoDB:** `chat_messages` (save), `chat_summaries` (compress)
- **Python → Kotlin internal API:** `/internal/clients-projects`, `/internal/pending-user-tasks/summary`, `/internal/unclassified-meetings/count`, `/internal/tasks/*`

### 31.4 Chat Request Model (`app/chat/models.py`)

```python
class ChatRequest(BaseModel):
    task_id: str
    client_id: str
    project_id: str | None = None
    client_name: str | None = None
    project_name: str | None = None
    query: str
    workspace_path: str = ""
    processing_mode: str = "FOREGROUND"
    max_openrouter_tier: str = "NONE"  # "NONE" / "FREE" / "PAID" / "PREMIUM"
    auto_use_anthropic: bool = False
    auto_use_openai: bool = False
    auto_use_gemini: bool = False
    auto_use_openrouter: bool = False

class ChatStreamEvent(BaseModel):
    event_type: ChatEventType  # token | thinking | tool_call | tool_result | done | error
    content: str = ""
    tool_name: str | None = None
    tool_args: dict | None = None
    tool_call_id: str | None = None
    metadata: dict | None = None
```

### 31.5 Runtime System Prompt (`app/chat/system_prompt.py`)

The system prompt is assembled dynamically at each chat turn:

```
┌─────────────────────────────────────────┐
│ Static persona rules (Czech language)    │
│  • Role, behavior, output format         │
│  • Tool usage instructions               │
│  • Safety constraints                    │
├─────────────────────────────────────────┤
│ Dynamic: Available clients & projects    │  ← /internal/clients-projects
├─────────────────────────────────────────┤
│ Dynamic: Pending user tasks              │  ← /internal/pending-user-tasks/summary
├─────────────────────────────────────────┤
│ Dynamic: Unclassified meetings count     │  ← /internal/unclassified-meetings/count
├─────────────────────────────────────────┤
│ Dynamic: User context from request       │  ← ChatRequest fields
├─────────────────────────────────────────┤
│ Dynamic: Memory/KB context               │  ← kb_search results
└─────────────────────────────────────────┘
```

`fetch_runtime_context()` calls Kotlin internal API endpoints with graceful degradation — if any endpoint fails, that section is omitted (not a fatal error). Results are cached for 5 minutes.

### 31.6 Chat-Specific Tools (`app/chat/tools.py`)

8 new tools available ONLY in foreground chat (not in background):

| Tool | Description | Implementation |
|------|-------------|----------------|
| `create_background_task` | Create a new background task for autonomous processing | POST /internal/tasks/create |
| `search_tasks` | Search existing tasks by query | POST /internal/tasks/search |
| `get_task_status` | Get status of a specific task | GET /internal/tasks/{id} |
| `list_recent_tasks` | List recent tasks for current client | GET /internal/tasks/recent |
| `respond_to_user_task` | Respond to a pending user-review task | POST /internal/tasks/{id}/respond |
| `dispatch_coding_agent` | Dispatch K8s coding agent (Claude/Kilo) | K8s Job via job_runner |
| `classify_meeting` | Classify an unclassified meeting | POST /internal/meetings/{id}/classify |
| `list_unclassified_meetings` | List meetings awaiting classification | GET /internal/unclassified-meetings |

**Total tool count:** 37 base tools (from `ALL_RESPOND_TOOLS_FULL`) + 8 chat-specific = **45 tools**

### 31.7 Background Handler (`app/background/handler.py`)

**Entry point:** `POST /orchestrate/v2` → fire-and-forget → `handle_background(OrchestrateRequest) → dict`

**4-Phase Flow:**

```
Phase 1: INTAKE
  └─ Analyze task, select initial model tier, build context

Phase 2: EXECUTE (agentic loop, max 15 iterations)
  └─ LLM → tool_calls → execute → repeat
  └─ On failure → EscalationTracker bumps model tier
  └─ Max 3 escalation retries per failure

Phase 3: DISPATCH (if coding needed)
  └─ K8s Job via dispatch_coding_agent tool

Phase 4: FINALIZE
  └─ Save result to MongoDB
  └─ Notify Kotlin (report_status_change)
  └─ Log quality metrics
```

**Context management (dynamic tier selection):**

Background handler dynamically estimates context size before each LLM call
(same pattern as chat `handler_agentic.estimate_and_select_tier`):
1. Estimates tokens: `(messages + tools + output_reserve) // 4`
2. Selects tier via `EscalationPolicy.select_local_tier()`, clamped to `LOCAL_XLARGE` (128k max)
3. Re-estimates after each iteration — tool results grow the context
4. Auto-escalates if context exceeds 85% of current tier's `num_ctx`
5. Detects Ollama context overflow ("Operation not allowed" as text response) and escalates

**Search tool rate limiting:**

Search tools (`brain_search_issues`, `kb_search`, `web_search`, `brain_search_pages`)
are limited to max 3 calls per task. After 3 calls, a STOP message forces the LLM
to conclude with available results. Prevents IDLE_REVIEW loops.

**Key differences from foreground chat:**

| Aspect | Foreground (chat) | Background (v2) |
|--------|-------------------|------------------|
| Streaming | SSE tokens via kotlin_client | No streaming (status push only) |
| Tools | 45 (37 base + 8 chat) | ~30 (subset, no ask_user/memory/list_affairs) |
| Model selection | Dynamic + clamped to LOCAL_LARGE | Dynamic + clamped to LOCAL_XLARGE |
| Execution | Synchronous (awaited) | Fire-and-forget (asyncio.create_task) |
| System prompt | Dynamic runtime context | Basic task context |
| Compression | Fire-and-forget after answer | At finalize |

### 31.8 Model Tier Escalation (`app/background/escalation.py`)

Background tasks use progressive model escalation when the current tier fails:

```
LOCAL_FAST → LOCAL_STANDARD → LOCAL_LARGE → LOCAL_XLARGE → (cloud, if allowed)
```

**`needs_escalation(answer, tool_parse_failures, iteration)`** detects:
- Empty or None response
- Refusal patterns ("I cannot", "I'm unable", "I don't have access")
- Tool parse failures ≥ 2 (JSON workaround issues)
- Gibberish detection (low alphabetic ratio)

**`get_next_tier(current, cloud_allowed)`** follows the escalation path. Cloud bridge (`CLOUD_OPENROUTER → CLOUD_REASONING → CLOUD_CODING → CLOUD_PREMIUM`) is ONLY accessible when `cloud_allowed=True`, derived from project rules. OpenRouter is first in the cloud chain — unrestricted, handles any task.

**`EscalationTracker`** — stateful tracker per background execution:
- Tracks current tier, escalation count, failure reasons
- Max 3 escalation retries before giving up
- `escalate() → ModelTier | None` — returns next tier or None if exhausted

### 31.9 Background Tool Subset (`app/background/tools.py`)

Background tasks use a reduced tool set — excludes interactive and session-specific tools:

**Included:** KB (search, store, traverse, graph_search), web_search, repository tools (list_repos, list_branches, get_commits, read_file_content, search_code), git workspace tools (git_status, git_diff, git_commit, git_push, create_branch), filesystem tools (read_file, list_files, search_files, write_file, create_directory), terminal (execute_command), scheduled tasks (create_scheduled_task, list_scheduled_tasks), coding agent dispatch, brain tools (Jira/Confluence CRUD).

**Excluded:** `ask_user`, `memory_store`, `memory_recall`, `list_affairs` (foreground-only), `respond_to_user_task`, `classify_meeting`, `list_unclassified_meetings`.

### 31.10 New API Endpoints

| Endpoint | Method | Handler | Mode |
|----------|--------|---------|------|
| `/chat` | POST | `app/chat/handler.handle_chat` | Synchronous (awaited) |
| `/orchestrate/v2` | POST | `app/background/handler.handle_background` | Fire-and-forget |
| `/approve/{thread_id}` | POST | `app/main.approve` | Fire-and-forget resume |

`/chat` and `/orchestrate/v2` are registered in `app/chat/router.py`. `/approve/{thread_id}` is in `app/main.py`.

### 31.11 Kotlin Internal REST Endpoints (Implemented)

All v6 Python tool endpoints are implemented in `KtorRpcServer.kt` as thin REST wrappers
delegating to existing Kotlin services:

| Endpoint | Method | Delegates to | Used by |
|----------|--------|-------------|---------|
| `/internal/clients-projects` | GET | `ClientRpcImpl.getAllClients()` + `ProjectRpcImpl.listProjectsForClient()` | system_prompt.py |
| `/internal/pending-user-tasks/summary` | GET | `TaskRepository.findByTypeAndState(USER_TASK, PENDING)` | system_prompt.py |
| `/internal/unclassified-meetings/count` | GET | `MeetingRpcImpl.listUnclassifiedMeetings()` | system_prompt.py |
| `/internal/tasks/create` | POST | `TaskService.createTask()` | chat tool |
| `/internal/tasks/search` | GET | `TaskRepository.findAllByOrderByCreatedAtAsc()` + text filter | chat tool |
| `/internal/tasks/{id}/status` | GET | `TaskRepository.getById()` | chat tool |
| `/internal/tasks/recent` | GET | `TaskRepository.findAllByOrderByCreatedAtAsc()` | chat tool |
| `/internal/tasks/{id}/respond` | POST | `TaskRepository.save()` (state transition) | chat tool |
| `/internal/meetings/{id}/classify` | POST | `MeetingRpcImpl.classifyMeeting()` | chat tool |
| `/internal/unclassified-meetings` | GET | `MeetingRpcImpl.listUnclassifiedMeetings()` | chat tool |
| `/internal/dispatch-coding-agent` | POST | `TaskService.createTask(QUEUED)` | chat tool |

Request DTOs: `InternalCreateTaskRequest` (in InternalTaskApiRouting.kt), `InternalRespondToTaskRequest`, `InternalClassifyMeetingRequest`, `InternalDispatchCodingAgentRequest` (in KtorRpcServer.kt). `InternalCreateTaskRequest` accepts both legacy (`query`) and new (`title`, `description`, `schedule`, `daysOffset`, `createdBy`) fields.

Graceful degradation: `fetch_runtime_context()` catches HTTP errors per-endpoint, so chat works even if some endpoints fail.

### 31.12 New File Inventory

```
backend/service-orchestrator/
├── app/
│   ├── chat/
│   │   ├── __init__.py                    # (existing)
│   │   ├── context.py                     # (existing) Chat context assembler
│   │   ├── router.py                      # (modified) Added /chat, /orchestrate/v2
│   │   ├── models.py                      # NEW: ChatRequest, ChatStreamEvent
│   │   ├── system_prompt.py               # NEW: Runtime context + system prompt builder
│   │   ├── tools.py                       # NEW: 8 chat-specific tool definitions
│   │   └── handler.py                     # NEW: Foreground SSE agentic loop
│   └── background/
│       ├── __init__.py                    # NEW: Package init
│       ├── escalation.py                  # NEW: Model tier escalation logic
│       ├── tools.py                       # NEW: Background tool subset
│       └── handler.py                     # NEW: Simplified background agentic loop

backend/server/
└── src/main/kotlin/com/jervis/rpc/
    └── KtorRpcServer.kt                   # (modified) Added 11 /internal/* REST endpoints + 4 DTOs
```

### 31.13 Cloud Safety

Both handlers enforce the same cloud safety rule as the rest of the system:

- **Chat handler:** Passes `auto_use_*` flags from `ChatRequest` to `llm_with_cloud_fallback()` via project rules. Cloud is never used unless explicitly allowed.
- **Background handler:** Derives `cloud_allowed` from `OrchestrateRequest.rules` (`auto_use_anthropic or auto_use_openai or auto_use_gemini or auto_use_openrouter`). `EscalationTracker` only bridges to cloud tiers when `cloud_allowed=True`.
- **No implicit cloud:** If all project `auto_use_*` flags are `False`, escalation stops at `LOCAL_XLARGE` and the task fails if that tier can't handle it.
- **OpenRouter routing:** When `auto_use_openrouter=True`, OpenRouter is the first cloud tier tried (`CLOUD_OPENROUTER`). It routes via the priority model list configured in Settings → OpenRouter. No task type or context size restrictions — OpenRouter can handle everything.

### 31.14 Kotlin Integration (Implemented)

**Foreground chat** (master `7d31a405`):
- `ChatRpcImpl` → `ChatService` → `PythonChatClient.chat()` → SSE `POST /chat`
- Bypasses `AgentOrchestratorService` entirely; streams tokens directly to UI

**Background tasks** (v6 dispatch):
- `BackgroundEngine` → `AgentOrchestratorService.dispatchToPythonOrchestrator()`
- `dispatchBackgroundV6()` → `PythonOrchestratorClient.orchestrateV2()` → `POST /orchestrate/v2`
- Fallback: `dispatchLegacy()` → `PythonOrchestratorClient.orchestrateStream()` → `POST /orchestrate/stream`

| Kotlin method | Python endpoint | Mode |
|---------------|-----------------|------|
| `PythonChatClient.chat()` | `POST /chat` | Foreground SSE |
| `PythonOrchestratorClient.orchestrateV2()` | `POST /orchestrate/v2` | Background fire-and-forget |
| `PythonOrchestratorClient.orchestrateStream()` | `POST /orchestrate/stream` | Legacy fallback |

### 31.15 Migration Path

The v6 handlers coexist with the legacy LangGraph orchestrator:

1. **Phase 1 (current):** Both systems active; foreground→`/chat` (SSE), background→`/orchestrate/v2` with legacy fallback
2. **Phase 2 (stabilization):** Remove `dispatchLegacy()` fallback after v6 background handler is proven stable
3. **Phase 3 (cleanup):** Remove old 14-node LangGraph (`app/graph/orchestrator.py`), 22 specialist agents (`app/agents/specialists/`), unused graph nodes (`app/graph/nodes/`)

### 31.16 Chat Focus — Intent Classification & Drift Detection

**Problem:** qwen3-coder:30b model gets lost in 26 tools (~10.6k tokens = 33% of 32k context). Instead of answering simple questions, it cycles 8+ tool-call iterations (5-8 min). See `docs/chat-issues-analysis.md`.

**Solution:** 7 components reducing tool noise, maintaining focus, and detecting drift.

#### A. Tool Categories (`app/chat/tools.py`)

26 tools divided into 4 categories via `ToolCategory` enum:

| Category | Count | When exposed |
|----------|-------|-------------|
| **CORE** | 3 | Always (kb_search, web_search, memory_recall) |
| **RESEARCH** | 4 | Code/KB introspection keywords |
| **BRAIN** | 8 | Jira/Confluence keywords (issue, ticket, TPT-xxx, confluence...) |
| **TASK_MGMT** | 11 | Task/meeting keywords + switch_context + memory_store (úkol, přepni na, zapamatuj...) |

**Design decision (2026-02-23):** `switch_context` and `memory_store` moved from CORE to TASK_MGMT. Qwen3-30b has strong tool-calling bias — with 5 CORE tools, it called unnecessary tools (kb_search, switch_context, memory_store) on simple questions, causing 2 min response time instead of 5s. With 3 CORE tools, simple questions get direct answers without tool calls.

`TOOL_DOMAINS` dict maps each tool to a semantic domain (search, memory, brain, task, meeting, scope) for drift detection.

#### B. Intent Classifier (`app/chat/intent.py`)

Regex-based pre-pass (no LLM call, <1ms):

```python
classify_intent(user_message, has_pending_user_tasks, has_unclassified_meetings, has_context_task_id)
→ set[ToolCategory]   # always includes CORE
```

Patterns: `_BRAIN_PATTERNS` (Czech+English), `_TASK_MGMT_PATTERNS`, `_RESEARCH_PATTERNS`, `_FILTERING_PATTERNS`, `_GREETING_PATTERNS`. Git/coding keywords (git, branch, commit, push, merge, deploy, build) match both `_TASK_MGMT_PATTERNS` (for `dispatch_coding_agent`) and `_RESEARCH_PATTERNS` (for `code_search`). Context-driven: greeting + pending tasks → TASK_MGMT. User_task response → TASK_MGMT.

`select_tools(categories)` builds deduplicated tool list from matched categories.

**Typical result:** simple question → 3 CORE tools (~1.2k tokens) instead of 26 (~10.6k tokens).

#### B2. Simple Message Fast Path

For short messages (<500 chars) with CORE-only intent (no keywords matched), the handler tries a **direct answer without tools** first. LLM gets `tools=None` → must answer directly. If the answer is sufficient (no "potřebuji informace" markers), it's returned immediately (~5s). If insufficient, falls through to the normal agentic loop.

This eliminates the 60-120s overhead of 3-4 unnecessary tool calls for simple questions like "ahoj" or "na čem pracuju?".

#### C. Focus Reminder

After each iteration's tool results, a system message reminds the model:

```
[FOCUS] Původní otázka: "{message[:200]}"
Zbývá {remaining} iterací. Pokud máš dost info, ODPOVĚZ.
```

~80 tokens/iteration — pulls model back to the original question.

#### D. Multi-Signal Drift Detection (`_detect_drift()`)

Replaces simple "consecutive same signature" with 3 signals:

1. **Consecutive same** (2× identical tool+args) — model stuck in loop
2. **Domain drift** (3 iterations, 3+ distinct domains, no common domain) — model wandering between unrelated areas
3. **Excessive tools** (8+ distinct tools after 4+ iterations) — model unfocused

On detection, forces text response without tools (same as loop break).

#### E. Thinking Events (3 types, distinct wording)

1. **Pre-LLM** (before first iteration): `"Připravuji odpověď..."` or `"Analyzuji dlouhou zprávu..."` (>4k chars). Immediately replaces client-side "Zpracovávám..." so user gets feedback within milliseconds.
2. **Pre-tool** (before each tool call): `_describe_tool_call()` e.g. "Hledám v KB: ..."
3. **Inter-iteration** (after tool results, before next LLM): `"Analyzuji výsledky..."`. Prevents stale "Přepínám na..." during 60s LLM call.
4. **Long message warning** (>49k estimated tokens, iteration 0): `"Dlouhá zpráva — zpracování potrvá déle..."`. GPU VRAM exceeded → CPU spill → much slower.

#### F. System Prompt — Strong Direct Answer Rules + Anti-Dump

System prompt restructured (2026-02-23) to combat Qwen3's tool-calling bias:

- **⚠️ KLÍČOVÉ PRAVIDLO section** — explicit "answer from context ABOVE" instruction with concrete examples (Q: "Na čem pracuju?" → look at client/project in context and ANSWER, don't call kb_search)
- **Negative examples** — "NEVOLEJ tools v těchto případech" list with specific tools and when NOT to use them
- **Few-shot examples** — 3 concrete Q&A showing correct no-tool behavior
- "Maximálně 2-3 tool calls" — cap tool usage
- "NIKDY neukládej celou zprávu uživatele do KB/memory" — prevents model from storing user's message verbatim
- "NIKDY neukládej runtime stav" — prevents storing trivial facts like "active project is nUFO"

#### G. MAX_ITERATIONS 15 → 6

With intent filtering + focus reminders + drift detection, 6 iterations suffice. Typical: 1-2 (simple), 3-4 (multi-intent). Worst case: 6 × 60s = 6 min vs 15 × 60s = 15 min.

#### H. Long Message Intent (head+tail)

For messages >2000 chars, `classify_intent()` analyzes only first 500 + last 500 characters. Long messages (bug reports, analyses) contain keywords from all categories in the body, but the actual intent is in the first/last sentences. Full message is sent to LLM unchanged — only intent classification uses the excerpt.

#### I. Duplicate Send Guard (ChatViewModel)

`_isLoading` set to `true` synchronously BEFORE `scope.launch`. `sendMessage()` returns immediately if `_isLoading` is already true. Prevents race condition where rapid double-click or UI retry created two parallel SSE connections.

#### J. Long Message Strategy — Summarize then Act

Foreground chat uses a multi-layer strategy for long messages:

```
Message length?
  < 8k chars:  pass through unchanged (fits in context easily)
  8k-16k:      try decompose (multi-topic), else single-pass
  > 16k:       SUMMARIZE first (LOCAL_FAST ~5s), then agentic loop on summary
```

**Summarize-then-Act (messages >16k chars):**
1. Original message saved to KB FIRST — nothing is ever lost
2. LLM summarizer (LOCAL_FAST, CRITICAL priority, **90s timeout**) creates structured summary (~2-4k chars)
3. Summary preserves ALL: requirements, action items, questions, key details (IDs, names, numbers)
4. Agentic loop works with compact summary instead of raw message
5. If summarizer fails → **suggest background task** (NEVER fall back to pre-trim)

**NO-TRIM PRINCIPLE (CRITICAL):**
The current user message is NEVER truncated/trimmed. This is enforced at two levels:
- **Handler**: if summarizer fails, immediately suggest background task instead of falling through
- **LLM Provider**: `_trim_messages_for_context()` skips the last (current) user message;
  only OLD conversation history messages may be trimmed

**Summarizer timeout (90s):**
Must be >60s because Router GPU cleanup can take up to 60s when embedding model needs
to be unloaded (wait loop with 2s polling + force unload at 60s). Previous 30s timeout
caused summarizer to ALWAYS fail when embedding model was loaded on GPU.

**Tier Ceiling (VRAM protection):**
Foreground chat NEVER goes above LOCAL_LARGE (40k context). LOCAL_XLARGE (131k) causes
catastrophic CPU spill on P40 (6GB overflow, 630s for 387 tokens). Instead of escalating,
the message is summarized (not trimmed) to fit in 40k.

**Dynamic MAX_ITERATIONS:**
- Short messages (<8k): MAX_ITERATIONS=6 (standard)
- Long messages (>8k): MAX_ITERATIONS_LONG=3 (each iteration costs 3-5 min on GPU)

**Background offload:**
- FOCUS hint with background suggestion injected for ALL long messages (>16k), not just summarized ones
- System prompt instructs model: if message contains >5 distinct tasks, suggest
  `create_background_task` instead of processing everything in foreground chat
- If summarizer fails: handler immediately suggests background task (no fallback to trim)

**Cooperative disconnect:**
Disconnect event is checked not just between iterations but also inside the tool execution
loop. This prevents zombie streams from continuing to execute tools after the user sent
a new message (which sets disconnect_event on the old stream).

#### K. Long Message Decomposition

Multi-topic long messages (>8000 chars) are detected and split:

```
Message > 8000 chars?
  NO → existing flow
  YES → LLM classifier (LOCAL_FAST, head + middle samples + tail, ~3s)
        → single-topic? → existing flow (fallback)
        → multi-topic?  → extract sub-topics with char ranges
                        → process each in mini agentic loop (3 iter max)
                        → combine results via LLM combiner
```

- **Classifier**: LOCAL_FAST with CRITICAL priority, ~3500 chars sampled:
  head (1500) + 2 middle samples at 1/3 and 2/3 (500 each) + tail (500).
  Middle samples catch topic boundaries that head+tail alone would miss.
- **Sub-topic processing**: each gets own agentic mini-loop (SUBTOPIC_MAX_ITERATIONS=3), same tools/drift detection
- **Combiner**: LOCAL_FAST with CRITICAL priority, merges sub-results into one cohesive response
- **Fallback**: any classifier/parse failure → existing single-pass flow (zero regression)
- **Latency**: 3-topic message ~90s (classifier 3s + 3×25s + combiner 8s) vs 4-8 min single-pass CPU-spill

#### L. Pre-trim: Oversized Messages

When a user message exceeds the tier's context window capacity, the LLM provider
automatically trims it before sending to Ollama (preserving 75% head + 25% tail
with a truncation marker). This prevents sending excess data to Ollama where it
would be internally discarded anyway — saves network transfer and tokenization overhead.
Non-user messages are never trimmed.

#### M. Anti-dump Guards

The model tends to dump entire long messages into KB/memory instead of answering.
Three-layer defense:

1. **Tool removal**: For messages >8000 chars, `store_knowledge` and `memory_store`
   are removed from the tool set entirely.
2. **Content-length guard**: Both tools reject content >2000 chars with an error
   message instructing the model to summarize.
3. **Focus injection**: Long single-topic messages get an extra system message
   before the agentic loop: "ODPOVĚZ na zprávu, NEUKLÁDEJ ji."

#### N. Classifier Timeout

The decompose classifier has a hard 15s timeout (`asyncio.wait_for`).
If GPU is busy (model swap, semaphore queue), it falls back to single-pass
rather than adding 2+ minutes overhead.

#### O. Server-side Chat Dedup

If a new POST /chat arrives for a session_id that already has an active SSE stream,
the previous stream is stopped (disconnect_event.set()) before starting the new one.
Prevents duplicate concurrent processing from kRPC retries or double-clicks.

#### P. Tier Timeout Strategy

Blocking calls (tool-call mode) use tier-based timeouts:

| Tier | num_ctx | Timeout | Rationale |
|------|---------|---------|-----------|
| LOCAL_FAST | 8k | 300s | Pure GPU, ~30 tok/s |
| LOCAL_STANDARD | 32k | 300s | Pure GPU, ~30 tok/s |
| LOCAL_LARGE | **40k** | **600s** | Fits in P40 VRAM (30b + 40k KV < 24GB) |
| LOCAL_XLARGE | 128k | 900s | CPU RAM spill, ~7-12 tok/s (NOT used in foreground chat) |
| LOCAL_XXLARGE | 256k | 1200s | CPU, slowest (NOT used in foreground chat) |
| Cloud tiers | — | 300s | Fast APIs |

#### Q. Enhanced Drift Detection

Four signals for detecting model loops:

1. **Consecutive same** (existing): 2× identical tool+args → stuck
2. **Same tool 3×** (NEW): same tool name called 3+ times across ANY iterations, even non-consecutive.
   Catches: `kb_search("X")` in iter 1, `create_task` in iter 2, `kb_search("X")` in iter 4, `kb_search("Y")` in iter 6 — 3× kb_search → drift.
3. **Domain drift** (existing): 3 iterations with 3+ distinct domains, no common → wandering
4. **Excessive tools** (existing): 8+ distinct tools after 4+ iterations → unfocused

#### R. Dynamic/Learning System Prompt

System prompt contains a dynamic "Naučené postupy a konvence" section loaded from KB at chat start.

- **Loading**: `_load_learned_procedures()` searches KB for entries with procedure/convention keywords, cached 5 min.
- **Learning**: When user teaches a new procedure ("pro BMS vždy vytvoř issue"), the model stores it via `memory_store(category="procedure")`.
- **Persistence**: Stored in KB → survives restarts. Next chat session loads updated procedures.
- **Instruction**: System prompt tells model to use `memory_store(category="procedure")` for new learnings.

#### Token Impact

| | Before | After | Delta |
|---|---|---|---|
| Tool schemas (avg) | ~2,600 | ~900 | -1,700 |
| System prompt tools | ~375 | ~120 | -255 |
| Focus reminder | 0 | +80/iter | +160 |
| **Per call total** | **~2,975** | **~1,180** | **-1,795** |

---

## 32. Intent Router + Cloud-First Chat (feature-flagged)

> **Implemented:** 2026-03-01 | **Feature flag:** `use_intent_router=False` (disabled by default)

### 32.1 Motivation

Monolithic system prompt (160 lines) + 26 tools = excessive context, slow responses, unfocused tool usage.
Intent router enables: focused prompts (~60-80 lines), 3-13 tools per category, cloud-first routing for quality.

### 32.2 Two-Pass Classification

**Pass 1: Regex fast-path** (0ms, handles ~60% of messages):
- CORE only → DIRECT (no tools needed)
- Single non-CORE category → map directly (RESEARCH, BRAIN, TASK_MGMT)

**Pass 2: LLM classification** (~2-3s, LOCAL_FAST tier on P40):
- Multiple regex hits → LLM decides category + confidence
- Low confidence (<0.7) → fallback to RESEARCH

### 32.3 Categories & Tool Sets

| Category | Tools (count) | Max Iters | Use Case |
|----------|---------------|-----------|----------|
| DIRECT | none (0) | 1 | Greetings, simple questions |
| RESEARCH | kb_search, code_search, web_search, memory_recall, switch_context (5) | 3 | Information lookup |
| BRAIN | brain_* + switch_context (11) | 4 | Jira/Confluence CRUD |
| TASK_MGMT | task lifecycle + meetings + KB (11) | 4 | Background tasks, meetings |
| COMPLEX | work plans, coding, KB, brain, web (7) | 6 | Multi-step complex tasks |
| MEMORY | kb_search, kb_delete, memory_store, store_knowledge, memory_recall, code_search (6) | 3 | KB corrections, learning |

### 32.4 Prompt Architecture

```
core.py (shared identity + time + scope + runtime data + CRITICAL RULES)
  + category-specific prompt (10-20 lines each)
  = focused system prompt (~60-80 lines vs ~160 lines monolithic)
```

**Critical rules in core.py** (always applied):
- Absolute client/project isolation
- "User is always right" — corrections ≠ feature requests
- KB may contain hallucinations — verify before trusting
- Trust hierarchy: User > code_search > brain_search > kb_search

### 32.5 Cloud Routing

CHAT_CLOUD queue: claude-sonnet-4 → gpt-4o → p40 (fallback).
DIRECT category stays on P40 (LOCAL_FAST). All other categories use cloud-first when OpenRouter is available.

### 32.6 Files

- `app/chat/intent_router.py` — route_intent(), _llm_classify()
- `app/chat/prompts/` — core.py, direct.py, research.py, brain.py, task_mgmt.py, complex.py, memory.py, builder.py
- `app/chat/models.py` — ChatCategory, RoutingDecision
- `app/chat/tools.py` — select_tools_by_names()
- `app/llm/openrouter_resolver.py` — CHAT_CLOUD queue
- `app/config.py` — use_intent_router + per-category settings

---

## 33. Hierarchical Task System & Work Plan Decomposition

> **Implemented:** 2026-03-01

### 33.1 Task Hierarchy

TaskDocument now supports parent-child relationships:
- `parentTaskId` — links child to root task
- `blockedByTaskIds` — dependencies that must complete before this task runs
- `phase` + `orderInPhase` — ordering within work plan phases
- State: `BLOCKED` (waiting for deps; also used for root tasks being decomposed)

### 33.2 WorkPlanExecutor

New loop in BackgroundEngine (15s interval):
1. Find BLOCKED tasks → if all blockedByTaskIds are DONE → unblock to INDEXING
2. Find BLOCKED root tasks (with children) → if all children DONE → root.state = DONE with summary
3. If any child ERROR → root escalated to USER_TASK

### 33.3 create_work_plan Tool

Chat tool that creates hierarchical work plans:
- LLM sends phases + tasks with dependencies
- Python forwards to `POST /internal/tasks/create-work-plan`
- Kotlin creates root (BLOCKED) + children (BLOCKED/INDEXING)
- First phase tasks without dependencies start immediately
- WorkPlanExecutor handles the rest automatically

### 33.4 Unified Chat Stream

Background results and urgent alerts pushed to chat:
- `ChatRpcImpl.pushBackgroundResult()` — on task completion
- `ChatRpcImpl.pushUrgentAlert()` — on urgent KB results
- New MessageRole: BACKGROUND, ALERT
- ChatContextAssembler maps to "system" role with prefixes for LLM awareness

## 34. Graph Agent — Vertex/Edge Task Decomposition DAG

> **Status:** Fully implemented — LangGraph execution with responsibility-based vertex types and agentic tool loop
> **Source:** `backend/service-orchestrator/app/graph_agent/`

### 34.1 Motivace

Současný delegation systém (sekce 18-25) používá fixní `parallel_groups` bez přenosu kontextu mezi delegacemi. Graph Agent nahrazuje tento model plným DAG:

- Vstupní požadavek se rozloží na **vrcholy** (vertices) propojené **hranami** (edges)
- Každý vrchol se dál rozpracovává (rekurzivní dekompozice)
- **Hranou** do dalšího vrcholu jde **sumář výsledku** + **plný kontext** (prohledávatelný)
- **Fan-in**: pokud se do vrcholu sejde 10 hran → 10 sumářů + 10 kontextů
- **Fan-out**: vrchol se rozloží na více sub-vrcholů
- Výsledek se skládá z výsledků terminálních vrcholů

### 34.2 Data Model

```python
# Enums — responsibility-based vertex types
VertexType:  ROOT | PLANNER | INVESTIGATOR | EXECUTOR | VALIDATOR | REVIEWER | SYNTHESIS | GATE | SETUP | TASK | DECOMPOSE
VertexStatus: PENDING | READY | RUNNING | COMPLETED | FAILED | SKIPPED | CANCELLED
EdgeType:    DEPENDENCY | DECOMPOSITION | SEQUENCE
GraphStatus: BUILDING | READY | EXECUTING | COMPLETED | FAILED | CANCELLED

# What flows through an edge (filled when source completes)
class EdgePayload:
    source_vertex_id: str
    source_vertex_title: str
    summary: str           # Concise result summary
    context: str           # Full context (searchable at target)

# Processing unit
class GraphVertex:
    id, title, description, vertex_type, status
    agent_name: str | None
    input_request: str
    incoming_context: list[EdgePayload]  # From incoming edges
    result: str
    result_summary: str         # For outgoing edges
    local_context: str          # Full context (searchable downstream)
    parent_id: str | None       # Decomposition hierarchy
    depth: int

# Connection
class GraphEdge:
    id, source_id, target_id, edge_type
    payload: EdgePayload | None  # Filled when source completes

# Complete DAG
class TaskGraph:
    id, task_id, client_id, project_id
    root_vertex_id: str
    vertices: dict[str, GraphVertex]
    edges: list[GraphEdge]
    status: GraphStatus
```

### 34.3 Graph Operations

| Operation | Description |
|-----------|-------------|
| `create_task_graph()` | Create graph with root vertex |
| `add_vertex()` | Add vertex, auto-depth from parent |
| `add_edge()` | Add edge, recalculate target readiness |
| `get_ready_vertices()` | Vertices where ALL incoming edges have payloads |
| `start_vertex()` | Mark RUNNING, populate `incoming_context` from edges |
| `complete_vertex()` | Mark COMPLETED, fill outgoing edge payloads, cascade readiness |
| `fail_vertex()` | Mark FAILED, propagate SKIPPED to unreachable downstream |
| `topological_order()` | Kahn's algorithm for execution order |
| `get_final_result()` | Compose result from terminal vertices |

### 34.4 Context Accumulation Flow

```
vertex A completes → edge A→C gets EdgePayload(summary_A, context_A)
vertex B completes → edge B→C gets EdgePayload(summary_B, context_B)
                                        ↓
vertex C becomes READY (all incoming edges have payloads)
C.incoming_context = [payload_A, payload_B]
C processes with access to both upstream contexts
C completes → edge C→D gets EdgePayload(summary_C, context_C)
                                        ↓
D.incoming_context includes C's context which itself references A and B
```

After N vertices, the context chain carries N summaries + full contexts.

### 34.5 MongoDB Persistence

Collection: `task_graphs` (TTL: 30 days, unique index on `task_id`)

Supports atomic vertex status updates via MongoDB dot notation:
```python
await store.update_vertex_status(task_id, vertex_id, VertexStatus.COMPLETED, result="...")
await store.update_edge_payload(task_id, edge_id, payload)
await store.update_graph_status(task_id, GraphStatus.COMPLETED)
```

### 34.6 Progress Reporting

Uses existing `kotlin_client.report_progress()` with `delegation_id`, `delegation_agent`, `delegation_depth` to stream vertex progress to UI.

### 34.7 Implementation Parts

| Part | Status | Description |
|------|--------|-------------|
| **1** | Done | Core data model, graph operations, persistence, progress |
| **2** | Done | LLM-driven decomposition engine (root + recursive), graph validation |
| **3** | Done | LangGraph execution: StateGraph, responsibility-based vertex types, agentic tool loop |
| **4** | Done | Default tool sets per vertex type, `request_tools` meta-tool for dynamic expansion |
| **5** | Done | Integration: feature flag `use_graph_agent`, wired into `run_orchestration` |

### 34.8 Decomposition Engine

**Source:** `app/graph_agent/decomposer.py`

Two entry points:
- `decompose_root(graph, state, evidence, guidelines)` — decomposes the root vertex from user request
- `decompose_vertex(graph, vertex_id, state, guidelines)` — recursively decomposes a DECOMPOSE-type vertex

**LLM Prompt Pattern:**

The decomposer asks the LLM to choose the correct **responsibility type** for each vertex:
```json
{
  "vertices": [
    {"title": "...", "description": "...", "type": "investigator|planner|executor|task|validator|reviewer|gate|setup|decompose", "agent": "research", "depends_on": [0]},
    ...
  ],
  "synthesis": {"title": "Combine results", "description": "..."}
}
```

The `depends_on` field references indices within the vertices array. Synthesis vertex auto-depends on all others.

**Typical decomposition patterns:**
- `investigator → executor → validator` (research → do → verify)
- `planner → multiple executors → reviewer` (plan → parallel work → review)
- `investigator → gate → executor` (research → decide → act)

**Limits:**
- `MAX_VERTICES_PER_DECOMPOSE = 10` (per LLM call)
- `MAX_TOTAL_VERTICES = 200` (entire graph)
- `MAX_DECOMPOSE_DEPTH = 8` (recursive depth)

**Fallback:** If decomposition fails, creates a single TASK vertex that executes the entire request directly.

### 34.9 Graph Validation

**Source:** `app/graph_agent/validation.py`

`validate_graph(graph)` returns `ValidationResult(valid, errors, warnings)`:

| Check | Type | Limit |
|-------|------|-------|
| Root exists | Error | — |
| No cycles (DAG) | Error | — |
| Vertex count | Error | max 50 |
| Edge references exist | Error | — |
| Orphan vertices | Warning | — |
| Fan-in | Error | max 15 |
| Fan-out | Warning | max 10 |
| TASK has description | Error | — |
| Depth limit | Error | max 4 |

### 34.10 LangGraph Execution

**Source:** `app/graph_agent/langgraph_runner.py`

Uses LangGraph `StateGraph` for execution. TaskGraph is carried in LangGraph state — LangGraph handles checkpointing, interrupt/resume, and execution flow.

**StateGraph flow:**
```
decompose → select_next → dispatch_vertex → select_next → ... → synthesize → END
```

**LangGraph nodes:**

| Node | Responsibility |
|------|---------------|
| `node_decompose` | Call LLM decomposer, create TaskGraph, validate, persist |
| `node_select_next` | Find ALL READY vertices (all incoming edges have payloads) |
| `node_dispatch_vertex` | Run agentic tool loop — parallel `asyncio.gather` for multiple ready vertices |
| `node_synthesize` | LLM-based synthesis of results (falls back to concatenation if LLM fails) |

**Routing:** `route_after_select` → dispatch_vertex (if vertex found) or synthesize (if done). `route_after_dispatch` → always back to select_next.

**Checkpointing:** MongoDB (`jervis_graph_agent` DB) via `MongoDBSaver`. Recursion limit: 200.

### 34.11 Agentic Tool Loop

Each vertex executes via a unified agentic tool loop (`_agentic_vertex`):

1. Load default tools for vertex type via `get_default_tools(vertex_type)`
2. Build system prompt from `_SYSTEM_PROMPTS[vertex_type]`
3. Call LLM with tools (max 12 iterations)
4. If LLM returns tool calls → execute each → append results to messages → repeat
5. If LLM calls `request_tools` meta-tool → add requested categories to tool set
6. If LLM returns text (no tool calls) → that's the final result

**Special cases:**
- EXECUTOR/TASK with `agent_name` + `use_specialist_agents` → try specialist agent dispatch first, fall back to LLM
- Tool loop detection via `detect_tool_loop()` (skip duplicate calls)
- Per-tool timeout: 60s via `asyncio.wait_for()`
- Per-vertex overall timeout: 600s (wraps entire vertex execution)
- Parallel execution: multiple READY vertices run concurrently via `asyncio.gather()`

### 34.12 Default Tool Sets

**Source:** `app/graph_agent/tool_sets.py`

| Vertex Type | Default Tools | Can Request More? |
|-------------|--------------|-------------------|
| PLANNER/DECOMPOSE | KB search, memory recall, repo info/structure, tech stack, KB stats, queue, **get_guidelines** | Yes |
| INVESTIGATOR | Above + web search, file listing, commits, branches, indexed items, **list_unclassified_meetings** | Yes |
| EXECUTOR/TASK | KB search, **ask_user**, web search, files, repo, coding agent, KB write, memory, scheduling, **get/update_guidelines**, **classify/list_meetings** | Yes |
| VALIDATOR | KB search, files, repo, branches, commits, **dispatch_coding_agent** | Yes |
| REVIEWER | KB search, files, repo, branches, commits, tech stack, **get_guidelines** | Yes |
| SYNTHESIS | KB search, memory recall, KB write, memory store | No |
| GATE | KB search, memory recall, **ask_user** | Yes |
| SETUP | KB search, ask_user, environment CRUD, project mgmt, repo info/structure, tech stack, coding agent, KB write, memory, **get/update_guidelines** | Yes |

**`request_tools` meta-tool:** Any vertex with this tool can dynamically request additional categories:
- Categories: `kb`, `web`, `git`, `code`, `memory`, `scheduling`, **`interactive`**, **`guidelines`**, **`meetings`**, `queue`, `environment`, `project_management`, `setup`, `all`
- `interactive` = ask_user (for vertices that don't have it by default)
- `guidelines` = get_guidelines + update_guideline
- `meetings` = classify_meeting + list_unclassified_meetings
- Tools are appended to the current set (deduplicated by name)
- Available in next LLM call iteration

### 34.13 Recursive Decomposition

PLANNER/DECOMPOSE vertices don't execute via agentic tool loop. `node_dispatch_vertex` detects these types and calls `decompose_vertex()` to create new sub-vertices + edges in the graph. Children are picked up by subsequent `select_next` cycles.

**Limits:** `MAX_DECOMPOSE_DEPTH=8`, `MAX_TOTAL_VERTICES=200`. When hit, vertex auto-converts to EXECUTOR.

### 34.14 Discussion vs Implementation — Decomposer Intelligence

The decomposer distinguishes between **discussion/specification** and **implementation commands**:

**Discussion phase** (vague/incomplete requirements):
- "Klient by chtěl aplikaci na správu domácí knihovny" → single conversational vertex (asks "what platforms? what features?")
- "Mělo by to mít konektivitu na databázi knih" → single vertex (refines: "which API? what data to fetch?")
- Requirements accumulate in **memories (affairs)** across messages
- Each confirmed decision is stored to KB via `store_knowledge(category='specification')`

**Progressive KB capture during discussion:**
Discussion executor vertices automatically persist each decision to KB with `category='specification'`. This creates structured, searchable entries like:
- Subject: "Platform decision" → Content: "Android + iOS with KMP"
- Subject: "Storage choice" → Content: "PostgreSQL for book catalog, Redis for caching"
- Subject: "Feature: auth" → Content: "OAuth2 with Google and Apple Sign-In"

These entries survive memory compression — when memories get compacted over hours/days of discussion, the full details remain in KB.

**Cross-project references:**
When the user mentions another project (e.g., "this logging solution would work in project XYZ"), `store_knowledge` accepts `target_project_name` parameter. The knowledge is stored for BOTH the current project and the referenced project. When later working on project XYZ, the knowledge is discoverable via KB search.

**Implementation command** (explicit + sufficient context):
- "Tak to implementuj" / "Build it" → full graph with SETUP, EXECUTOR, VALIDATOR vertices
- "Napiš aplikaci v KMP s PostgreSQL backendem" → full workflow (clear spec in one message)
- SETUP vertex **reconstructs from KB**: first searches for all `specification` entries, then combines with memories summary to build complete requirements brief

**SETUP reconstruction flow:**
1. `kb_search("specification")` → finds all progressively stored requirements
2. `kb_search("platform decision")`, `kb_search("feature")` → targeted searches for specific aspects
3. Upstream context from memories → high-level summary
4. Combine KB details + memory summary → complete requirements brief for `get_stack_recommendations`

**Key principle:** The agent leads a natural discussion, asking clarifying questions, until the user explicitly commands implementation. The memories system accumulates requirements across messages. SETUP vertex is NEVER created for vague/incomplete requirements.

No heuristic short-circuit for "trivial" requests. Text length says nothing about complexity — "jaký je stav projektu?" is short but requires deep analysis. The decomposer LLM decides: simple request → 1 vertex, complex → multiple vertices with dependencies.

### 34.15 ArangoDB Artifact Graph — Impact Analysis

**Source:** `app/graph_agent/artifact_graph.py`, `app/graph_agent/impact.py`

ArangoDB-backed graph tracking ALL entities Jervis manages — code artifacts (from Joern CPG via KB), documents, meetings, people, test plans, budgets, etc. Direct ArangoDB access from orchestrator (`python-arango`).

**Collections:**

| Collection | Type | Purpose |
|------------|------|---------|
| `graph_artifacts` | Vertex | Entities of all kinds (code, docs, people, events, etc.) |
| `artifact_deps` | Edge | Structural/organizational dependencies |
| `task_artifact_links` | Document | TaskGraph vertex → entity it touches (with `artifact_id`, `vertex_id`, `touch_kind`) |

**Impact analysis flow (per vertex completion):**

1. LLM extracts touched entities from vertex result (`_EXTRACT_ARTIFACTS_PROMPT`)
2. Entities + dependencies persisted in ArangoDB (`upsert_artifacts_batch`, `add_dependencies_batch`)
3. For each modifying touch → AQL traversal (INBOUND, BFS, depth 3) finds all dependents
4. Cross-check: which OTHER planned vertices touch affected entities?
5. If found → inject VALIDATOR vertex into graph (blocks affected vertices until verified)
6. Detect conflicts: two vertices modifying same entity → log warning

**Code artifacts** link to existing KnowledgeNodes (Joern CPG) via `kb_node_key` — no duplication.

**Key AQL patterns:**
- `find_affected_artifacts()` — BFS traversal through `artifact_deps` INBOUND
- `find_affected_task_vertices()` — two-step: traverse deps → join with `task_artifact_links`
- `find_conflicting_vertices()` — group `task_artifact_links` by artifact, filter multi-vertex

### 34.16 Cancellation & Graceful Degradation

**Cancellation flow:**

1. User clicks Cancel → Kotlin calls `cancelOrchestration(taskId)` → reads `orchestratorThreadId` → `POST /cancel/{thread_id}`
2. `/cancel` endpoint marks `graph.status = CANCELLED` in MongoDB persistence
3. `/cancel` reports `status="cancelled"` to Kotlin via `POST /internal/orchestrator-status`
4. Kotlin `OrchestratorStatusHandler.handleCancelled()` transitions task to `DONE`, saves cancel message, cleans up
5. `/cancel` then calls `task.cancel()` on the asyncio Task + removes from `_active_tasks`
6. In the agentic tool loop (`_agentic_vertex`), each iteration checks `graph.status` before the next LLM call
7. Running vertex gets `VertexStatus.CANCELLED`, returns `("Cancelled by user.", "Cancelled")`

**ArangoDB resilience (retry with backoff):**

- `artifact_graph_store.init()` retries with exponential backoff: `5s → 15s → 30s → 60s → 5min cap`
- Matches the project-wide resilience pattern (workspace recovery, task dispatch)
- Service startup blocks until ArangoDB is reachable — no partial-feature state
- Each attempt logs a warning with attempt count and next retry delay

### 34.17 Background Dispatch & Queue Priority

**Background path:** When `use_graph_agent=True`, `handle_background()` routes directly to `run_graph_agent()`. No legacy 5-phase loop. Flow:

```
Chat LLM → create_background_task tool → Kotlin creates BACKGROUND TaskDocument
  → BackgroundEngine picks up → Python /orchestrate/v2 → handle_background()
  → run_graph_agent() (async, doesn't block chat)
  → Progress via pushBackgroundResult → appears in chat
```

**Priority tools for PLANNER vertex:**

| Tool | Purpose |
|------|---------|
| `task_queue_inspect` | List queued BACKGROUND tasks across all clients/projects (ordered by priorityScore) |
| `task_queue_set_priority` | Set priorityScore (0–100) for a task — higher = sooner execution |

**Priority as decomposition:** PLANNER vertex gets queue tools by default. When decomposing a new task, it can:
1. Inspect the current queue (`task_queue_inspect`)
2. Analyze dependencies between tasks
3. Set optimal priority scores (`task_queue_set_priority`)
4. Decompose its own task accordingly

This means **LLM decides priority** based on understanding of tasks, not hardcoded rules. Cross-project, cross-client.

**Kotlin internal API:**
- `GET /internal/tasks/queue?clientId=&limit=` — queued BACKGROUND tasks ordered by priority
- `POST /internal/tasks/{id}/priority` — set priorityScore (0–100)

### 34.18 Project Management & Git Internal APIs

**Internal REST endpoints** for SETUP vertex type and MCP tools:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/internal/clients` | POST | Create new client |
| `/internal/clients` | GET | List all clients |
| `/internal/projects` | POST | Create project for client |
| `/internal/projects` | GET | List projects (optionally by clientId) |
| `/internal/projects/{id}` | PUT | Update project (description, gitRemoteUrl) |
| `/internal/connections` | POST | Create external service connection |
| `/internal/connections` | GET | List all connections |
| `/internal/git/repos` | POST | Create GitHub/GitLab repository via provider API |
| `/internal/git/init-workspace` | POST | Trigger workspace clone for project |
| `/internal/project-advisor/recommendations` | POST | Get stack recommendations (advisor pattern) |
| `/internal/project-advisor/archetypes` | GET | List available architecture archetypes |

**SETUP vertex advisor workflow:**
1. SETUP vertex calls `get_stack_recommendations(requirements)` — accumulates all requirements from conversation history
2. Recommendations include architecture archetype, platforms, storage, features — each with pros/cons/alternatives
3. SETUP vertex presents choices to user via `ask_user` for confirmation
4. After confirmation: create infrastructure (client, project, connection, git repo)
5. Dispatch `coding_agent` with scaffolding instructions from recommendations
6. Provision environment and init workspace

**Source files:**
- `backend/server/.../rpc/internal/InternalProjectManagementRouting.kt`
- `backend/server/.../rpc/internal/InternalGitRouting.kt`
- `backend/server/.../service/git/GitRepositoryCreationService.kt`
- `backend/server/.../service/project/ProjectTemplateService.kt` — advisor pattern (recommendations, not file generation)

**Orchestrator tools** (SETUP vertex + MCP):
- `create_client(name, description)` — create client
- `create_project(client_id, name, description)` — create project
- `create_connection(name, provider, auth_type, base_url, bearer_token, client_id)` — create connection (optionally linked to client)
- `create_git_repository(client_id, name, description, connection_id, is_private)` — create GitHub/GitLab repo
- `update_project(project_id, description, git_remote_url)` — update project, link git repo
- `init_workspace(project_id)` — trigger workspace clone
- `get_stack_recommendations(requirements)` — get technology recommendations with pros/cons

### 34.19 Orchestration Entry Point

**Source:** `app/graph_agent/langgraph_runner.py`

`run_graph_agent(request, thread_id)` — called from `handle_background()` (BACKGROUND tasks) or `run_orchestration()` (FOREGROUND):
```
LangGraph.ainvoke(initial_state) → decompose → [select → dispatch → loop] → synthesize → END
```

Returns the final LangGraph state dict with `final_result` and `task_graph`.

### 34.18 Graph Visualization in Chat UI

**Data flow:**
```
Python GET /graph/{task_id} → JSON
  → PythonOrchestratorClient.getTaskGraph()
  → TaskGraphRpcImpl (ITaskGraphService) → lenient JSON → TaskGraphDto
  → ChatViewModel.loadTaskGraph() → _taskGraphs cache
  → ChatMessageDisplay BACKGROUND_RESULT → TaskGraphSection composable
```

**DTOs** (`shared/common-dto/.../graph/TaskGraphDtos.kt`): `TaskGraphDto`, `GraphVertexDto`, `GraphEdgeDto`, `EdgePayloadDto` — mirror Python models with `@SerialName` snake_case mapping.

**UI components** (`shared/ui-common/.../chat/TaskGraphComponents.kt`):

| Component | Purpose |
|-----------|---------|
| `TaskGraphSection` | Expandable section in BACKGROUND_RESULT card. Collapsed: graph icon + summary line. Expanded: stats row + vertex tree |
| `GraphStatsRow` | FlowRow of `StatChip`s: status, vertex count, edge count, LLM calls, tokens, project |
| `VertexCard` | Depth-indented card per vertex. Header: type icon + title + status badge. Expandable body: description, debug stats (agent, depth, tokens, LLM calls, tools), timing, errors, input request, result, local context, incoming edges |
| `EdgeRow` | Source vertex title + edge type + payload summary |
| `ExpandableTextSection` | Collapse/expand for long text fields (input, result, context) |

**Loading pattern:** Lazy — graph is fetched on demand when user clicks "Zobrazit graf" button in the BACKGROUND_RESULT card. Cached in `ChatViewModel._taskGraphs: Map<String, TaskGraphDto?>`. `null` value = loading in progress.

**Vertex status colors:**
- `completed` → surface (default)
- `running` → primaryContainer (30% alpha)
- `failed` → errorContainer (20% alpha)
- `cancelled` → surfaceVariant (50% alpha)

### 34.19 Key Files

| File | Purpose |
|------|---------|
| `app/graph_agent/__init__.py` | Package docstring |
| `app/graph_agent/models.py` | All data models and enums (responsibility-based VertexType) |
| `app/graph_agent/graph.py` | Graph operations (add/remove/traverse/complete/fail) |
| `app/graph_agent/persistence.py` | MongoDB CRUD with atomic updates |
| `app/graph_agent/progress.py` | Progress reporting to Kotlin server |
| `app/graph_agent/decomposer.py` | LLM-driven decomposition (root + recursive, depth 8) |
| `app/graph_agent/validation.py` | Structural validation (cycles, limits, orphans) |
| `app/graph_agent/langgraph_runner.py` | LangGraph execution: StateGraph, agentic tool loop, trivial short-circuit |
| `app/graph_agent/tool_sets.py` | Default tool sets per vertex type, `request_tools` meta-tool |
| `app/graph_agent/artifact_graph.py` | ArangoDB entity graph: artifacts, deps, impact traversal |
| `app/graph_agent/impact.py` | Impact propagation: extract entities, traverse deps, create validators |
| `shared/common-dto/.../graph/TaskGraphDtos.kt` | KMP DTOs for graph transfer (TaskGraphDto, GraphVertexDto, GraphEdgeDto) |
| `shared/common-api/.../ITaskGraphService.kt` | kRPC interface: `getGraph(taskId)` |
| `backend/server/.../rpc/TaskGraphRpcImpl.kt` | Kotlin RPC impl — calls Python, deserializes with lenient JSON |
| `shared/ui-common/.../chat/TaskGraphComponents.kt` | Compose UI: TaskGraphSection, VertexCard, EdgeRow, StatChip |
