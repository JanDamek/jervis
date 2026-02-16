# Orchestrator — Detailed Technical Reference

> Kompletní referenční dokument pro Python orchestrátor a jeho integraci s Kotlin serverem.
> Základ pro analýzu, rozšiřování a debugging celé orchestrační vrstvy.
> **Automaticky aktualizováno:** 2026-02-14

---

## Agent Selection Strategy

Orchestrátor volí coding agenta na základě **complexity** úkolu a **agent_preference** uživatele:

```python
# backend/service-orchestrator/app/graph/nodes/_helpers.py:192
def select_agent(complexity: Complexity, preference: str = "auto") -> AgentType:
    """Select coding agent based on task complexity."""
    if preference != "auto":
        return AgentType(preference)  # Uživatel explicitně zvolil agenta

    match complexity:
        case Complexity.SIMPLE:     return AgentType.AIDER       # Malé opravy, lokální
        case Complexity.MEDIUM:     return AgentType.OPENHANDS   # Levné zpracování, lokální
        case Complexity.COMPLEX:    return AgentType.OPENHANDS   # Větší analýzy, lokální
        case Complexity.CRITICAL:   return AgentType.CLAUDE      # TOP agent, nejlepší cena/výkon
    return AgentType.CLAUDE  # Fallback na nejlepšího agenta
```

### Agent Profiles

| Agent | Use Case | Provider | Model | API Key Required |
|-------|----------|----------|-------|------------------|
| **Aider** | Malé drobné opravy, rychlé zjištění stavu v kódu | Ollama (GPU) | `qwen3-coder-tool:30b` | ❌ Ne |
| **OpenHands** | Levné zpracování větších věcí a analýz (MEDIUM, COMPLEX) | Ollama (GPU) | `qwen3-coder-tool:30b` | ❌ Ne |
| **Claude** | **TOP agent** pro kritické úkoly (CRITICAL) — nejlepší cena/výkon | Anthropic | `claude-3-5-sonnet-20241022` | ✅ Ano (nebo setup token) |
| **Junie** | **Premium agent** jen pro projekty s explicitním povolením (horší než Claude, dražší) | JetBrains | `claude-3-5-sonnet-20241022` | ✅ Ano (JetBrains účet) |

### Configuration

**Properties:** `backend/server/src/main/resources/application.yml:150-173`
```yaml
coding-tools:
  aider:
    default-provider: ollama
    default-model: qwen3-coder-tool:30b
  openhands:
    default-provider: ollama
    default-model: qwen3-coder-tool:30b
    ollama-base-url: http://192.168.100.117:11434
  junie:
    default-provider: anthropic
    default-model: claude-3-5-sonnet-20241022
  claude:
    default-provider: anthropic
    default-model: claude-3-5-sonnet-20241022
```

