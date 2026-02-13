# Finální zadání – Python Orchestrator, K8s Jobs, MCP KB

**Datum:** 2026-02-07
**Vstup:** `docs/orchestrator-analysis.md` (18 sekcí, rev.7)
**Účel:** Konsolidovaná specifikace bez nekonzistencí + implementační plán

---

## 1. Vyřešené nekonzistence z analýzy

| Nekonzistence | Sekce | Rozhodnutí |
|---------------|-------|------------|
| Sekce 15 doporučuje Deployment replicas:0 + AgentScaler | 15 vs 18 | **K8s Jobs** (sekce 18 je finální) |
| Sekce 18.3 říká "agent sám rozhodne o commit" | 18.3 vs 18.6 | **Orchestrator rozhoduje**, agent vykonává (18.6 je finální) |
| Sekce 13 navrhuje service-claude-code jako Python WebSocket microservice | 13 vs 18 | **K8s Job** s dual-mode entrypoint (18 je finální) |
| Sekce 10 ukazuje kRPC WebSocket ke coding services | 10 vs 18 | **Filesystem + K8s API** (18 je finální – žádný kRPC pro Jobs) |
| Sekce 15 uvádí CodingTaskQueue s kRPC WebSocket | 15 vs 18 | **Jobs nepotřebují frontu** – K8s limit souběžných Jobů |

---

## 2. Architektonické principy (finální, neměnné)

1. **Orchestrator = mozek, agent = ruka** – žádný agent se neřídí sám
2. **Orchestrator centrálně řídí** na základě pravidel klienta/projektu (`ProjectRules`)
3. **Git operace**: orchestrator ROZHODUJE (kdy, za jakých podmínek), agent VYKONÁVÁ (commit, push)
4. **Dva režimy agenta**: CODING (ALLOW_GIT=false, default) a GIT DELEGACE (ALLOW_GIT=true)
5. **Workspace připravený serverem** – codebase na shared PVC, orchestrator doplní instrukce/kontext
6. **Agent skončí sám** – `activeDeadlineSeconds` jen jako safety timeout
7. **MCP nativně pro Claude Code**, pre-fetch pro ostatní agenty
8. **K8s Jobs** pro coding agenty (ne Deploymenty)

---

## 3. Implementační scope (co se implementuje TEĎ)

### 3.1 Nové adresáře

```
backend/
  service-orchestrator/           # Python orchestrator (KB-first architecture)
    app/
      __init__.py
      main.py                     # FastAPI + SSE endpoints
      config.py                   # Konfigurace (env vars)
      models.py                   # Pydantic modely (TaskCategory, TaskAction, EvidencePack, ...)
      graph/
        __init__.py
        orchestrator.py           # LangGraph StateGraph – 4-category routing, OrchestratorState
        nodes/                    # Modular node files (one per concern)
          __init__.py             # Re-exports
          _helpers.py             # Shared LLM helpers, JSON parsing, cloud escalation
          intake.py               # Intent detection, 4-category classification, mandatory clarification
          evidence.py             # Parallel KB + tracker artifact fetch (EvidencePack)
          respond.py              # ADVICE + analytical response (LLM + KB)
          plan.py                 # Multi-type planning (respond/code/tracker/mixed)
          execute.py              # Step dispatch by type (respond/code/tracker)
          evaluate.py             # Evaluation + routing (next_step, advance_step, advance_goal)
          git_ops.py              # Git operations with approval gates
          finalize.py             # Final report generation
          coding.py               # Coding pipeline (decompose, select_goal, plan_steps)
          epic.py                 # EPIC: plan_epic, execute_wave, verify_wave
          design.py               # GENERATIVE: design (epic generation from goal)
      context/
        __init__.py
        context_store.py          # MongoDB orchestrator_context (hierarchical, TTL 30d)
        agent_result_parser.py    # Normalize variable agent responses
        context_assembler.py      # Per-node LLM context assembly (step/goal/epic)
        distributed_lock.py       # MongoDB distributed lock for multi-pod
      agents/
        __init__.py
        job_runner.py             # K8s Job CRUD + log streaming
        workspace_manager.py      # Příprava workspace (instrukce, KB, MCP)
      kb/
        __init__.py
        prefetch.py               # Pre-fetch KB kontext pro agenty
      llm/
        __init__.py
        provider.py               # litellm wrapper + EscalationPolicy
      tools/
        __init__.py
        definitions.py            # Tool schemas (web_search, kb_search)
        executor.py               # Tool execution (SearXNG, KB retrieve)
        kotlin_client.py          # REST client pro Kotlin server internal API
      whisper/
        correction_agent.py       # Transcript correction agent (shares Ollama GPU)
    requirements.txt
    Dockerfile

  service-kb-mcp/                 # NOVÝ – MCP server pro KB (stdio)
    server.py                     # MCP tools: kb_search, kb_traverse, kb_store, ...
    requirements.txt

  shared-entrypoints/             # NOVÝ – sdílené entrypointy pro Docker images
    entrypoint-job.sh             # Job mode entrypoint
```

### 3.2 Hlavní komponenty

#### A. Python Orchestrator (`service-orchestrator/`)

FastAPI server s LangGraph StateGraph. Přijímá requesty z Kotlin serveru, řídí
coding workflow, streamuje progress přes SSE.

**API endpointy:**
```
POST /orchestrate            # Spustí orchestraci (blocking – čeká na výsledek)
POST /orchestrate/stream     # Spustí orchestraci (fire-and-forget – vrátí thread_id ihned)
GET  /status/{thread_id}     # Status polling (running/interrupted/done/error)
GET  /stream/{thread_id}     # SSE stream progress
POST /approve/{thread_id}    # Approval response od uživatele (resume z interrupt)
POST /cancel/{thread_id}     # Cancel running orchestration (asyncio task cancellation)
GET  /health                 # Health check
```

**Cancellation flow:**
- UI → Kotlin RPC `cancelOrchestration(taskId)` → Kotlin resolves `orchestratorThreadId` from TaskDocument
- Kotlin → Python `POST /cancel/{thread_id}` → Python cancels asyncio task, pushes error status back to Kotlin
- Python reports `status="error"` with `error="Orchestrace zrušena uživatelem"` via push callback

**Recursion limit:** `recursion_limit=150` in all LangGraph config dicts (default 25 is too low for 5+ goals with multi-step execution).

**Komunikační model (push-based, Python → Kotlin callbacks):**
- Kotlin → Python: `POST /orchestrate/stream` s kompletním `OrchestrateRequest`
  (rules, workspace, task info – vše upfront, `task_id` = MongoDB ObjectId z `TaskDocument._id`)
