# Graph Agent — Doplnění pro greenfield vývoj

> Specifikace chybějících komponent aby Graph Agent zvládl vytvořit nový projekt od nuly.
> Testovací prompt: *"Napiš aplikaci pro domácí knihovnu. UI bude KMP s web/android/ios/desktop. Backend kotlin/spring. Ukladat do mongo/postgre. X uživatelů. Evidence co mám/přečteno/čtu/chci. Konektivita k databáziknih + další zdroje."*

---

## A. Chybějící Internal REST endpoints (Kotlin)

Agent (Python) komunikuje s Kotlin serverem přes REST (`/internal/*`). Tyto operace existují pouze v kRPC (UI), ne v REST:

### A1. `InternalProjectManagementRouting.kt` — NOVÝ

```
POST /internal/clients/create
  Body: { name: String, description: String? }
  → ClientService.create() → vrátí ClientDto (id, name)

POST /internal/projects/create
  Body: { clientId: String, name: String, description: String?, gitRemoteUrl: String? }
  → ProjectService.saveProject() → vrátí ProjectDto (id, name, clientId)
  → Automaticky: ensureProjectDirectories + syncProjectRepositories + workspace init

POST /internal/connections/create
  Body: { clientId: String, provider: "GITHUB"|"GITLAB", name: String,
          authType: "BEARER_TOKEN", bearerToken: String, gitRemoteUrl: String }
  → ConnectionService.save() → vrátí ConnectionResponseDto

GET  /internal/clients/list
  → ClientService.findAll() → List<ClientDto>

GET  /internal/projects/list?clientId={id}
  → ProjectService.findByClientId() → List<ProjectDto>
```

**Proč:** Graph Agent potřebuje vytvořit klienta + projekt + connection pro nový projekt. Tyto operace dnes jdou jen z UI přes kRPC/WebSocket.

### A2. `InternalGitRouting.kt` — NOVÝ

```
POST /internal/git/create-repository
  Body: { connectionId: String, repoName: String, description: String?, private: Boolean }
  → GitHub/GitLab API: POST /user/repos (GitHub) nebo POST /projects (GitLab)
  → Vrátí { cloneUrl: String, htmlUrl: String }

POST /internal/git/init-workspace
  Body: { projectId: String, templateType: "EMPTY"|"KMP"|"SPRING_BOOT"|"KMP_SPRING" }
  → git init v workspace, přidá základní strukturu, initial commit, push
  → Vrátí { workspacePath: String, commitHash: String }
```

**Proč:** Dnes Jervis umí pracovat jen s existujícími repozitáři. Pro greenfield musí umět repo vytvořit a inicializovat.

---

## B. Nové MCP tools

MCP server (`backend/service-mcp/app/main.py`) musí exposovat nové endpointy jako tools:

```python
# --- Project Management ---
@mcp.tool()
async def create_client(name: str, description: str = "") -> dict:
    """Vytvořit nového klienta v Jervis."""
    return await _post("/internal/clients/create", {"name": name, "description": description})

@mcp.tool()
async def create_project(client_id: str, name: str, description: str = "", git_remote_url: str = "") -> dict:
    """Vytvořit nový projekt pod klientem. Automaticky inicializuje workspace."""
    return await _post("/internal/projects/create", {...})

@mcp.tool()
async def create_connection(client_id: str, provider: str, name: str,
                            bearer_token: str, git_remote_url: str = "") -> dict:
    """Vytvořit nové připojení (GitHub/GitLab) pro klienta."""
    return await _post("/internal/connections/create", {...})

# --- Git Repository ---
@mcp.tool()
async def create_git_repository(connection_id: str, repo_name: str,
                                 description: str = "", private: bool = True) -> dict:
    """Vytvořit nový Git repozitář na GitHub/GitLab přes existující connection."""
    return await _post("/internal/git/create-repository", {...})

@mcp.tool()
async def init_project_workspace(project_id: str, template_type: str = "EMPTY") -> dict:
    """Inicializovat workspace pro projekt. Template: EMPTY, KMP, SPRING_BOOT, KMP_SPRING."""
    return await _post("/internal/git/init-workspace", {...})
```

