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
  service-orchestrator/           # NOVÝ – Python orchestrator
    app/
      __init__.py
      main.py                     # FastAPI + SSE endpoints
      config.py                   # Konfigurace (env vars)
      models.py                   # Pydantic modely (state, rules, tasks)
      graph/
        __init__.py
        orchestrator.py           # LangGraph StateGraph – hlavní flow
        nodes.py                  # Nodes: plan, execute, evaluate, git_ops
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
        kotlin_client.py          # REST client pro Kotlin server internal API
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
GET  /health                 # Health check
```

**Komunikační model (full request, no callbacks):**
- Kotlin → Python: `POST /orchestrate/stream` s kompletním `OrchestrateRequest`
  (rules, workspace, task info – vše upfront)
- Python → UI: SSE `/stream/{thread_id}` pro progress
- Kotlin polls: `GET /status/{thread_id}` z `BackgroundEngine.runOrchestratorResultLoop()`
- Approval: interrupt → USER_TASK → user odpovídá → `POST /approve/{thread_id}`
- **Žádné Python → Kotlin callbacky** (kotlin_client.py je minimální)

**LangGraph StateGraph:**
```
[decompose] → [select_goal] → [plan_steps] → [execute_step] → [evaluate]
                    ↑                                              │
                    └──────────── [next_step] ←────────────────────┘
                                      │
                                      ↓ (all done)
                               [git_operations] → [report]
```

**State model:**
```python
class OrchestratorState(TypedDict):
    # Input
    task_id: str
    client_id: str
    project_id: str | None
    user_query: str
    workspace_path: str

    # Rules
    rules: ProjectRules

    # Execution state
    goals: list[Goal]
    current_goal_index: int
    steps: list[CodingStep]
    current_step_index: int
    step_results: list[StepResult]

    # Output
    branch: str | None
    final_result: str | None
    artifacts: list[str]

    # Streaming
    messages: Annotated[list, add_messages]
```

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
- `setup_claude_workspace(workspace, client_id, project_id, kb_context)` – MCP + CLAUDE.md
- `setup_aider_workspace(workspace, files, kb_context)` – .aider.conf.yml
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

class CodingTask(BaseModel):
    """Task pro orchestrator."""
    id: str
    client_id: str
    project_id: str | None = None
    workspace_path: str
    query: str
    agent_preference: str = "auto"  # "auto" | "aider" | "claude" | "openhands" | "junie"

class Goal(BaseModel):
    """Jeden cíl rozložený z user query."""
    id: str
    title: str
    description: str
    complexity: str  # "simple" | "medium" | "complex" | "critical"
    dependencies: list[str] = []

class CodingStep(BaseModel):
    """Jeden krok plánu pro coding agenta."""
    index: int
    instructions: str
    agent_type: str
    files: list[str] = []

class StepResult(BaseModel):
    """Výsledek jednoho kroku."""
    step_index: int
    success: bool
    summary: str
    agent_type: str
    changed_files: list[str] = []

class ApprovalRequest(BaseModel):
    """Žádost o schválení od uživatele."""
    action_type: str   # "commit" | "push" | "code_change" | ...
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
6. `graph/nodes.py` – decompose, plan, execute_step, evaluate, git_operations
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
K8S_NAMESPACE         app_orchestrator.yaml  Namespace pro K8s Jobs (jervis)
DATA_ROOT             app_orchestrator.yaml  Sdílený PVC (/opt/jervis/data)
OLLAMA_URL            app_orchestrator.yaml  Lokální LLM (http://192.168.100.117:11434)
ANTHROPIC_API_KEY     jervis-secrets         Fallback LLM (orchestrátor) + fallback auth (Jobs)
CONTAINER_REGISTRY    app_orchestrator.yaml  Registry pro Job images

Jobs (injektované z jervis-secrets do K8s Jobs, NE do orchestrátoru):
CLAUDE_CODE_OAUTH_TOKEN  jervis-secrets      Max účet OAuth – Claude Code CLI preferuje tento
ANTHROPIC_API_KEY        jervis-secrets      Pay-per-token fallback pro Claude CLI + Aider/OpenHands
```

**Autentizace – dva klíče, různé účely:**

| Secret | Co to je | Kdo ho používá |
|--------|----------|----------------|
| `CLAUDE_CODE_OAUTH_TOKEN` | **Max účet** (OAuth) | Claude Code CLI v K8s Jobs – preferovaný auth |
| `ANTHROPIC_API_KEY` | **API klíč** (pay-per-token) | Orchestrátor fallback + Job fallback pokud chybí OAuth |

**Orchestrátorova vlastní logika** (`llm/provider.py`):
- Decompose + plan běží na **Ollama** (LOCAL_FAST / LOCAL_STANDARD)
- Na Anthropic API eskaluje JEN jako fallback: 2× selhání lokálního modelu,
  >32k context, nebo user preference "quality"
- V praxi orchestrátor **skoro nikdy** nevolá Anthropic API

**K8s Jobs pro coding agenty** (`agents/job_runner.py`):
- Job runner injektuje OBA klíče z `jervis-secrets` (ne z vlastního env)
- Claude Code CLI preferuje `CLAUDE_CODE_OAUTH_TOKEN` (Max) → API key je fallback
- Aider/OpenHands/Junie používají `ANTHROPIC_API_KEY` přímo (nemají OAuth)

---

## 8. State persistence a restart resilience

### 8.1 Zdroj pravdy (SSOT)

**TaskDocument v MongoDB (Kotlin server)** zůstává SSOT pro:
- Lifecycle stav tasku (`TaskStateEnum`)
- `orchestratorThreadId` – link na LangGraph checkpoint
- `pendingUserQuestion` / `userQuestionContext` – USER_TASK kontext
- `agentCheckpointJson` – stav Koog agentů (Python orchestrátor tento field nepoužívá)

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
  └─── Koog agent (blocking, stávající flow)
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

---

## 11. Concurrency control – pouze jedna orchestrace najednou

### 11.1 Proč

LLM (Ollama/Anthropic) nezvládá efektivně více souběžných požadavků.
Python orchestrátor **smí zpracovávat maximálně jednu orchestraci najednou**.

### 11.2 Dvě vrstvy kontroly

```
Vrstva 1: Kotlin (early guard)                    Vrstva 2: Python (definitivní)
─────────────────────────────                      ────────────────────────────────
dispatchToPythonOrchestrator():                    asyncio.Semaphore(1)
  countByState(PYTHON_ORCHESTRATING) > 0?          POST /orchestrate/stream → 429 if busy
  → return false (skip dispatch)                   POST /approve → runs in background with semaphore
                                                   GET /health → {"busy": true/false}
```

**Kotlin vrstva** (`AgentOrchestratorService`):
- `taskRepository.countByState(PYTHON_ORCHESTRATING)` před dispatch
- Pokud > 0, vrátí false → task zůstane READY_FOR_GPU → BackgroundEngine retry
- Reaguje na HTTP 429 z `orchestrateStream()` → vrátí false

**Python vrstva** (`main.py`):
- `asyncio.Semaphore(1)` – `/orchestrate/stream` a `/orchestrate` vrátí 429 pokud busy
- `/approve/{thread_id}` fire-and-forget: `asyncio.create_task()` + semaphore
- `/health` vrací `{"busy": true/false}` pro diagnostiku

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
