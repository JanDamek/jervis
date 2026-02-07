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
POST /orchestrate            # Spustí orchestraci (z Kotlin serveru)
POST /resume/{thread_id}     # Resume po approval
GET  /stream/{thread_id}     # SSE stream progress
POST /approve/{thread_id}    # Approval response od uživatele
GET  /health                 # Health check
```

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
langchain-core>=0.3.0
litellm>=1.60.0
httpx>=0.28.0
kubernetes>=31.0.0
pydantic>=2.10.0
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