---

## C. Nové Graph Agent tools

### C1. Přidat do `tool_sets.py` — SETUP vertex type

Nový vertex type pro project setup:

```python
# models.py — nový VertexType
SETUP = "setup"   # Project setup / scaffolding / infrastructure provisioning

# tool_sets.py — tools pro SETUP
if vertex_type == VertexType.SETUP:
    return _base + [
        TOOL_CREATE_CLIENT,            # /internal/clients/create
        TOOL_CREATE_PROJECT,           # /internal/projects/create
        TOOL_CREATE_CONNECTION,        # /internal/connections/create
        TOOL_CREATE_GIT_REPOSITORY,    # /internal/git/create-repository
        TOOL_INIT_PROJECT_WORKSPACE,   # /internal/git/init-workspace
        # + environment tools pro DB provisioning:
        TOOL_ENVIRONMENT_CREATE,
        TOOL_ENVIRONMENT_ADD_COMPONENT,
        TOOL_ENVIRONMENT_DEPLOY,
        _request_tools,
    ]
```

### C2. Nové tool definice v `tools/definitions.py`

```python
TOOL_CREATE_CLIENT = {
    "type": "function",
    "function": {
        "name": "create_client",
        "description": "Create a new client in Jervis. Use when starting a greenfield project for a new customer/entity.",
        "parameters": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Client name"},
                "description": {"type": "string", "description": "Client description"},
            },
            "required": ["name"],
        },
    },
}

TOOL_CREATE_PROJECT = {
    "type": "function",
    "function": {
        "name": "create_project",
        "description": "Create a new project under an existing client. Automatically initializes workspace and directories.",
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {"type": "string"},
                "name": {"type": "string"},
                "description": {"type": "string"},
                "git_remote_url": {"type": "string", "description": "Git repo URL (if already exists)"},
            },
            "required": ["client_id", "name"],
        },
    },
}

TOOL_CREATE_GIT_REPOSITORY = {
    "type": "function",
    "function": {
        "name": "create_git_repository",
        "description": "Create a new Git repository on GitHub/GitLab via an existing connection.",
        "parameters": {
            "type": "object",
            "properties": {
                "connection_id": {"type": "string"},
                "repo_name": {"type": "string"},
                "description": {"type": "string"},
                "private": {"type": "boolean", "default": True},
            },
            "required": ["connection_id", "repo_name"],
        },
    },
}

TOOL_INIT_PROJECT_WORKSPACE = {
    "type": "function",
    "function": {
        "name": "init_project_workspace",
        "description": "Initialize project workspace with a project template. Templates: EMPTY, KMP, SPRING_BOOT, KMP_SPRING.",
        "parameters": {
            "type": "object",
            "properties": {
                "project_id": {"type": "string"},
                "template_type": {
                    "type": "string",
                    "enum": ["EMPTY", "KMP", "SPRING_BOOT", "KMP_SPRING"],
                    "default": "EMPTY",
                },
            },
            "required": ["project_id"],
        },
    },
}
```

### C3. Environment tools do graph agent

EXECUTOR a SETUP vertices musí mít přístup k environment tools:

```python
# tool_sets.py — přidat do kategorií:
categories["environment"] = [
    TOOL_ENVIRONMENT_CREATE,
    TOOL_ENVIRONMENT_ADD_COMPONENT,    # PostgreSQL, MongoDB, Redis...
    TOOL_ENVIRONMENT_CONFIGURE,
    TOOL_ENVIRONMENT_DEPLOY,
    TOOL_ENVIRONMENT_STATUS,
]
```

Tím EXECUTOR vertex může přes `request_tools(categories=["environment"])` získat environment tools a vytvořit PostgreSQL + MongoDB pro aplikaci.