- Python → Kotlin: **push-based callbacky** (`kotlin_client.py`):
  - `POST /internal/orchestrator-progress` — node transition events (heartbeat pro liveness detection)
  - `POST /internal/orchestrator-status` — completion/error/interrupt + task state transition
- Safety-net: Kotlin polls `GET /status/{thread_id}` z `BackgroundEngine.runOrchestratorResultLoop()` každých 60s
- Interrupts: clarification (pre-planning questions) + approval (commit/push) → USER_TASK → user odpovídá → `POST /approve/{thread_id}`

**LangGraph StateGraph (KB-First Architecture, 14 nodes):**

4 task categories with intelligent routing:

| Category | Examples | Flow |
|----------|----------|------|
| **ADVICE** | "napiš odpověď", "shrň meeting" | intake → evidence → respond → finalize → END |
| **SINGLE_TASK** | "vyřeš UFO-24", "naplánuj opravu" | intake → evidence → plan → execute/respond → finalize → END |
| **EPIC** | "vezmi celý epic, implementuj po dávkách" | intake → evidence → plan_epic → select_goal → ... → finalize → END |
| **GENERATIVE** | "navrhni epiky + tasky + implementuj" | intake → evidence → design → select_goal → ... → finalize → END |

```
[intake] → [evidence_pack] ─┬─ ADVICE ──────→ [respond] ──────────────────────→ [finalize] → END
                              ├─ SINGLE_TASK ──→ [plan] → [execute_step loop] ─→ [finalize] → END
                              ├─ EPIC ─────────→ [plan_epic] → [select_goal] → ...
                              └─ GENERATIVE ───→ [design] → [select_goal] → ...

Coding execution loop (shared by SINGLE_TASK/code, EPIC, GENERATIVE):
  [select_goal] → [plan_steps] → [execute_step] → [evaluate]
       ↑                                              │
       │                                         [next_step]
       │                                              │
       └── [advance_goal] ←── more goals ─────────────┤
                                                      │
                               [advance_step] ← more steps
                                                      │
                                                      ↓ (all done)
                                           [git_operations] → [finalize]
```

`intake` classifies tasks into 4 categories + detects mandatory clarification.
`evidence_pack` fetches KB context and tracker artifacts in parallel.
`respond` handles ADVICE and analytical SINGLE_TASK responses directly (LLM + KB, no K8s Jobs).
`plan` handles SINGLE_TASK with step types: respond, code, tracker, mixed.
`plan_epic` fetches tracker issues, builds waves, and gates with interrupt() approval.
`design` generates a full epic structure from a high-level goal, then gates with interrupt().

**Modular node files** (`app/graph/nodes/`):
```
app/graph/nodes/
  __init__.py          # Re-exports
  _helpers.py          # Shared LLM helpers, JSON parsing, cloud escalation
  intake.py            # 4-category classification, mandatory clarification
  evidence.py          # KB + tracker artifact fetch
  respond.py           # ADVICE + analytical response
  plan.py              # Multi-type planning (respond/code/tracker/mixed)
  execute.py           # Step dispatch (respond/code/tracker)
  evaluate.py          # Evaluation + routing (next_step, advance_step, advance_goal)
  git_ops.py           # Git operations with approval gates
  finalize.py          # Final report
  coding.py            # Coding pipeline (decompose, select_goal, plan_steps)
  epic.py              # EPIC: plan_epic, execute_wave, verify_wave
  design.py            # GENERATIVE: design (epic generation)
```

**Context management** (`app/context/`):
```
app/context/
  __init__.py
  context_store.py          # MongoDB orchestrator_context collection (hierarchical)
  agent_result_parser.py    # Normalize variable agent responses
  context_assembler.py      # Build per-node LLM context (step/goal/epic levels)
  distributed_lock.py       # MongoDB distributed lock for multi-pod concurrency
```

**State model:**
```python
class OrchestratorState(TypedDict, total=False):
    # Core task data
    task: dict               # CodingTask serialized
    rules: dict              # ProjectRules serialized
    environment: dict | None # Resolved K8s environment context
    jervis_project_id: str | None  # JERVIS internal project for tracker ops

    # Intake (4-category routing)
    task_category: str | None       # TaskCategory: advice/single_task/epic/generative
    task_action: str | None         # TaskAction: respond/code/tracker_ops/mixed
    external_refs: list | None      # Extracted ticket IDs, URLs
    evidence_pack: dict | None      # EvidencePack from evidence node
    needs_clarification: bool

    # Clarification
    clarification_questions: list | None
    clarification_response: dict | None
    project_context: str | None
    task_complexity: str | None
    allow_cloud_prompt: bool

    # Goals & steps
    goals: list              # list[Goal]
    current_goal_index: int
    steps: list              # list[CodingStep] (now with step_type: respond/code/tracker)
    current_step_index: int
    step_results: list       # list[StepResult]
    goal_summaries: list     # list[GoalSummary] – cross-goal context

    # Results
    branch: str | None
    final_result: str | None
    artifacts: list
    error: str | None
    evaluation: dict | None

    # --- Delegation system (multi-agent, opt-in via feature flag) ---
    # See section 13 for details
    execution_plan: dict | None
    delegation_states: dict
    active_delegation_id: str | None
    completed_delegations: list
    delegation_results: dict
    response_language: str
    domain: str | None
    session_memory: list
    _delegation_outputs: list
```

**JERVIS Internal Project:**

Each client has max 1 project with `isJervisInternal = true` — an internal workspace for orchestrator planning (tracker, wiki). Auto-created on first orchestration via `ProjectService.getOrCreateJervisProject()`. The project ID is passed to Python as `jervis_project_id` in `OrchestrateRequestDto`.

**Hierarchical context management:**

| Level | What LLM sees | Source | Max size |
|-------|---------------|--------|----------|
| Step (current) | Full instructions + KB context + prev step summary | State + KB | ~8k tokens |
| Goal (current) | Step names + status + 1 sentence per step | State (goal_summaries) | ~500 chars |
| Epic (all) | Only goal names + status (PENDING/DONE/FAIL) | State (goals list) | ~50 chars/goal |

Operational context (plans, results, summaries) stored in MongoDB `orchestrator_context` collection. Valuable insights (analyses, architectural decisions) also saved to KB for semantic search in future tasks.

**Multi-pod support:**

MongoDB distributed lock (`orchestrator_locks` collection) replaces `asyncio.Semaphore` for multi-pod deployments. Lock acquired atomically via `findOneAndUpdate`, heartbeat every 10s, stale recovery after 5 min.

#### B. K8s Job Runner (`agents/job_runner.py`)

Vytváří K8s Jobs pro coding agenty, streamuje logy, čeká na dokončení.

