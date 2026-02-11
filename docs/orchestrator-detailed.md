# Orchestrator — Detailed Technical Reference

> Kompletní referenční dokument pro Python orchestrátor a jeho integraci s Kotlin serverem.
> Základ pro analýzu, rozšiřování a debugging celé orchestrační vrstvy.
> **Automaticky aktualizováno:** 2026-02-11

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

1. [Přehled systému](#1-přehled-systému)
2. [Architektura komunikace](#2-architektura-komunikace)
3. [Životní cyklus úlohy — kompletní flow](#3-životní-cyklus-úlohy--kompletní-flow)
4. [OrchestrateRequest — vstupní data](#4-orchestraterequest--vstupní-data)
5. [LangGraph StateGraph — graf orchestrace](#5-langgraph-stategraph--graf-orchestrace)
6. [OrchestratorState — kompletní stav](#6-orchestratorstate--kompletní-stav)
7. [Nodes — detailní popis každého uzlu](#7-nodes--detailní-popis-každého-uzlu)
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
18. [Kotlin integrace — kompletní API](#18-kotlin-integrace--kompletní-api)
19. [Konfigurace a deployment](#19-konfigurace-a-deployment)
20. [Datové modely — kompletní referenční seznam](#20-datové-modely--kompletní-referenční-seznam)
21. [Souborová mapa](#21-souborová-mapa)

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

## 5. LangGraph StateGraph — graf orchestrace

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

    # --- Clarification (from intake interrupt/resume) ---
    project_context: str | None         # KB project context (fetched in intake)
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
```

---

## 7. Nodes — detailní popis každého uzlu

### 7.1 intake

**Soubor**: `app/graph/nodes/intake.py`
**Účel**: Klasifikace úlohy, detekce intentu, povinná klarifikace

**Kroky**:
1. Fetch project context z KB (`fetch_project_context`)
2. Build environment summary (pokud `state.environment` existuje)
3. Detekce cloud promptu (`detect_cloud_prompt` — keywords "use cloud", "použi cloud" atd.)
4. Build context section — client/project names + KB context
5. Recent conversation context (posledních 5 zpráv z `chat_history` pro klasifikaci)
6. LLM structured output — JSON s klasifikací

**LLM prompt vyžaduje**:
```json
{
  "task_category": "advice|single_task|epic|generative",
  "task_action": "respond|code|tracker_ops|mixed",
  "external_refs": ["UFO-24"],
  "complexity": "simple|medium|complex|critical",
  "goal_clear": true,
  "clarification_questions": [...],
  "reasoning": "..."
}
```

**Povinná klarifikace**: Pokud `goal_clear == false` a `clarification_questions` neprázdné:
- Vytvoří `ClarificationQuestion` objekty
- Zavolá `interrupt()` — graf se zastaví
- Python pushne `status=interrupted, action=clarify`
- Kotlin: FOREGROUND → emitne do chatu + DISPATCHED_GPU; BACKGROUND → USER_TASK
- Po resume: `clarification_response` obsahuje user's answers

**Output**: `task_category`, `task_action`, `external_refs`, `task_complexity`, `project_context`, `allow_cloud_prompt`, `needs_clarification`

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

**`fetch_project_context()`** — pro orchestrátor (intake, decompose):
1. Project structure via graph search (files + classes)
2. Architecture & modules (5 results, graph expansion)
3. Coding conventions (3 results, client-level)
4. Task-relevant context (5 results, confidence 0.6, graph expansion)

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

## 18. Kotlin integrace — kompletní API

### 18.1 PythonOrchestratorClient (Kotlin → Python)

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

### 18.2 Internal endpoints (Python → Kotlin push)

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

### 18.3 AgentOrchestratorService

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

### 18.4 OrchestratorStatusHandler

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

### 18.5 BackgroundEngine (relevantní loops)

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

## 19. Konfigurace a deployment

### 19.1 Python config (`app/config.py`)

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
```

### 19.2 K8s Deployment

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

### 19.3 RBAC

```yaml
# k8s/orchestrator-rbac.yaml
ServiceAccount: jervis-orchestrator
Role: jervis-orchestrator-role
  - resources: [jobs, pods, pods/log]
    verbs: [get, list, watch, create, delete]
RoleBinding: jervis-orchestrator → jervis-orchestrator-role
```

### 19.4 Build & Deploy

```bash
k8s/build_orchestrator.sh
# → Docker build --platform linux/amd64
# → Docker push registry.damek-soft.eu/jandamek/jervis-orchestrator:v{N}
# → kubectl apply -f k8s/orchestrator-rbac.yaml
# → kubectl apply -f k8s/app_orchestrator.yaml
# → kubectl set image deployment/jervis-orchestrator ...
```

---

## 20. Datové modely — kompletní referenční seznam

### 20.1 Python modely (`app/models.py`)

```python
# Enums
AgentType        # aider, openhands, claude, junie
Complexity       # simple, medium, complex, critical
ModelTier        # local_fast/standard/large, cloud_reasoning/coding/premium/large_context
TaskCategory     # advice, single_task, epic, generative
TaskAction       # respond, code, tracker_ops, mixed
StepType         # respond, code, tracker
RiskLevel        # LOW, MEDIUM, HIGH, CRITICAL

# Core models
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
```

### 20.2 Kotlin DTOs (`PythonOrchestratorClient.kt`)

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

### 20.3 MongoDB kolekce (orchestrátor-related)

| Kolekce | Účel | Indexy |
|---------|------|--------|
| `jervis_checkpoints.*` | LangGraph graph state | thread_id |
| `orchestrator_context` | Hierarchické context store | (task_id, scope, scope_key), TTL 30d |
| `orchestrator_locks` | Distributed lock | _id = "orchestration_slot" |
| `chat_messages` | Jednotlivé zprávy | (taskId, sequence), taskId, correlationId |
| `chat_summaries` | Komprimované souhrny | (taskId, sequenceEnd), taskId |
| `tasks` | TaskDocument lifecycle | state, clientId, projectId, type |

---

## 21. Souborová mapa

### Python orchestrátor

```
backend/service-orchestrator/
├── app/
│   ├── main.py                          # FastAPI app, endpoints, SSE, concurrency
│   ├── config.py                        # Environment-based configuration
│   ├── models.py                        # Pydantic models (ALL data structures)
│   ├── graph/
│   │   ├── orchestrator.py              # LangGraph StateGraph, state, routing, streaming
│   │   └── nodes/
│   │       ├── __init__.py              # Re-exports all nodes
│   │       ├── _helpers.py              # LLM wrapper, JSON parsing, cloud escalation
│   │       ├── intake.py                # Classification, clarification
│   │       ├── evidence.py              # KB + tracker artifact fetch
│   │       ├── respond.py               # Direct answers (ADVICE + SINGLE_TASK/respond)
│   │       ├── plan.py                  # SINGLE_TASK planning (respond/code/tracker/mixed)
│   │       ├── execute.py               # Step execution (respond/code/tracker dispatch)
│   │       ├── evaluate.py              # Result evaluation, routing, step/goal advancement
│   │       ├── git_ops.py               # Git commit/push with approval gates
│   │       ├── finalize.py              # Final report generation
│   │       ├── coding.py                # Decompose, select_goal, plan_steps
│   │       ├── epic.py                  # EPIC planning + wave execution (Phase 3)
│   │       └── design.py                # GENERATIVE design (Phase 3)
│   ├── llm/
│   │   └── provider.py                  # LLM abstraction (litellm), streaming, heartbeat
│   ├── agents/
│   │   ├── job_runner.py                # K8s Job creation, log streaming, result reading
│   │   └── workspace_manager.py         # .jervis/ files, CLAUDE.md, MCP, Aider config
│   ├── context/
│   │   ├── context_store.py             # MongoDB hierarchical context store
│   │   ├── context_assembler.py         # Per-node LLM context assembly
│   │   └── distributed_lock.py          # MongoDB distributed lock
│   ├── kb/
│   │   └── prefetch.py                  # KB context pre-fetch for agents and orchestrator
│   ├── tools/
│   │   └── kotlin_client.py             # Push client (progress, status → Kotlin)
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