---

## D. Project Templates (Kotlin)

### D1. `ProjectTemplateService.kt` — NOVÝ

```kotlin
@Service
class ProjectTemplateService(
    private val directoryStructureService: DirectoryStructureService,
) {
    enum class TemplateType { EMPTY, KMP, SPRING_BOOT, KMP_SPRING }

    fun initializeFromTemplate(workspacePath: String, template: TemplateType) {
        when (template) {
            EMPTY -> initEmpty(workspacePath)
            KMP -> initKmp(workspacePath)
            SPRING_BOOT -> initSpringBoot(workspacePath)
            KMP_SPRING -> { initKmp(workspacePath); initSpringBoot(workspacePath) }
        }
    }
}
```

Template obsah (minimální scaffolding — zbytek doplní coding agent):

**KMP template:**
```
settings.gradle.kts          — KMP plugin, module setup
build.gradle.kts              — Compose Multiplatform, targets (android, ios, desktop, web)
shared/build.gradle.kts       — shared module
shared/src/commonMain/...     — prázdný @Composable App()
composeApp/src/androidMain/   — Android entry point
composeApp/src/desktopMain/   — Desktop entry point
composeApp/src/iosMain/       — iOS entry point
composeApp/src/wasmJsMain/    — Web entry point
gradle/libs.versions.toml     — version catalog
```

**SPRING_BOOT template:**
```
backend/build.gradle.kts      — Spring Boot, Spring Data, Kotlin
backend/src/main/kotlin/...   — Application.kt, application.yml
backend/src/main/resources/   — application.yml (MongoDB + PostgreSQL config)
```

**Alternativa:** Místo statických templates použít coding agenta s explicitním scaffolding promptem. Výhoda: vždy aktuální verze. Nevýhoda: pomalejší, závisí na LLM kvalitě.

---

## E. Git Repository Creation (Kotlin)

### E1. `GitRepositoryCreationService.kt` — NOVÝ

```kotlin
@Service
class GitRepositoryCreationService(
    private val connectionRepository: ConnectionRepository,
    private val providerRegistry: ProviderRegistry,
) {
    suspend fun createRepository(
        connectionId: String,
        repoName: String,
        description: String,
        private: Boolean
    ): GitRepoResult {
        val connection = connectionRepository.getById(connectionId)
        val provider = providerRegistry.getProvider(connection.provider)

        return when (connection.provider) {
            GITHUB -> provider.createRepo(connection, repoName, description, private)
            GITLAB -> provider.createProject(connection, repoName, description, private)
            else -> throw UnsupportedOperationException("Repo creation not supported for ${connection.provider}")
        }
    }
}
```

Potřebuje implementaci v GitHub/GitLab providerech:
- **GitHub**: `POST https://api.github.com/user/repos` + Bearer token
- **GitLab**: `POST https://gitlab.com/api/v4/projects` + Bearer token

---

## F. Opravy v Graph Agent (Python)

### F1. Paralelní vertex exekuce

```python
# langgraph_runner.py — node_select_next → node_dispatch_vertex

# SOUČASNÉ: vybere 1 READY vertex
state["current_vertex_id"] = ready[0].id

# NOVÉ: vybere VŠECHNY READY vertices, exekuuje paralelně
async def node_dispatch_vertices(state):  # plural
    ready = get_ready_vertices(graph)
    if len(ready) == 1:
        # Sériově jako dnes
        return await _dispatch_single(ready[0], state)
    else:
        # Paralelně — asyncio.gather
        results = await asyncio.gather(
            *[_dispatch_single(v, state) for v in ready],
            return_exceptions=True,
        )
        # Merge výsledky do grafu
        ...
```

**Pozor na routing**: Paralelní GPU volání nefunguje (1 request per GPU). Paralelní vertices musí jít buď:
- Jeden na GPU + ostatní na OpenRouter, nebo
- Všechny na OpenRouter (pokud tier >= FREE)
- Nebo sériově pokud jen GPU