**Klíčové funkce:**
- `run_coding_agent(task_id, agent_type, workspace, instructions, allow_git=False)`
- `stream_job_logs(job_name, callback)`
- `wait_for_job(job_name, timeout)`
- `count_running_jobs(agent_type)`
- `build_job_manifest(...)`

#### C. Workspace Manager (`agents/workspace_manager.py`)

Doplní instrukce, KB kontext a agent-specifickou konfiguraci do existujícího workspace.

**Klíčové funkce:**
- `prepare_workspace(task_id, client_id, project_id, workspace, instructions, agent_type, kb_context)`
- `prepare_git_workspace(workspace_path, client_id, project_id)` – CLAUDE.md pro git delegaci
- `_setup_claude_workspace(workspace, client_id, project_id, kb_context)` – MCP + CLAUDE.md (FORBID git)
- `_setup_claude_git_workspace(workspace, client_id, project_id)` – CLAUDE.md pro ALLOW_GIT=true
- `_setup_aider_workspace(workspace, files, kb_context)` – .aider.conf.yml
- `cleanup_workspace(workspace)` – smaže .jervis/ soubory

#### D. MCP Server pro KB (`service-kb-mcp/`)

Stdio MCP server volatelný z Claude Code. Přeposílá dotazy na KB service.

**MCP Tools:**
- `kb_search(query, scope?, max_results?)` – hybrid search
- `kb_search_simple(query, max_results?)` – RAG only
- `kb_traverse(start_node, direction?, max_hops?)` – graph traversal
- `kb_graph_search(query, node_type?, limit?)` – node search
- `kb_get_evidence(node_key)` – supporting chunks
- `kb_resolve_alias(alias)` – entity resolution
- `kb_store(content, kind, source_urn?, metadata?)` – write (controlled)

#### E. Dual-mode Entrypoint (`shared-entrypoints/entrypoint-job.sh`)

Shell skript pro Job mode. Čte instrukce z `.jervis/`, spustí agenta, zapíše `result.json`.

**Dva režimy:**
- `ALLOW_GIT=false` (default) – agent mění kód, žádný git
- `ALLOW_GIT=true` – agent smí git operace (commit, push) dle instrukcí

#### F. KB Pre-fetch (`kb/prefetch.py`)

Orchestrator před spuštěním agenta dotáže KB a vrátí kontext jako markdown.

**Timeout configuration:**
- HTTP timeout: `120.0` seconds (2 minutes) for all KB queries
- Rationale: KB operations involve Ollama embeddings and graph traversal, which can take significant time especially during high load
- Queries: `prefetch_kb_context()` and `fetch_project_context()` use `httpx.AsyncClient(timeout=120.0)`

---

## 4. Data modely

```python
# models.py

class TaskCategory(str, Enum):
    """4 task categories for intelligent routing."""
    ADVICE = "advice"           # Direct answer (LLM + KB)
    SINGLE_TASK = "single_task" # May or may not code
    EPIC = "epic"               # Batch execution in waves
    GENERATIVE = "generative"   # Design + approve + execute

class TaskAction(str, Enum):
    """What SINGLE_TASK needs to resolve."""
    RESPOND = "respond"         # Answer/analysis (LLM + KB)
    CODE = "code"               # Coding agent
    TRACKER_OPS = "tracker_ops" # Create/update issues
    MIXED = "mixed"             # Combination

class StepType(str, Enum):
    """What a step does."""
    RESPOND = "respond"         # LLM answer
    CODE = "code"               # K8s Job
    TRACKER = "tracker"         # Kotlin tracker API

class EvidencePack(BaseModel):
    """Collected evidence from KB + tracker + refs."""
    kb_results: list[dict] = []
    tracker_artifacts: list[dict] = []
    chat_history_summary: str = ""
    external_refs: list[str] = []
    facts: list[str] = []
    unknowns: list[str] = []

class ProjectRules(BaseModel):
    """Pravidla klienta/projektu – načtená z DB."""
    branch_naming: str = "task/{taskId}"
    commit_prefix: str = "task({taskId}):"
    require_review: bool = False
    require_tests: bool = False
    require_approval_commit: bool = True
    require_approval_push: bool = True
    allowed_branches: list[str] = ["task/*", "fix/*"]
    forbidden_files: list[str] = ["*.env", "secrets/*"]
    max_changed_files: int = 20
    auto_push: bool = False
    auto_use_anthropic: bool = False
    auto_use_openai: bool = False
    auto_use_gemini: bool = False

class CodingTask(BaseModel):
    """Task pro orchestrator."""
    id: str
    client_id: str
    project_id: str | None = None
    workspace_path: str
    query: str
    agent_preference: str = "auto"

class OrchestrateRequest(BaseModel):
    """Request z Kotlin serveru."""
    task_id: str
    client_id: str
    project_id: str | None = None
    workspace_path: str
    query: str
    agent_preference: str = "auto"
    rules: ProjectRules = ProjectRules()
    environment: dict | None = None
    jervis_project_id: str | None = None  # JERVIS internal project

class Goal(BaseModel):
    """Jeden cíl rozložený z user query."""
    id: str
    title: str
    description: str
    complexity: Complexity  # simple/medium/complex/critical
    dependencies: list[str] = []

class CodingStep(BaseModel):
    """Jeden krok plánu — supports multiple step types."""
    index: int
    instructions: str
    agent_type: str = "claude"
    files: list[str] = []
    step_type: StepType = StepType.CODE
    tracker_operations: list[dict] = []

class StepResult(BaseModel):
    """Výsledek jednoho kroku."""
    step_index: int
    success: bool
    summary: str
    agent_type: str
    changed_files: list[str] = []

class ApprovalRequest(BaseModel):
    """Žádost o schválení od uživatele."""
    action_type: str   # "commit" | "push" | "epic_plan" | "generative_design" | ...
    description: str
    details: dict = {}
    risk_level: str = "MEDIUM"
```

---

## 5. Implementační plán – pořadí

### Fáze 1: Kostra orchestratoru
1. `service-orchestrator/` – FastAPI skeleton, config, health check
2. `models.py` – Pydantic modely
3. `llm/provider.py` – litellm wrapper (Ollama + Anthropic)
4. `tools/kotlin_client.py` – REST client base

### Fáze 2: Core workflow
5. `graph/orchestrator.py` – LangGraph StateGraph
6. `graph/nodes.py` – clarify, decompose, select_goal, plan_steps, execute_step, evaluate, advance_step, advance_goal, git_operations, report (10 nodes)
7. `agents/workspace_manager.py` – příprava workspace
8. `agents/job_runner.py` – K8s Job CRUD + log streaming

