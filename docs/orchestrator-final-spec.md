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

<<<<<<< Updated upstream
**LangGraph StateGraph (12 nodes):**
```
                    ┌── question? ──► [respond] ──► END
                    │                  (web_search, kb_search tools)
[router] ──────────┤
                    │                  ┌── coding task
                    └── coding? ──────►[clarify] → [decompose] → [select_goal] → [plan_steps] → [execute_step] → [evaluate]
                                            ↑                               ↑                             │
                                            │                               │                        [next_step]
                                            │                               │                             │
                                            └── [advance_goal] ←── more goals ────────────────────────────┤
                                                                            │                             │
                                                                   [advance_step] ← more steps ──────────┘
                                                                                                          │
                                                                                                          ↓ (all done)
                                                                                               [git_operations] → [report]
```

**Routing**: `router` node uses `route_entry()` heuristic to decide:
- **Question** (starts with question words, ends with `?`, no coding verbs) → `respond` node
- **Coding task** → `clarify` node (full pipeline)

**Respond node**: Agentic tool-use loop (max 5 iterations). LLM has `web_search` (SearXNG) and
`kb_search` (Knowledge Base) tools. After tool results are gathered, LLM formulates a final
answer. Returns `{"final_result": content, "artifacts": []}` directly to END.

`clarify` may `interrupt()` for user questions (resumes after answers), or pass through for simple tasks.
=======
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
>>>>>>> Stashed changes

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