**UI Settings:** `shared/ui-common/.../sections/CodingAgentsSettings.kt`
- **Claude:** API key OR setup token (`claude setup-token` pro Max/Pro)
- **Junie:** API key (JetBrains account z https://account.jetbrains.com)
- **Aider/OpenHands:** Žádné nastavení (používají lokální Ollama)

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
14. [Concurrency Control — single-orchestration](#14-concurrency-control--single-orchestration)
15. [Heartbeat a liveness detection](#15-heartbeat-a-liveness-detection)
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
| **Žádné hard timeouty** | Streaming + heartbeat liveness (300s bez tokenu = dead) |
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
│  BackgroundEngine → safety-net polling + heartbeat detection       │
│  KtorRpcServer → /internal/ endpoints (push receivers)             │
│  OrchestratorHeartbeatTracker → in-memory liveness                 │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ REST (HTTP)
┌───────────────────────────▼─────────────────────────────────────────┐
│                   Python Orchestrator (:8090)                       │
│  FastAPI + LangGraph StateGraph + MongoDBSaver                     │
│  LLM Provider (litellm) → Ollama / Anthropic / OpenAI / Gemini    │
│  Job Runner → K8s Jobs (aider, claude, openhands, junie)           │
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
  POST /internal/orchestrator-progress  — node transitions (heartbeat)
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
1. `OrchestratorHeartbeatTracker.updateHeartbeat(taskId)` — aktualizuje liveness
2. Emituje `OrchestratorTaskProgress` event do UI via Flow subscription

Při dokončení/chybě/interruptu:

```python
await kotlin_client.report_status_change(
    task_id=task_id,
    thread_id=thread_id,
    status="done",           # "done" | "error" | "interrupted"
    summary="...",           # pro "done"
    error="...",             # pro "error"
    interrupt_action="...",  # pro "interrupted": "clarify", "commit", "push"
    interrupt_description="...",
)
```

### 2.3 Safety-net polling (sekundární)

`BackgroundEngine.runOrchestratorResultLoop()` — každých 60 sekund:

1. Najde tasks ve stavu `PYTHON_ORCHESTRATING`
2. Zkontroluje `OrchestratorHeartbeatTracker.getLastHeartbeat(taskId)`
3. Pokud heartbeat < 10 minut → OK, čeká
4. Pokud heartbeat > 10 minut → zavolá Python `GET /status/{thread_id}`
5. Pokud Python nedostupný → reset task na `READY_FOR_GPU` (retry)
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
5. Task state → READY_FOR_GPU
     ↓
6. BackgroundEngine.runExecutionLoop() picks up task
     ↓
7. AgentOrchestratorService.run(task, userInput)
   - Path A: task.orchestratorThreadId != null → resumePythonOrchestrator()
   - Path B: new task → dispatchToPythonOrchestrator()
     ↓
8. dispatchToPythonOrchestrator():
   a. Guard: countByState(PYTHON_ORCHESTRATING) == 0
   b. pythonOrchestratorClient.isHealthy() == true
   c. Load project rules, environment, client/project names
   d. ChatHistoryService.prepareChatHistoryPayload(taskId) → chat context
   e. Build OrchestrateRequestDto (includes chat_history)
   f. POST /orchestrate/stream → returns {thread_id, stream_url}
   g. If 429 → return false (orchestrator busy, retry later)
   h. Task state → PYTHON_ORCHESTRATING, save orchestratorThreadId
     ↓
9. Python _run_and_stream():
   a. asyncio.Semaphore acquire
   b. run_orchestration_streaming(request, thread_id)
   c. For each node event → push to SSE queue + kotlin_client.report_progress()
   d. After completion → kotlin_client.report_status_change()
     ↓
10. Kotlin receives push callback:
    - /internal/orchestrator-progress → heartbeat update + UI emit
    - /internal/orchestrator-status → OrchestratorStatusHandler.handleStatusChange()
     ↓
11. OrchestratorStatusHandler:
    - "done" → handleDone():
      a. Emit final response to chat stream
      b. Save ASSISTANT ChatMessageDocument
      c. Check for inline messages (arrived during orchestration)
      d. If inline → re-queue to READY_FOR_GPU (process new messages)
      e. If no inline → DISPATCHED_GPU (terminal)
      f. Async: ChatHistoryService.compressIfNeeded() (non-blocking)
    - "interrupted" → handleInterrupted():
      a. Emit clarification/approval to chat
      b. Save ASSISTANT ChatMessageDocument
      c. Task state → DISPATCHED_GPU (keeps orchestratorThreadId for resume)
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
3. Task state → READY_FOR_GPU
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
8. Python _resume_in_background():
   - Semaphore acquire
   - resume_orchestration_streaming(thread_id, resume_value)
   - Push progress + status to Kotlin
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
    agent_preference: str = "auto"        # "auto" | "aider" | "claude" | "openhands" | "junie"
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
- Kotlin: FOREGROUND → emitne do chatu + DISPATCHED_GPU; BACKGROUND → USER_TASK
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

**Používá se pro**: Meeting summaries, knowledge queries, planning advice, analýzy

**Context building**:
1. Task identity (client/project names)
2. Project context z KB
3. Evidence pack KB results + tracker artifacts
4. **Chat history — plný konverzační kontext** (summary blocks + recent messages)
5. User clarification (pokud proběhla)
6. Environment context

**LLM system prompt**: "You are Jervis, an AI assistant... Use Czech language."

**Agentic tool-use loop** (max 8 iterations): LLM call → tool calls → execute → repeat.
Tools: `web_search`, `kb_search`, `store_knowledge`, `ask_user`, `create_scheduled_task`, + KB stats, git, filesystem, terminal tools.

**ask_user tool**: Pokud agent potřebuje upřesnění od uživatele, zavolá `ask_user(question)`. Executor vyhodí `AskUserInterrupt`, respond node zachytí → volá `interrupt()` → graf se zastaví → uživatel odpoví v chatu → graf pokračuje s odpovědí jako tool result. Viz [§13 Approval Flow](#13-approval-flow--interruptresume-mechanismus).

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
- SIMPLE → Aider
- MEDIUM → Claude
- COMPLEX → OpenHands
- CRITICAL → Junie
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

**Logika**:
1. Pokud `final_result` už nastaveno (respond node) → skip
2. Sestaví kontext: client/project, branch, artifacts, **conversation stats z chat_history**, **key decisions**
3. LLM generuje český souhrn (max 3-5 vět)
4. Fallback: strukturovaný souhrn bez LLM

---

## 8. LLM Provider — model a volání

**Soubor**: `app/llm/provider.py`

### 8.1 Tiery modelů

| Tier | Model | Context | Kdy |
|------|-------|---------|-----|
| `LOCAL_FAST` | `ollama/qwen3-coder-tool:30b` | 8k | Klasifikace, jednoduchý plan |
| `LOCAL_STANDARD` | `ollama/qwen3-coder-tool:30b` | 32k | Standardní úlohy |
| `LOCAL_LARGE` | `ollama/qwen3-coder-tool:30b` | 49k | Max lokální kontext |
| `CLOUD_REASONING` | `anthropic/claude-sonnet-4-5` | - | Architektura, design (auto=anthropic) |
| `CLOUD_CODING` | `openai/gpt-4o` | - | Code editing (auto=openai) |
| `CLOUD_PREMIUM` | `anthropic/claude-opus-4-6` | - | Kritické úlohy |
| `CLOUD_LARGE_CONTEXT` | `google/gemini-2.5-pro` | 1M | Ultra-large context (auto=gemini) |

### 8.2 Escalation policy

```python
def select_local_tier(context_tokens):
    if context_tokens > 32_000: return LOCAL_LARGE
    if context_tokens > 8_000:  return LOCAL_STANDARD
    return LOCAL_FAST
```

### 8.3 Cloud eskalace (`llm_with_cloud_fallback`)

**Soubor**: `app/graph/nodes/_helpers.py`

```
1. context_tokens > 49_000? → rovnou cloud (pokud auto-enabled)
2. Pokus o lokální model (select_local_tier)
3. Lokální selhal → cloud eskalace:
   a. auto_providers neprázdné? → auto-escalate (bez ptaní)
   b. žádný provider auto? → interrupt() (zeptat se usera)
   c. user zamítl? → RuntimeError
```

**Auto-providers**: `rules.auto_use_anthropic/openai/gemini` + explicitní cloud prompt (keywords)

### 8.4 Streaming + heartbeat

```python
async def _iter_with_heartbeat(stream):
    last_token_time = time.monotonic()
    async for chunk in stream:
        now = time.monotonic()
        if now - last_token_time > HEARTBEAT_DEAD_SECONDS:  # 300s
            raise HeartbeatTimeoutError("No tokens for 5 min")
        last_token_time = now
        yield chunk
```

- **Žádné hard timeouty** na LLM call
- Pokud tokeny přicházejí → čeká neomezeně
- Pokud 5 minut bez tokenu → HeartbeatTimeoutError → escalate/retry
- Tool calls (structured output): blocking call (litellm omezení)

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

### 9.3 Runtime KB přístup (MCP)

Coding agenti (Claude) mají runtime přístup přes MCP server:
- `kb_search(query, max_results)` — full-text search
- `kb_search_simple(query)` — jednoduchý search (3 results)
- `kb_traverse(node_id, depth)` — graph traversal
- `kb_graph_search(query, node_types)` — graph node search
- `kb_get_evidence(issue_key)` — evidence pro issue
- `kb_store(content, kind, metadata)` — store new knowledge

---

## 10. K8s Job Runner — spouštění coding agentů

**Soubor**: `app/agents/job_runner.py`

### 10.1 Agent typy a limity

| Agent | Image | Concurrent limit | Timeout |
|-------|-------|-------------------|---------|
| aider | `jervis-aider` | 3 | 600s |
| openhands | `jervis-coding-engine` | 2 | 1800s |
| claude | `jervis-claude` | 2 | 1800s |
| junie | `jervis-junie` | 1 | 1200s |

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
      containers:
        - name: agent
          image: {registry}/jervis-{image_name}:latest
          env:
            - TASK_ID, CLIENT_ID, PROJECT_ID
            - WORKSPACE_PATH (absolut na PVC)
            - OLLAMA_URL, KNOWLEDGEBASE_URL
            - ALLOW_GIT (true/false)
            - ANTHROPIC_API_KEY (pro Claude agenta)
          volumeMounts:
            - /opt/jervis/data (PVC shared s orchestrátorem)
          resources:
            requests: 256Mi memory, 250m CPU
            limits: 1Gi memory, 1000m CPU
      restartPolicy: Never
```

### 10.3 Lifecycle

1. `prepare_workspace()` — zapíše instrukce do `.jervis/`
2. K8s Job create → pod se spustí
3. Agent čte `.jervis/instructions.md`, pracuje v workspace
4. Agent zapíše `.jervis/result.json` s výsledkem
5. Job runner čeká na completion (watch pod logs)
6. Orchestrátor čte `result.json`
7. `cleanup_workspace()` — smaže `.jervis/` a agent-specific soubory

### 10.4 Result format

```json
{
  "success": true,
  "summary": "Implemented feature X...",
  "changedFiles": ["src/main.kt", "src/test.kt"]
}
```

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
└── .aider.conf.yml            # Aider config (Aider only)
```

### 11.2 Claude MCP config

```json
{
  "mcpServers": {
    "jervis-kb": {
      "command": "curl",
      "args": ["-X", "POST", "{kb_url}/api/v1/mcp", ...],
      "env": {"CLIENT_ID": "...", "PROJECT_ID": "..."}
    }
  }
}
```

### 11.3 CLAUDE.md pro coding

Obsahuje:
- Název projektu, klient, popis úlohy
- Forbidden actions: NIKDY nepoužívej git příkazy
- KB tools: 6 nástrojů pro runtime KB přístup
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

- FOREGROUND task: `DISPATCHED_GPU` (keeps `orchestratorThreadId`)
  - Clarification/approval emitováno do chatu jako ASSISTANT message
  - User odpovídá přímo v chatu → task reused → resume
- BACKGROUND task: `USER_TASK` (notification v sidebar)
  - User responds in sidebar → new READY_FOR_GPU

---

## 14. Concurrency Control — single-orchestration

### 14.1 Dvě vrstvy

**Kotlin (early guard)**:
```kotlin
val orchestratingCount = taskRepository.countByState(TaskStateEnum.PYTHON_ORCHESTRATING)
if (orchestratingCount > 0) return false  // skip dispatch
```

**Python (definitive)**:
```python
_orchestration_semaphore = asyncio.Semaphore(1)

if _orchestration_semaphore.locked():
    raise HTTPException(status_code=429, detail="Orchestrator busy")
```

### 14.2 Multi-pod concurrency

**Soubor**: `app/context/distributed_lock.py`

MongoDB distributed lock:
- Collection: `orchestrator_locks`
- Document: `{_id: "orchestration_slot", locked_by: pod_id, thread_id, locked_at}`
- Atomic acquire via `findOneAndUpdate`
- Heartbeat: 10s interval (updates `locked_at`)
- Stale recovery: 300s timeout → auto-release

### 14.3 Důvod omezení

LLM (Ollama) nedokáže efektivně zpracovávat souběžné requesty. Jeden GPU request najednou zajišťuje:
- Předvídatelný výkon
- Žádné OOM na GPU
- Jednodušší debugging

---

## 15. Heartbeat a liveness detection

### 15.1 OrchestratorHeartbeatTracker (Kotlin)

```kotlin
@Service
class OrchestratorHeartbeatTracker {
    private val heartbeats = ConcurrentHashMap<String, Instant>()

    fun updateHeartbeat(taskId: String)     // called from /internal/orchestrator-progress
    fun getLastHeartbeat(taskId: String): Instant?
    fun clearHeartbeat(taskId: String)      // called from /internal/orchestrator-status
}
```

### 15.2 BackgroundEngine liveness check

```
every 60s:
  for each task in PYTHON_ORCHESTRATING:
    lastHeartbeat = heartbeatTracker.getLastHeartbeat(taskId)
    if lastHeartbeat == null:
      // No heartbeat ever received — task just dispatched, wait
      continue
    if Duration.between(lastHeartbeat, now) < 10 minutes:
      // Heartbeat recent — all good
      continue
    // Heartbeat stale → poll Python directly
    try:
      status = pythonClient.getStatus(threadId)
      orchestratorStatusHandler.handleStatusChange(...)
    catch (connectionError):
      // Python unreachable → reset task for retry
      task.state = READY_FOR_GPU
      task.orchestratorThreadId = null
```

### 15.3 LLM heartbeat (Python)

```python
HEARTBEAT_DEAD_SECONDS = 300  # 5 min

async def _iter_with_heartbeat(stream):
    last_token_time = time.monotonic()
    async for chunk in stream:
        elapsed = time.monotonic() - last_token_time
        if elapsed > HEARTBEAT_DEAD_SECONDS:
            raise HeartbeatTimeoutError(...)
        last_token_time = time.monotonic()
        yield chunk
```

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

### 16.4 Použití v nodech

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
- Heartbeat: 300s (5 min bez tokenu = dead)
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
| **Agenti** | 4 coding agenti (Aider/OpenHands/Claude/Junie) | 19+ specialist agentů |
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
- coding: Delegates to coding agents (Aider/OpenHands/Claude/Junie), manages workspace. Domains: code
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
        """LLM calling with tier selection and heartbeat."""

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
| 1 | **CodingAgent** | `code_agent.py` | K8s Job delegace (Aider/OpenHands/Claude/Junie), workspace, results | - |
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
```

| Flag | Default | Effect when True | Effect when False |
|------|---------|-----------------|-------------------|
| `use_delegation_graph` | `False` | `get_orchestrator_graph()` returns 7-node delegation graph | Returns legacy 14-node graph |
| `use_specialist_agents` | `False` | `plan_delegations` selects from 19 registered agents | Routes everything to `LegacyAgent` |
| `use_dag_execution` | `False` | `execute_delegation` uses `DAGExecutor` (parallel) | Sequential execution only |
| `use_procedural_memory` | `False` | `plan_delegations` looks up learned procedures in KB | No procedure lookup |

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
  → OrchestratorHeartbeatTracker.updateHeartbeat(taskId)
  → Emit OrchestratorTaskProgress to UI

POST /internal/orchestrator-status
  Body: { taskId, status, summary, error, interruptAction, interruptDescription, branch, artifacts, threadId }
  → OrchestratorHeartbeatTracker.clearHeartbeat(taskId)
  → OrchestratorStatusHandler.handleStatusChange(...)
  → Emit OrchestratorTaskStatusChange to UI

POST /internal/correction-progress
  Body: { meetingId, phase, chunkIndex, totalChunks, segmentsProcessed, totalSegments, message }
  → CorrectionHeartbeatTracker.updateHeartbeat(meetingId)
  → Emit notification to UI
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
        // 1. Guard: PYTHON_ORCHESTRATING count == 0
        // 2. isHealthy()
        // 3. Load rules, environment, names, chat history
        // 4. Build OrchestrateRequestDto
        // 5. POST /orchestrate/stream → get thread_id
        // 6. Save PYTHON_ORCHESTRATING + orchestratorThreadId

    private suspend fun resumePythonOrchestrator(...):
        // Distinguish clarification vs approval
        // POST /approve/{thread_id}
        // Save PYTHON_ORCHESTRATING
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
            "running"     → no-op (heartbeat handles liveness)
            "interrupted" → handleInterrupted(task, action, description)
            "done"        → handleDone(task, summary)
            "error"       → handleError(task, error)

    private suspend fun handleInterrupted(task, action, description):
        // FOREGROUND: emit to chat stream + save ASSISTANT message + DISPATCHED_GPU
        // BACKGROUND: create USER_TASK notification

    private suspend fun handleDone(task, summary):
        // FOREGROUND: emit response + save ASSISTANT message
        // Check inline messages (arrived during orchestration)
        //   → if yes: re-queue to READY_FOR_GPU
        //   → if no: DISPATCHED_GPU (terminal)
        // BACKGROUND: delete task after completion
        // Async: chatHistoryService.compressIfNeeded()

    private suspend fun handleError(task, error):
        // Emit error + save error message
        // Create USER_TASK + set ERROR state
}
```

### 26.5 BackgroundEngine (relevantní loops)

```kotlin
// Execution loop (GPU): picks up READY_FOR_GPU tasks
private suspend fun runExecutionLoop():
    while (true):
        delay(executionIntervalMs)     // configurable polling interval
        task = findNextGpuTask()       // respects preemption
        if task != null:
            agentOrchestratorService.run(task, task.content)

// Orchestrator result loop: safety-net for PYTHON_ORCHESTRATING
private suspend fun runOrchestratorResultLoop():
    while (true):
        delay(60_000)                  // 60s interval
        tasks = findAll(PYTHON_ORCHESTRATING)
        for task in tasks:
            checkOrchestratorTaskStatus(task)
            // → heartbeat check → Python poll → status handler
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
        "aider": 600, "openhands": 1800,
        "claude": 1800, "junie": 1200,
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

    # Session memory
    session_memory_ttl_days: int = 7
    session_memory_max_entries: int = 50
```

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
AgentType        # aider, openhands, claude, junie
Complexity       # simple, medium, complex, critical
ModelTier        # local_fast/standard/large, cloud_reasoning/coding/premium/large_context
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
│   ├── main.py                          # FastAPI app, endpoints, SSE, concurrency
│   ├── config.py                        # Environment-based configuration (+feature flags)
│   ├── models.py                        # Pydantic models (ALL data structures + delegation models)
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
│   │   ├── provider.py                  # LLM abstraction (litellm), streaming, heartbeat
│   │   └── gpu_router.py               # GPU routing (announce/release)
│   ├── agents/
│   │   ├── __init__.py
│   │   ├── base.py                      # NEW: BaseAgent abstract class, agentic loop
│   │   ├── registry.py                  # NEW: AgentRegistry singleton
│   │   ├── legacy_agent.py              # NEW: Wrapper of existing 14-node logic (fallback)
│   │   ├── job_runner.py                # K8s Job creation, log streaming, result reading
│   │   ├── workspace_manager.py         # .jervis/ files, CLAUDE.md, MCP, Aider config
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
│   │   └── kotlin_client.py             # Push client (progress, status → Kotlin) (+delegation fields)
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
│   │   └── OrchestratorHeartbeatTracker.kt  # In-memory liveness
│   ├── background/
│   │   └── BackgroundEngine.kt          # 4 loops: qualification, execution, scheduler, result
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
2. Dispatch to coding agent (aider/openhands) for deeper exploration
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
- **`lqm.py`** — Local Quick Memory: 3-layer RAM cache (hot affairs dict, async write buffer queue, LRU warm cache with TTL)
- **`context_switch.py`** — LLM-based context switch detection (Czech prompt, confidence threshold 0.7)
- **`affairs.py`** — Affair lifecycle: create, park (with LLM summarization), resume, resolve, load from KB
- **`composer.py`** — Token-budgeted context composition (40% active affair, 10% parked, 15% user context)
- **`agent.py`** — `MemoryAgent` facade; process-global LQM singleton, per-orchestration agent instances

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