### Fáze 3: KB integrace
9. `kb/prefetch.py` – pre-fetch KB kontext
10. `service-kb-mcp/server.py` – MCP server pro Claude Code

### Fáze 4: Entrypoints
11. `shared-entrypoints/entrypoint-job.sh` – dual-mode entrypoint
12. Dokumentace (update orchestrator-analysis.md)

---

## 6. Závislosti (Python)

```
# requirements.txt (service-orchestrator)
fastapi>=0.115.0
uvicorn>=0.34.0
langgraph>=0.4.0
langgraph-checkpoint-mongodb>=2.0.0
langchain-core>=0.3.0
litellm>=1.60.0
httpx>=0.28.0
kubernetes>=31.0.0
motor>=3.6.0
pydantic>=2.10.0
pydantic-settings>=2.7.0
sse-starlette>=2.2.0

# requirements.txt (service-kb-mcp)
mcp>=1.0.0
httpx>=0.28.0
```

---

## 7. K8s RBAC

Orchestrator potřebuje přístup k K8s API pro Jobs:

```yaml
# Permissions:
- apiGroups: ["batch"]
  resources: ["jobs"]
  verbs: ["create", "get", "list", "delete"]
- apiGroups: [""]
  resources: ["pods", "pods/log"]
  verbs: ["get", "list"]
- apiGroups: [""]
  resources: ["configmaps"]
  verbs: ["create", "delete"]
```

### 7.1 Konfigurace a environment variables

```
Env var               Odkud                 K čemu
─────────────────────────────────────────────────────────────────────────
ORCHESTRATOR_PORT     app_orchestrator.yaml  FastAPI port (8090)
MONGODB_URL           jervis-secrets         Persistent checkpointer (AsyncMongoDBSaver)
KOTLIN_SERVER_URL     app_orchestrator.yaml  REST API Kotlin serveru (http://jervis-server:5500)
KNOWLEDGEBASE_URL     app_orchestrator.yaml  KB service (http://jervis-knowledgebase:8080)
SEARXNG_URL           app_orchestrator.yaml  Web search SearXNG (http://192.168.100.117:30053)
K8S_NAMESPACE         app_orchestrator.yaml  Namespace pro K8s Jobs (jervis)
DATA_ROOT             app_orchestrator.yaml  Sdílený PVC (/opt/jervis/data)
OLLAMA_URL            app_orchestrator.yaml  Lokální LLM (http://192.168.100.117:11434)
ANTHROPIC_API_KEY     jervis-secrets         Cloud LLM pro orchestrátor (critical/complex tasks)
GOOGLE_API_KEY        jervis-secrets         Gemini pro ultra-large context (>49k tokenů, až 1M)
CONTAINER_REGISTRY    app_orchestrator.yaml  Registry pro Job images

Jobs (injektované z jervis-secrets do K8s Jobs, NE do orchestrátoru):
CLAUDE_CODE_OAUTH_TOKEN  jervis-secrets      Max účet OAuth – Claude Code CLI preferuje tento
ANTHROPIC_API_KEY        jervis-secrets      Pay-per-token pro Claude CLI + Aider/OpenHands
```

**Autentizace – tři klíče, různé účely:**

| Secret | Co to je | Kdo ho používá |
|--------|----------|----------------|
| `CLAUDE_CODE_OAUTH_TOKEN` | **Max účet** (OAuth) | Claude Code CLI v K8s Jobs – preferovaný auth |
| `ANTHROPIC_API_KEY` | **API klíč** (pay-per-token) | Orchestrátor (cloud tiers) + Job auth pokud chybí OAuth |
| `GOOGLE_API_KEY` | **Gemini API** | Orchestrátor – CLOUD_LARGE_CONTEXT tier (>49k tokenů) |

**Orchestrátorova vlastní logika** (`llm/provider.py`):
- Clarify, decompose + plan běží na **Ollama** (LOCAL_FAST / LOCAL_STANDARD / LOCAL_LARGE)
- Clarify node vždy LOCAL_STANDARD — je to lehký triage krok, nepotřebuje cloud
- Cloud modely **NEJSOU failure fallback** pro lokální modely
- Cloud se použije JEN pro legitimní potřeby:
  - Ultra-large context (>49k tokenů) → **Gemini** (CLOUD_LARGE_CONTEXT, až 1M tokenů)
  - Critical architecture/design → **Anthropic** (CLOUD_REASONING / CLOUD_PREMIUM)
  - Critical code changes → **Anthropic** (CLOUD_CODING)
  - Explicitní user preference "quality" → CLOUD_REASONING
- Gemini je **naprostá nezbytnost** – jen když lokální kontext nestačí

**K8s Jobs pro coding agenty** (`agents/job_runner.py`):
- Job runner injektuje `ANTHROPIC_API_KEY` a `CLAUDE_CODE_OAUTH_TOKEN` z `jervis-secrets`
- Claude Code CLI preferuje `CLAUDE_CODE_OAUTH_TOKEN` (Max) → API key je fallback
- Aider/OpenHands/Junie používají `ANTHROPIC_API_KEY` přímo (nemají OAuth)

---

## 8. State persistence a restart resilience

### 8.1 Zdroj pravdy (SSOT)

**TaskDocument v MongoDB (Kotlin server)** zůstává SSOT pro:
- Lifecycle stav tasku (`TaskStateEnum`)
- `orchestratorThreadId` – link na LangGraph checkpoint
- `pendingUserQuestion` / `userQuestionContext` – USER_TASK kontext
- `agentCheckpointJson` – legacy field (unused, kept for backward compatibility)

**LangGraph checkpoints v MongoDB** (Python orchestrátor, kolekce `checkpoints`):
- Interní stav grafu (goals, steps, step_results, evaluation)
- Uloženy automaticky po každém node
- Použity pro resume z interrupt() (approval flow)
- Přežijí restart Python procesu

### 8.2 Persistent checkpointer

```python
# orchestrator.py
from langgraph.checkpoint.mongodb.aio import AsyncMongoDBSaver

# Inicializace v main.py lifespan:
checkpointer = AsyncMongoDBSaver.from_conn_string(settings.mongodb_url)
await checkpointer.setup()

# Kompilace grafu s checkpointerem:
graph = build_orchestrator_graph().compile(checkpointer=checkpointer)
```

MongoDB je **stejná instance** jako Kotlin server → žádná nová infrastruktura.

### 8.3 Restart scénáře