### F2. Zvýšení limitů

```python
_MAX_VERTEX_TOOL_ITERATIONS = 12    # bylo 6 — málo pro implementační vertices
_VERTEX_TOOL_TIMEOUT_S = 120        # bylo 60 — coding agent dispatch trvá déle
MAX_TOTAL_VERTICES = 200            # ponechat — stačí
MAX_DECOMPOSE_DEPTH = 8             # ponechat — stačí
```

### F3. LLM-powered syntéza v `node_synthesize`

```python
async def node_synthesize(state):
    graph = TaskGraph(**state["task_graph"])
    raw_result = get_final_result(graph)

    # Pokud víc než 1 terminální vertex → LLM syntéza
    terminal_count = sum(1 for v in graph.vertices.values()
                        if not get_outgoing_edges(graph, v.id)
                        and v.status == VertexStatus.COMPLETED)

    if terminal_count > 1:
        messages = [
            {"role": "system", "content": _SYSTEM_PROMPTS[VertexType.SYNTHESIS]},
            {"role": "user", "content": f"Combine these results into a coherent response:\n\n{raw_result}"},
        ]
        response = await llm_with_cloud_fallback(state=state, messages=messages, ...)
        result = response.choices[0].message.content
    else:
        result = raw_result

    state["final_result"] = result
    ...
```

### F4. Vertex timeout (celkový)

```python
# Nový: timeout na celý vertex (ne jen per-tool)
_MAX_VERTEX_DURATION_S = 600  # 10 minut

async def node_dispatch_vertex(state):
    ...
    try:
        result = await asyncio.wait_for(
            _dispatch_single(vertex, state),
            timeout=_MAX_VERTEX_DURATION_S,
        )
    except asyncio.TimeoutError:
        fail_vertex(graph, vertex_id, "Vertex timed out after 10 minutes")
```

### F5. Edge payload update — key-based místo index-based

```python
# persistence.py — update_edge_payload by edge_id, ne by index
async def update_edge_payload(self, task_id: str, edge_id: str, payload: EdgePayload):
    coll = await self._ensure_collection()
    result_op = await coll.update_one(
        {"task_id": task_id, "edges.id": edge_id},
        {"$set": {"edges.$.payload": payload.model_dump(), "updated_at": ...}},
    )
```

---

## G. Zbývající bugy (z předchozí review)

### G1. CRITICAL: putAll(msg.metadata) chybí v history load

```kotlin
// ChatRpcImpl.kt — loadování chat historie
metadata = buildMap {
    put("sender", msg.role.name.lowercase())
    put("timestamp", msg.timestamp.toString())
    put("fromHistory", "true")
    put("sequence", msg.sequence.toString())
    putAll(msg.metadata)  // ← CHYBÍ — taskId, taskTitle, success se ztratí po reloadu
}
```

### G2. COMPILE ERROR: chatMessages + compressionBoundaries

`screens/MainScreen.kt` odkazuje `viewModel.chat.chatMessages` a `viewModel.chat.compressionBoundaries`, ale `ChatViewModel` tyto public properties nemá. Buď přidat, nebo vrátit `displayItems` pattern.

### G3. Threading UI konflikt s masterem

Větev maže veškerý threading UI (ChatDisplayItem, ThreadGrouper, ThreadCard). Master má tyto komponenty. Vyřešit:
- **Varianta A**: Zachovat threading, rebasovat větev na master
- **Varianta B**: Záměrně smazat threading (s jasným rozhodnutím)

### G4. Doc limity nekonzistence

§34.8 říká MAX_TOTAL_VERTICES=50, MAX_DECOMPOSE_DEPTH=4. Kód i §34.13 říká 200/8. Opravit §34.8.

### G5. Desktop icon changes