| Restart | Dopad | Obnova |
|---------|-------|--------|
| Python restart | Checkpoints v MongoDB přežijí | Kotlin polling najde thread_id v TaskDocument, Python obnoví graf z checkpointu |
| Kotlin restart | TaskDocument v MongoDB přežije | BackgroundEngine restartuje, runOrchestratorResultLoop() najde PYTHON_ORCHESTRATING tasky |
| Oba restart | Oba stavy v MongoDB | Plná obnova – thread_id propojí TaskDocument ↔ LangGraph checkpoint |

---

## 9. Async dispatch + result polling architektura

### 9.1 Fire-and-forget dispatch

"Fire-and-forget" znamená: Kotlin volá Python, dostane zpět `thread_id` **ihned** (neblokuje),
Python pokračuje na pozadí. Kotlin uvolní execution slot pro další tasky.

```
BackgroundEngine.executeTask(task)
  → agentOrchestrator.run(task, task.content, onProgress)
    → shouldUsePythonOrchestrator(userInput) = true
    → dispatchToPythonOrchestrator():
        POST /orchestrate/stream → {thread_id, stream_url}   // NEBLOKUJE
        task.state = PYTHON_ORCHESTRATING
        task.orchestratorThreadId = thread_id
        taskRepository.save(updatedTask)
        return true
    → return ChatResponseDto("")   // Prázdná odpověď = dispatch signál
  → freshTask.state == PYTHON_ORCHESTRATING
  → uvolní execution slot, return
```

### 9.2 Result polling loop

`BackgroundEngine.runOrchestratorResultLoop()` – nezávislý loop, běží každých 5s:

```
findByStateOrderByCreatedAtAsc(PYTHON_ORCHESTRATING)
  → pro každý task:
      GET /status/{thread_id}
      "running"     → skip
      "interrupted" → userTaskService.failAndEscalateToUserTask() + notifikace
      "done"        → emit výsledek + state=DISPATCHED_GPU (nebo delete pro BACKGROUND)
      "error"       → failAndEscalateToUserTask() + state=ERROR
      Python nedostupný → skip, retry next cycle
```

### 9.3 Stavový diagram tasku s orchestrátorem

```
READY_FOR_GPU
  │
  ├─── dispatchToPythonOrchestrator()
  │    │
  │    ▼
  │  PYTHON_ORCHESTRATING  ←─── resumePythonOrchestrator()
  │    │                          ▲
  │    ├── "done" ──────────► DISPATCHED_GPU (FOREGROUND) / DELETE (BACKGROUND)
  │    ├── "error" ─────────► ERROR → USER_TASK
  │    └── "interrupted" ───► USER_TASK
  │                             │
  │                             │  uživatel odpoví (sendToAgent)
  │                             │  task.content = odpověď uživatele
  │                             ▼
  │                          READY_FOR_GPU (orchestratorThreadId zachován)
  │                             │
  │                             └── BackgroundEngine pickup
  │                                 → agentOrchestrator.run()
  │                                 → Path 1: resumePythonOrchestrator()
  │                                 → POST /approve/{thread_id}
  │                                 → PYTHON_ORCHESTRATING (opakuje se)
  │
  └─── Error: orchestrátor nedostupný
```

---

## 10. USER_TASK approval flow

### 10.1 Průběh schvalování (commit/push)

1. LangGraph dosáhne `git_operations` → `interrupt()` (commit approval)
2. Checkpoint uložen do MongoDB
3. `runOrchestratorResultLoop()` detekuje `status=interrupted`
4. `userTaskService.failAndEscalateToUserTask(task, pendingQuestion="Schválení: commit...", ...)`
   - state = USER_TASK, type = USER_TASK
   - `notificationRpc.emitUserTaskCreated()` – UI notifikace
5. Uživatel vidí USER_TASK v UI, odpoví "ano, schvaluji"
6. `UserTaskRpcImpl.sendToAgent()`:
   - `task.content = "ano, schvaluji"` (odpověď uživatele)
   - state = READY_FOR_GPU, pendingUserQuestion = null
7. BackgroundEngine pickup → `agentOrchestrator.run(task, "ano, schvaluji", ...)`
8. Path 1: `task.orchestratorThreadId != null` → `resumePythonOrchestrator()`
   - Parsuje odpověď: "ano" → approved=true
   - `POST /approve/{thread_id}` s `{approved: true, reason: "ano, schvaluji"}`
   - state = PYTHON_ORCHESTRATING
9. Python obnoví graf z MongoDB checkpointu → pokračuje z interrupt bodu
10. Commit vykonán → možný další interrupt pro push → opakuje se od kroku 2

### 10.2 Klíčové invarianty

- **orchestratorThreadId** přežívá USER_TASK cyklus (není clearován)
- **task.content** po sendToAgent() = odpověď uživatele (ne původní task popis)
- **UserTaskService** zajistí notifikace + správný type + state
- **Checkpoint v MongoDB** zachová plný stav grafu včetně step_results, branch, atd.

### 10.3 Clarification flow (odlišný od approval)

Intake node (`intake`) může přerušit graf pro upřesňující otázky **před plánováním** — mandatory clarification when `goal_clear=false`.
Odlišnosti od approval flow:

| Aspekt | Clarification | Approval | Epic/Design Approval |
|--------|---------------|----------|---------------------|
| **Node** | `intake` | `git_operations` | `plan_epic` / `design` |
| **Kdy** | Před evidence_pack | Před commit/push | Před execution |
| **interrupt action** | `"clarify"` | `"commit"` / `"push"` | `"epic_plan"` / `"generative_design"` |
| **pendingQuestion prefix** | Bez prefixu | `"Schválení: ..."` | `"Schválení: ..."` |
| **Resume handling** | `approved=true`, celý userInput jako reason | Parsování ano/ne z userInput | Parsování ano/ne |

**Clarification Flow:**
1. `intake` node classifies task into 4 categories + detects if `goal_clear=false`
2. Pokud `goal_clear=false` → `interrupt({"action": "clarify", ...})` IHNED
3. BackgroundEngine detekuje `interrupted`, action=`clarify`
4. `pendingQuestion` = otázky **bez** prefixu "Schválení:"
5. Uživatel odpoví → `resumePythonOrchestrator()`
6. Detekce: `pendingQuestion` nezačíná "Schválení:" → clarification
7. `approve(threadId, approved=true, reason=userInput)` — celý input je odpověď
8. Python obnoví graf → odpovědi uloženy do `clarification_response`
9. `evidence_pack` → routing → appropriate category path

### 10.4 Cross-goal context (GoalSummary)

Při přechodu mezi goals (`advance_goal` node) se sestaví `GoalSummary`:
```python
class GoalSummary(BaseModel):
    goal_id: str
    title: str
    summary: str                                     # Shrnutí z step results
    changed_files: list[str] = []                    # Soubory změněné v tomto goalu
    key_decisions: list[str] = []                    # Klíčová rozhodnutí
```

- `plan_steps` čte `goal_summaries` ze state → předá LLM jako "Previously Completed Goals"
- Umožňuje agentům navazovat na předchozí práci místo duplikace

### 10.5 Dependency validation (select_goal)

`select_goal` node validuje `goal.dependencies`:
1. Kontrola: mají všechny dependency goals odpovídající ID v `goal_summaries`?
2. Pokud ne → zkusí swapnout s jiným goalem bez nesplněných dependencies
3. Pokud swap nemožný → warning log, pokračuje best-effort

---

## 11. Concurrency control – pouze jedna orchestrace najednou

### 11.1 Proč

LLM (Ollama/Anthropic) nezvládá efektivně více souběžných požadavků.
Python orchestrátor **smí zpracovávat maximálně jednu orchestraci najednou** (across ALL pods).

### 11.2 Tři vrstvy kontroly

```
Vrstva 1: Kotlin (early guard)                    Vrstva 2: Python (in-process)        Vrstva 3: MongoDB (multi-pod)
─────────────────────────────                      ────────────────────────────────      ────────────────────────────
dispatchToPythonOrchestrator():                    asyncio.Semaphore(1)                 DistributedLock (orchestrator_locks)
  countByState(PYTHON_ORCHESTRATING) > 0?          POST /orchestrate/stream → 429       findOneAndUpdate + heartbeat 10s
  → return false (skip dispatch)                   GET /health → {"busy": true/false}    stale recovery after 5 min
```

**Kotlin vrstva** (`AgentOrchestratorService`):
- `taskRepository.countByState(PYTHON_ORCHESTRATING)` před dispatch
- Pokud > 0, vrátí false → task zůstane READY_FOR_GPU → BackgroundEngine retry
- Reaguje na HTTP 429 z `orchestrateStream()` → vrátí false

**Python in-process vrstva** (`main.py`):
- `asyncio.Semaphore(1)` – `/orchestrate/stream` a `/orchestrate` vrátí 429 pokud busy
- `/approve/{thread_id}` fire-and-forget: `asyncio.create_task()` + semaphore
- `/health` vrací `{"busy": true/false}` pro diagnostiku

**MongoDB distributed lock** (`app/context/distributed_lock.py`):
- Collection `orchestrator_locks`, single document `_id: "orchestration_slot"`
- Lock acquired atomically via `findOneAndUpdate` (condition: `locked_by: null`)
- Heartbeat every 10s updates `locked_at` to prevent stale detection
- Stale lock recovery: locks older than 5 min without heartbeat are force-acquired
- Pod ID from `HOSTNAME` env var (K8s pod name)
- On startup: auto-recover stale locks from crashed pods

### 11.3 Resume po approval (fire-and-forget)

```
POST /approve/{thread_id} {approved, reason}
  → Python: asyncio.create_task(_resume_in_background())
  → vrátí {"status": "resuming"} IHNED (neblokuje Kotlin)
  → _resume_in_background() čeká na semaphore, pak resume_orchestration()
  → BackgroundEngine.runOrchestratorResultLoop() poluje GET /status
```

### 11.4 Časování

| Situace | Chování |
|---------|---------|
| Nový task, orchestrátor volný | Dispatch OK, PYTHON_ORCHESTRATING |
| Nový task, orchestrátor busy | Kotlin: count > 0 → skip. Python: 429 (fallback) |
| Approval, orchestrátor busy (jiný task) | POST /approve OK, _resume čeká na semaphore |
| Approval, orchestrátor volný | POST /approve OK, resume okamžitě |

## 12. Cloud Model Policy – per-provider escalation

### 12.1 Princip

Orchestrátor **vždy zkusí lokální Ollama model nejdříve**. Cloud modely se použijí pouze při:
- Selhání lokálního modelu (HeartbeatTimeout, prázdná odpověď, error)
- Kontextu > 49k tokenů (nelze lokálně)

### 12.2 Tři cloud provideři

| Provider | Tier | Síla | Kdy |
|----------|------|------|-----|
| **Anthropic** (Claude) | `CLOUD_REASONING` | Reasoning, analýza, architektura | `architecture`, `design_review`, `decomposition` |
| **OpenAI** (GPT-4o) | `CLOUD_CODING` | Kód, strukturovaný výstup | `code_change`, `planning`, default |
| **Gemini** (2.5 Pro) | `CLOUD_LARGE_CONTEXT` | 1M token context | POUZE kontext > 49k tokenů |

### 12.3 Nastavení

- `CloudModelPolicy` — 3 booleany: `autoUseAnthropic`, `autoUseOpenai`, `autoUseGemini`
- **Client level**: výchozí pro všechny projekty klienta
- **Project level**: nullable override (null = dědí z klienta)
- Předáváno v `ProjectRulesDto` → Python `ProjectRules`

### 12.4 Rozhodovací flow

```
LLM volání v nodu
  │
  ├─ kontext ≤ 49k → LOCAL tier
  │     ├─ úspěch → hotovo
  │     └─ selhání
  │           ├─ prompt říká "použi cloud" → auto-eskalace (všichni konfigurovaní)
  │           ├─ auto_use_* zapnuto → auto-eskalace (příslušný provider)
  │           ├─ API klíč existuje → interrupt() zeptá se uživatele
  │           └─ žádné klíče → error
  │
  └─ kontext > 49k → nelze lokálně
        ├─ auto_use_gemini → auto CLOUD_LARGE_CONTEXT
        ├─ prompt říká "použi cloud" → auto CLOUD_LARGE_CONTEXT
        ├─ google_api_key existuje → interrupt() zeptá se
        └─ žádný Gemini klíč → error

```

### 12.5 Klíčové funkce (nodes.py)

- `_detect_cloud_prompt(query)` — detekce "použi cloud" v dotazu
- `_auto_providers(rules)` — set auto-povolených providerů z ProjectRules
- `_llm_with_cloud_fallback(state, messages, ...)` — hlavní helper: local → cloud fallback
- `_escalate_to_cloud(task, auto_providers, ...)` — auto/interrupt logika

### 12.6 Konfigurace (config.py)

```python
openai_api_key: str = os.getenv("OPENAI_API_KEY", "")
default_openai_model: str = os.getenv("DEFAULT_OPENAI_MODEL", "gpt-4o")
```

### 12.7 Interrupt pro cloud schválení

Existující `ApprovalNotificationDialog` zvládne `action: "cloud_model"` automaticky:
- Popis: "Lokální model selhal: ...\nPovolit použití cloud modelu Anthropic Claude (reasoning)?"
- Uživatel schválí/zamítne → resume flow pokračuje