Nesouvisí s graph agentem. Oddělit do jiného commitu.

### G6. contextTaskId v handler_agentic.py

Odstraněn z metadat — regrese pro non-graph-agent cestu. Vrátit.

---

## H. Decomposer prompt — rozšíření o SETUP

Decomposer musí znát nový SETUP vertex type:

```python
# decomposer.py — přidat do _DECOMPOSE_SYSTEM_PROMPT:
"""
- "setup" — project scaffolding, repository creation, environment provisioning, database setup
"""

# A do available agents:
"""Available agents: research, coding, git, code_review, test, documentation,
devops, project_management, setup, ..."""
```

---

## I. Shrnutí implementačního pořadí

| Krok | Co | Kde | Závislosti |
|------|----|-----|------------|
| **1** | Fix zbývajících bugů (G1-G6) | Kotlin + KMP | Žádné |
| **2** | Internal REST: client/project/connection CRUD | Kotlin `rpc/internal/` | Žádné |
| **3** | Git repo creation service | Kotlin `service/git/` | Krok 2 |
| **4** | Project templates | Kotlin `service/project/` | Krok 2 |
| **5** | MCP tools pro nové endpointy | Python `service-mcp/` | Kroky 2-4 |
| **6** | Graph agent tools: SETUP type + definice | Python `graph_agent/` | Krok 5 |
| **7** | Environment tools v graph agent | Python `graph_agent/tool_sets.py` | Existuje |
| **8** | Paralelní vertex exekuce | Python `graph_agent/langgraph_runner.py` | Žádné |
| **9** | LLM syntéza v node_synthesize | Python `graph_agent/langgraph_runner.py` | Žádné |
| **10** | Zvýšení limitů + vertex timeout | Python `graph_agent/` | Žádné |
| **11** | Edge payload update by key | Python `graph_agent/persistence.py` | Žádné |
| **12** | Decomposer prompt rozšíření | Python `graph_agent/decomposer.py` | Krok 6 |

---

## J. Ověření hotovosti (testovací scénář)

Prompt: *"Napiš aplikaci pro domácí knihovnu..."*

Očekávaný flow po implementaci:

```
ROOT → decompose →
  [v1: SETUP]         "Vytvořit klienta, projekt, Git repo, KMP+Spring workspace"
                      tools: create_client, create_project, create_git_repository, init_project_workspace
  [v2: SETUP]         "Provisioning PostgreSQL + MongoDB (environment)"
                      tools: environment_create, environment_add_component (PG, Mongo), environment_deploy
  [v3: INVESTIGATOR]  "Prozkoumat API zdrojů knih (databazeknih.cz, Google Books, OpenLibrary, ISBN)"
                      tools: web_search, kb_search, store_knowledge
  [v4: PLANNER]       "Navrhnout entity model, API design, frontend screens"
       depends_on: [v1, v3]
       → rekurzivní dekompozice na sub-vertices
  [v5: EXECUTOR]      "Implementovat backend: entities, repositories, services, controllers"
       depends_on: [v2, v4]
       tools: dispatch_coding_agent (Claude CLI v workspace)
  [v6: EXECUTOR]      "Implementovat frontend: KMP screens (knihovna, detail, hledání, profil)"
       depends_on: [v4, v5]
       tools: dispatch_coding_agent
  [v7: EXECUTOR]      "Implementovat API integrace (Google Books, OpenLibrary, databázeknih scraper)"
       depends_on: [v3, v5]
       tools: dispatch_coding_agent
  [v8: VALIDATOR]     "Ověřit kompilaci, testy, API funkčnost"
       depends_on: [v5, v6, v7]
  [v9: SYNTHESIS]     "Shrnout výsledek: co je hotové, jak spustit, co zbývá"
       depends_on: [v8]
```

v1 a v2 a v3 běží PARALELNĚ (žádné závislosti).
v5 a v7 mohou běžet PARALELNĚ po splnění svých deps.