---

## 13. Multi-Agent Delegation System (Feature-Flagged)

> **Stav:** Rozšíření orchestrátoru z monolitického 14-nodového grafu na univerzální multi-agent systém.
> Cíl: orchestrátor řídí 19+ specialist agentů pro všechny domény (kód, HR, právo, komunikace, finance, ...).
> Podrobný plán viz `~/.claude/plans/mutable-wandering-cook.md`.

Orchestrátor může operovat ve dvou režimech:

1. **Legacy mode** (default) — stávající 14-nodový graf, 4 task kategorie (ADVICE, SINGLE_TASK, EPIC, GENERATIVE)
2. **Delegation mode** — nový 7-nodový graf s 19 specialist agenty a DAG execution

### 13.1 Delegation Graph (7 nodes)

```
intake → evidence_pack → plan_delegations → execute_delegation(s) → synthesize → finalize → END
```

| Node | Odpovědnost |
|------|-------------|
| `intake` | Klasifikace domény, urgence, jazyk detekce, Session Memory + Procedural Memory lookup (reuse stávajícího, rozšířen) |
| `evidence_pack` | KB kontext + tracker artifacts fetch (reuse stávajícího) |
| `plan_delegations` | **NOVÝ** — LLM vybírá z AgentRegistry, sestavuje ExecutionPlan (DAG delegací) |
| `execute_delegation` | **NOVÝ** — Dispatch DelegationMessage na agenty, DAG executor pro paralelní skupiny |
| `synthesize` | **NOVÝ** — Sloučení AgentOutput výsledků, RAG cross-check, překlad do `response_language` |
| `finalize` | Finální report generace (reuse stávajícího, rozšířen o delegation metadata) |

**Klíčová změna:** Místo hardcoded cest pro 4 kategorie máme univerzální delegační engine. `plan_delegations` node vybírá z registru agentů a sestavuje DAG (directed acyclic graph) delegací. Agenti mohou volat sub-agenty rekurzivně (max depth 4).

### 13.2 Feature Flags

```python
# config.py — Settings třída
use_delegation_graph: bool = False      # Nový 7-nodový graf (False = legacy 14-nodový)
use_specialist_agents: bool = False     # Specialist agenti místo LegacyAgent
use_dag_execution: bool = False         # Paralelní DAG delegace
use_procedural_memory: bool = False     # Učení z úspěchů (ArangoDB ProcedureNode)
```

Všechny flagy defaultují na `False` — nový systém je opt-in. Přepnutí na legacy je okamžité bez restartu.

### 13.3 Agent Registry

19 specialist agentů + LegacyAgent fallback, registrovaných při startup v `main.py` lifespan.

**Singleton:** `AgentRegistry.instance()` (`app/agents/registry.py`)

**Metody:**
- `register(agent)` — registrace agenta
- `get(name)` — lookup by name
- `list_agents()` → `list[AgentCapability]`
- `find_for_domain(domain)` → agenti pro danou doménu
- `get_capability_summary()` → text summary pro LLM prompt v `plan_delegations`

**Agenti (19 + fallback):**

| # | Agent | Domény | Sub-delegace |
|---|-------|--------|-------------|
| 1 | CodingAgent | code | - |
| 2 | GitAgent | code | CodingAgent |
| 3 | CodeReviewAgent | code | CodingAgent, TestAgent, ResearchAgent |
| 4 | TestAgent | code | CodingAgent |
| 5 | ResearchAgent | research | - |
| 6 | IssueTrackerAgent | project_management | ResearchAgent |
| 7 | WikiAgent | project_management | ResearchAgent |
| 8 | DocumentationAgent | code | ResearchAgent |
| 9 | DevOpsAgent | devops | - |
| 10 | ProjectManagementAgent | project_management | IssueTrackerAgent |
| 11 | SecurityAgent | security | ResearchAgent |
| 12 | CommunicationAgent | communication | EmailAgent |
| 13 | EmailAgent | communication | - |
| 14 | CalendarAgent | administrative | - |
| 15 | AdministrativeAgent | administrative | CalendarAgent |
| 16 | LegalAgent | legal | ResearchAgent |
| 17 | FinancialAgent | financial | - |
| 18 | PersonalAgent | personal | CalendarAgent |
| 19 | LearningAgent | learning | ResearchAgent |
| F | LegacyAgent | *all* | - (wraps existing 14-node logic) |

**BaseAgent** (`app/agents/base.py`):
```python
class BaseAgent(ABC):
    name: str
    description: str
    domains: list[DomainType]
    tools: list[dict]           # OpenAI function-calling schemas
    can_sub_delegate: bool

    @abstractmethod
    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput

    async def _call_llm(self, messages, tools=None, model_tier=None) -> str
    async def _execute_tool(self, tool_name, arguments, state) -> str
    async def _sub_delegate(self, target_agent, task_summary, context, state) -> AgentOutput
```

### 13.4 Communication Protocol

**DelegationMessage** (vstup pro agenta):
```python
class DelegationMessage(BaseModel):
    delegation_id: str
    parent_delegation_id: str | None = None
    depth: int = 0                    # 0=orchestrátor, 1-4=sub-agenti
    agent_name: str
    task_summary: str                 # Co má agent udělat (ANGLICKY)
    context: str = ""                 # Token-budgeted kontext
    constraints: list[str] = []       # Forbidden files, max changes, etc.
    expected_output: str = ""
    response_language: str = "en"     # ISO 639-1 pro finální odpověď
    client_id: str
    project_id: str | None = None
    group_id: str | None = None       # Agent vidí KB celé skupiny
```

**AgentOutput** (výstup agenta):
```python
class AgentOutput(BaseModel):
    delegation_id: str
    agent_name: str
    success: bool
    result: str = ""                  # Hlavní výstup
    structured_data: dict = {}        # diff, issues, etc.
    artifacts: list[str] = []         # Vytvořené soubory, commity
    changed_files: list[str] = []
    sub_delegations: list[str] = []   # ID sub-delegací (tracing)
    confidence: float = 1.0           # 0.0-1.0
    needs_verification: bool = False  # Cross-check přes KB
```

**Structured response format:** Agenti odpovídají kompaktně ale kompletně — STATUS/RESULT/ARTIFACTS/ISSUES/CONFIDENCE. Žádná trunkace.

**Pravidla delegací:**
1. **Max depth 4** — agent na depth 3 nemůže volat sub-agenta na depth 5
2. **Cycle detection** — stack delegací se trackuje, nelze volat agenta co už je ve stacku
3. **Token budget per depth** — Depth 0: 48k (GPU sweet spot), Depth 1: 16k, Depth 2: 8k, Depth 3-4: 4k
4. **Summarizace nahoru** — rodič NIKDY nevidí plný output sub-agenta, jen summary (max 500 znaků)
5. **Plný výsledek** se ukládá do `context_store` (MongoDB) pro on-demand retrieval

**Jazyková pravidla:**
- `intake` detekuje jazyk vstupu → `response_language` ve stavu
- Celý interní chain běží ANGLICKY (menší chybovost, menší token count)
- `finalize` přeloží výsledek do `response_language`

### 13.5 Memory Layers (4 vrstvy)

| Vrstva | Kde | TTL | Účel |
|--------|-----|-----|------|
| **Working Memory** | OrchestratorState (LangGraph checkpoint) | Během orchestrace | Aktuální delegation stack, mezivýsledky |
| **Session Memory** | MongoDB `session_memory` | 7 dní | Per-client/project krátkodobá paměť mezi orchestracemi |
| **Semantic Memory** | KB (Weaviate RAG + ArangoDB Graph) | Permanentní | Fakta, pravidla, konvence, decisions |
| **Procedural Memory** | ArangoDB `ProcedureNode` | Permanentní (usage-decay) | Naučené workflow postupy |

**Session Memory** (`app/context/session_memory.py`):
- Collection `session_memory`, klíč: `clientId + projectId`
- Max 50 entries per client/project, TTL 7 dní
- Plněna po každé orchestraci (klíčová rozhodnutí)
- Čtena na začátku orchestrace v `intake` node
- Řeší problém: "uživatel řekl v chatu před hodinou, background task to potřebuje vědět"

**Procedural Memory** (`app/context/procedural_memory.py`):
- ArangoDB `ProcedureNode` collection
- `trigger_pattern` → `procedure_steps` (agenti + akce)
- `success_rate`, `usage_count` — automatický usage-decay
- `source`: `"learned"` (automatické) vs `"user_defined"` (manuální, vyšší priorita)
- Pokud procedura pro daný typ úkolu neexistuje → orchestrátor se ZEPTÁ uživatele → uloží odpověď

**Progressive context assembly:**
- `context_assembler.py` rozšířen o `assemble_delegation_context()`
- Token budgets per depth level (48k → 16k → 8k → 4k)
- Evidence pack, session memory, delegation context oříznuty na budget

### 13.6 DAG Execution

`DAGExecutor` (`app/graph/dag_executor.py`):
- `plan_delegations` sestaví `ExecutionPlan` s `parallel_groups`
- Delegace ve stejné skupině běží přes `asyncio.gather` (paralelně)
- Skupiny mezi sebou sekvenčně (závislosti respektovány)
- Progress reporting per delegace přes `kotlin_client`

### 13.7 Backward Compatibility

**Zachováno BEZ ZMĚN:**
- Všechny API endpointy (`/orchestrate/stream`, `/approve/{thread_id}`, `/status/{thread_id}`, `/cancel/{thread_id}`)
- TaskDocument lifecycle a state transitions v Kotlin serveru
- K8s Job runner (`agents/job_runner.py`)
- Workspace manager (`agents/workspace_manager.py`)
- LLM provider (`llm/provider.py`)
- KB prefetch (`kb/prefetch.py`)
- MongoDB checkpointer (sdílený oběma grafy)

**Nové progress fieldy (backward compatible, Kotlin může ignorovat):**
```python
delegation_id: str | None          # ID aktuální delegace
delegation_agent: str | None       # Název agenta
delegation_depth: int | None       # Hloubka rekurze (0-4)
delegation_tree: list[dict] | None # Celý strom pro UI vizualizaci
thinking_about: str | None         # Co orchestrátor zvažuje
```

**Přepínání grafů:**
```python
def get_orchestrator_graph():
    if settings.use_delegation_graph:
        return build_delegation_graph().compile(checkpointer=_checkpointer)
    return build_orchestrator_graph().compile(checkpointer=_checkpointer)
```

Stávající `build_orchestrator_graph()` (14 nodes) se NEMAŽE — zůstává jako legacy fallback.

### 13.8 Nové soubory (delegation system)

```
backend/service-orchestrator/app/
  agents/
    base.py                          # BaseAgent, abstraktní třída
    registry.py                      # AgentRegistry singleton
    legacy_agent.py                  # Wrapper stávající 14-node logiky
    specialists/
      code_agent.py                  # CodingAgent (K8s Job delegace)
      git_agent.py                   # GitAgent (git operace)
      review_agent.py                # CodeReviewAgent (code review orchestrace)
      test_agent.py                  # TestAgent (testy, coverage)
      research_agent.py              # ResearchAgent (KB + web search)
      tracker_agent.py               # IssueTrackerAgent (issue CRUD)
      wiki_agent.py                  # WikiAgent (wiki CRUD)
      documentation_agent.py         # DocumentationAgent (docs generování)
      devops_agent.py                # DevOpsAgent (CI/CD, K8s)
      project_management_agent.py    # ProjectManagementAgent (sprint planning)
      security_agent.py              # SecurityAgent (security analýza)
      communication_agent.py         # CommunicationAgent (hub pro komunikaci)
      email_agent.py                 # EmailAgent (email operace)
      calendar_agent.py              # CalendarAgent (termíny, scheduling)
      administrative_agent.py        # AdministrativeAgent (logistika)
      legal_agent.py                 # LegalAgent (smlouvy, compliance)
      financial_agent.py             # FinancialAgent (rozpočet, faktury)
      personal_agent.py              # PersonalAgent (osobní asistence)
      learning_agent.py              # LearningAgent (tutoriály, evaluace)
  context/
    session_memory.py                # MongoDB session_memory collection
    procedural_memory.py             # ArangoDB ProcedureNode CRUD
    summarizer.py                    # Summarizace AgentOutput (max 500 znaků)
    retention_policy.py              # Co uložit do KB vs zahodit
  graph/
    dag_executor.py                  # DAG paralelní execution
    nodes/
      plan_delegations.py            # LLM-driven výběr agentů
      execute_delegation.py          # Dispatch + monitoring
      synthesize.py                  # Sloučení výsledků + RAG cross-check
  monitoring/
    delegation_metrics.py            # Per-agent metriky (start, end, tokens, llm_calls)
```

### 13.9 Failure Handling

- **Soft failure** (confidence < 0.5) → orchestrátor zkusí jiného agenta nebo eskaluje na uživatele
- **Hard failure** (exception) → retry 1x, pak eskalace
- **Quality failure** (RAG cross-check neprošel) → orchestrátor vrátí agentovi s vysvětlením
- **LegacyAgent fallback** → pokud specialist agent selže, orchestrátor může fallbacknout na stávající logiku
