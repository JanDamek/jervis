# TODO – Plánované Features a Vylepšení

Tento dokument obsahuje seznam plánovaných features, vylepšení a refaktoringů,
které budou implementovány jako separate tickety.

---

## Autoscaling & Performance

### DB Performance Metrics & Monitoring

**Problém:**
- KB ingest operace nyní běží async v background queue
- Není viditelnost do toho, jestli DB (ArangoDB, Weaviate) není bottleneck
- Může se stát, že všechny PODy čekají na DB a fronta roste
- Žádné metriky pro monitorování DB výkonu

**Řešení:**
- Přidat metriky pro KB operace:
  - RAG ingest latency (Weaviate write)
  - Graph ingest latency (ArangoDB write)
  - Query latency (read operations)
  - Queue depth (pending extraction tasks)
  - Active workers count
- Exportovat metriky do Prometheus/Grafana
- Alert při vysoké latenci nebo rostoucí frontě

**Implementace:**
1. KB Python service: instrumentace pomocí `prometheus_client`
2. Metriky endpointy: `/metrics` (Prometheus format)
3. K8s ServiceMonitor pro automatický scraping
4. Grafana dashboard s latency graphs a queue depth

**Soubory:**
- `backend/service-knowledgebase/app/metrics.py` – Prometheus metrics
- `backend/service-knowledgebase/app/main.py` – expose /metrics endpoint
- `k8s/kb-servicemonitor.yaml` – Prometheus ServiceMonitor
- `grafana/kb-dashboard.json` – Grafana dashboard

**Priorita:** High (pro detekci DB bottlenecks)
**Complexity:** Medium
**Status:** Planned

---

## Environment Management (Phase 2 + 3)

### Kontext – 3 vrstvy interakce s prostředím

Práce s K8s prostředími je rozdělena do tří vrstev s jasně oddělenou zodpovědností:

| Vrstva | Kde v UI | Účel | Operace |
|--------|----------|------|---------|
| **Náhled** (✅ Done) | Hlavní okno – postranní panel | Read-only monitoring | Strom prostředí, stav komponent, repliky, ready/error, logy |
| **Správa** (Phase 2) | Navigace → Environment Manager | Plná konfigurace a ovládání | CRUD komponent, images, ENV, configmapy, porty, nasazení, logy, scaling |
| **Vytvoření** (Phase 3) | Nastavení → Prostředí | Založení prostředí a přiřazení | Vytvořit prostředí, přiřadit ke klientovi/projektu, základní metadata |

**Hlavní okno (panel)** = jen **náhled** — vidět hodnoty, stav, logy. Žádná editace.
**Nastavení → Prostředí** = jen **vytvořit** prostředí, přiřadit ke klientovi, možná název a namespace. Detailní konfigurace se dělá v Environment Manageru.
**Environment Manager** = hlavní místo pro **veškerou konfiguraci** prostředí.

### Phase 2: Environment Manager (Standalone Screen)

**Prerequisite:** Phase 1 (backend + MCP) ✅ DONE — fabric8 K8s methods, EnvironmentResourceService, internal REST endpoints, MCP server, workspace manager integration, RBAC.

**Cíl:** Plnohodnotná správa K8s prostředí — od definice komponent, přes nastavení images a ENV proměnných, až po nasazení a monitoring. Včetně podpory aplikací bez vlastního Docker image.

#### A. UI – Environment Manager Screen

Nová navigační položka v hlavním menu (`Screen.EnvironmentManager`). Layout: `JListDetailLayout` — vlevo seznam prostředí, vpravo detail vybraného.

**Levý panel (seznam):**
- Všechna prostředí pro aktuálního klienta
- Stav badge (Running/Stopped/Error)
- Tlačítko "Nové prostředí"

**Pravý panel (detail) — záložky:**

1. **Přehled** — název, namespace, stav, přiřazení (klient, projekt/skupina), akce (Start, Stop, Restart, Smazat)
2. **Komponenty** — CRUD seznam komponent prostředí:
   - Každá komponenta: typ (Deployment/StatefulSet/Job/CronJob), název, image, repliky
   - Tlačítka: Přidat, Editovat, Odstranit
   - Detail komponenty (editace):
     - Image source (registry URL, tag, nebo "bez image" → base Linux)
     - Porty (containerPort, servicePort, protocol)
     - ENV proměnné (key-value, nebo odkaz na ConfigMap/Secret)
     - Volume mounts (PVC, emptyDir, hostPath)
     - Resource limits (CPU, memory)
     - Health checks (liveness, readiness)
     - Startup command override
3. **ConfigMap & Secrets** — správa ConfigMap a Secret objektů pro namespace:
   - CRUD pro ConfigMap: název → klíč-hodnota páry
   - CRUD pro Secret: název → klíč-hodnota (maskovné)
   - Možnost importovat ze souboru (.env, YAML)
   - Propojení: které komponenty odkazují na který ConfigMap/Secret
4. **Síť** — Service/Ingress konfigurace:
   - Service typ (ClusterIP, NodePort, LoadBalancer)
   - Ingress pravidla (hostname, path, TLS)
5. **Logy & Events** — real-time pod logy + K8s events:
   - Log viewer s tail -f (SSE stream)
   - K8s events pro namespace (Warning/Normal)
   - Filtrování podle komponenty/podu
6. **YAML** — raw YAML/JSON viewer pro advanced uživatele:
   - Read-only zobrazení generovaných K8s manifestů
   - Možnost "export" manifestů

#### B. Podpora aplikací bez Docker image

**Problém:** Ne každá aplikace má Dockerfile nebo Docker image. Přesto ji potřebujeme v K8s prostředí spustit.

**Řešení: Base Linux Container**
- Při definici komponenty lze vybrat "Bez vlastního image" → systém použije base Linux image (např. `ubuntu:22.04` nebo vlastní `jervis-base:latest`)
- Do base containeru se:
  1. **Namountují zdrojové kódy** přes PVC nebo init container (git clone)
  2. **Nainstalují závislosti** podle detekovaného stacku (npm install, pip install, mvn install, ...)
  3. **Spustí aplikace** podle konfigurace (startup command)
- Konfigurace "bez image" komponenty:
  - Git repo URL + branch (odkud vzít kód)
  - Working directory (path v kontejneru)
  - Build příkaz (optional: `npm run build`, `mvn package`, ...)
  - Run příkaz (`node server.js`, `python app.py`, `java -jar app.jar`, ...)
  - Runtime (Node.js, Python, Java, Go, .NET — detekce nebo manuální výběr)
  - Závislosti (automatická detekce z package.json, requirements.txt, pom.xml)

**Implementace:**
- **Init container pattern**: Init container provede git clone + build, main container spustí aplikaci
- **Base images per runtime**: `jervis-base-node:20`, `jervis-base-python:3.12`, `jervis-base-java:21`, atd.
- Nebo univerzální `jervis-base:latest` s multi-runtime (node, python, java, go pre-installed)
- Build step běží v init containeru, runtime step v main containeru (sdílený volume)

**Flow:**
```
User v UI definuje komponentu:
  → typ: "Source Code" (ne Docker image)
  → git: https://github.com/user/app.git, branch: main
  → runtime: Node.js (auto-detected from package.json)
  → run: npm start
  → env: PORT=3000, DB_URL=...

Jervis vygeneruje K8s manifest:
  → initContainer: git clone + npm install
  → container: jervis-base-node:20, command: npm start
  → volume: shared emptyDir pro kód
  → service: ClusterIP, port 3000
```

#### C. Backend rozšíření

**EnvironmentService rozšíření:**
- `createComponent(envId, componentDto)` — CRUD operace pro komponenty
- `updateComponent(envId, componentId, componentDto)`
- `deleteComponent(envId, componentId)`
- `getConfigMaps(envId)` — list ConfigMap
- `setConfigMap(envId, name, data: Map<String,String>)`
- `deleteConfigMap(envId, name)`
- `getSecrets(envId)` — list Secret (values masked)
- `setSecret(envId, name, data: Map<String,String>)`
- `deleteSecret(envId, name)`
- `deployEnvironment(envId)` — apply all manifests to K8s
- `stopEnvironment(envId)` — scale all to 0 / delete resources
- `getEvents(envId, since)` — K8s events for namespace
- `streamPodLogs(envId, podName, tailLines)` — SSE log stream

**EnvironmentComponentDto rozšíření:**
```kotlin
data class EnvironmentComponentDto(
    val id: String,
    val name: String,
    val type: ComponentType,          // DEPLOYMENT, STATEFUL_SET, JOB, CRON_JOB
    // Image source
    val imageSource: ImageSource,     // REGISTRY nebo SOURCE_CODE
    val image: String?,               // registry image (pokud REGISTRY)
    val gitRepoUrl: String?,          // git repo (pokud SOURCE_CODE)
    val gitBranch: String?,
    val runtime: RuntimeType?,        // NODE, PYTHON, JAVA, GO, DOTNET (pokud SOURCE_CODE)
    val buildCommand: String?,        // npm install, pip install, mvn package
    val runCommand: String?,          // npm start, python app.py
    val workDir: String?,             // working directory v containeru
    // Resources
    val replicas: Int = 1,
    val ports: List<PortMapping>,
    val envVars: List<EnvVar>,        // inline key-value
    val configMapRefs: List<String>,  // ConfigMap names → injected as ENV
    val secretRefs: List<String>,     // Secret names → injected as ENV
    val volumeMounts: List<VolumeMount>,
    val cpuLimit: String?,            // "500m", "1"
    val memoryLimit: String?,         // "512Mi", "2Gi"
    val startupCommand: String?,      // override entrypoint
    val healthCheckPath: String?,     // HTTP path pro liveness/readiness
    val healthCheckPort: Int?,
)
```

**Manifest Generator:**
- `K8sManifestGenerator` — z EnvironmentDto + komponent vygeneruje K8s YAML manifesty
- Pro SOURCE_CODE: generuje initContainer spec s git clone + build
- Pro REGISTRY: standardní container spec s image
- Generuje Service, Ingress, ConfigMap, Secret, PVC podle konfigurace

#### D. Navigace

- Přidat `Screen.EnvironmentManager` do `AppNavigator`
- Přidat navigační položku do hlavního menu (vedle Settings, Agent Workload, atd.)
- Přidat ikonu do menu: `Icons.Default.Dns` (nebo `Cloud`)

#### E. Existující kód k využití

- `IEnvironmentService` — RPC interface pro prostředí (list, create, delete, status)
- `IEnvironmentResourceService` — RPC interface pro K8s resources (pods, deployments, logs, scale)
- `EnvironmentResourceService` — backend fabric8 K8s operace
- `EnvironmentViewerScreen.kt` — existující viewer screen (základ pro rozšíření)
- `EnvironmentPanel.kt` — read-only panel v hlavním okně (zůstává jako náhled)
- `EnvironmentTreeComponents.kt` — composables pro strom komponent (znovupoužití)

#### F. Implementační kroky

1. **Backend DTOs** — rozšířit `EnvironmentComponentDto` o image source, ENV, configmap refs, volume mounts, resource limits
2. **Backend RPC** — nové metody na `IEnvironmentService` (CRUD komponenty, configmapy, secrety, deploy, stop)
3. **Backend K8s** — `K8sManifestGenerator` pro generování manifestů z DTO
4. **Backend K8s** — `EnvironmentResourceService` rozšíření pro configmap/secret CRUD, log streaming (SSE)
5. **Base images** — vytvořit `jervis-base-*` Docker images pro jednotlivé runtime
6. **UI Screen** — `EnvironmentManagerScreen` s `JListDetailLayout` a záložkami
7. **UI Formuláře** — editace komponent (image/source, ENV, porty, resource limits)
8. **UI ConfigMap/Secret** — CRUD formuláře pro key-value data
9. **UI Log viewer** — real-time log tailing (SSE stream → Flow)
10. **UI YAML viewer** — read-only manifest viewer
11. **Navigace** — přidat `Screen.EnvironmentManager`, menu položku

### Phase 3: Environments v Nastavení (zjednodušené)

V záložce Nastavení → Prostředí zůstane jen:
- Vytvořit nové prostředí (název, namespace)
- Přiřadit prostředí ke klientovi
- Smazat prostředí
- Odkaz "Otevřít v Environment Manageru" pro detailní konfiguraci

Detailní konfigurace se neprovádí v Nastavení — uživatel je přesměrován do Environment Manageru.

**Priorita:** High
**Complexity:** High
**Status:** Planned (Phase 2 + 3)

---

## Orchestrator & Agent Flow

### Non-blocking Coding Agent Invocation with Background Task Queue

**Problém:**
- Když orchestrator volá coding agenta (OpenHands, Claude, Junie) přes `JobRunner.run_coding_agent()`, volání je **blocking** – čeká na dokončení K8s Job
- Během čekání je orchestrator thread **blokovaný** a nemůže zpracovávat další tasky
- Semaphore (`_orchestration_semaphore`) je držen po celou dobu běhu coding agenta (až 30 minut)
- To omezuje throughput: jen jeden coding agent najednou, i když by mohly běžet paralelně
- Chybí mechanismus pro background processing s monitorováním stavu

**Současné chování:**
1. Orchestrator graph node (např. `execute.py`) volá `await job_runner.run_coding_agent()`
2. `run_coding_agent()` wait na K8s Job completion (polling every 10s, timeout 600-1800s)
3. Orchestrator thread blokovaný – semaphore držen – žádné další orchestration může běžet
4. Po dokončení: vrátí result, thread pokračuje, semaphore uvolněn

**Požadované chování (Non-blocking Async):**
1. Orchestrator graph node volá `await job_runner.run_coding_agent_async()`
2. `run_coding_agent_async()`:
   - Vytvoří K8s Job
   - Vrátí TaskDocument s `state = RUNNING` a `agentTaskId = jobName`
   - **Okamžitě vrátí control** – nečeká na dokončení
3. Orchestrator thread:
   - Uloží `agentTaskId` do LangGraph state
   - **Uvolní semaphore** – může zpracovávat další tasky
   - Vrátí `interrupt()` nebo `pending` state – graph se checkpointne
4. Background watcher (`AgentTaskWatcher`):
   - Spuštěný jako independent background service (ne v orchestrator threadu)
   - Periodicky polluje K8s Jobs s `agentTaskId` (nebo čeká na K8s events)
   - Detekuje job completion (success/failure)
   - Pro dokončený job:
     - Načte job result (logs, exit code, artifacts)
     - Najde odpovídající TaskDocument (podle agentTaskId)
     - Aktualizuje TaskDocument state → COMPLETED/FAILED
     - **Resume orchestration thread**:
       - Najde LangGraph thread_id pro tento task (uložené v TaskDocument)
       - Zavolá `resume_orchestration(thread_id, result)` – vrací control do graphu
       - Graph pokračuje od místa kde byl interruptnut
5. Multi-tasking:
   - Více orchestration threadů může běžet současně (každý s vlastním semaphore)
   - Každý může volat coding agenta async – všechny K8s Jobs běží paralelně
   - Watcher monitoruje všechny jobs a resume-uje odpovídající threads
6. Health monitoring (ping):
   - Watcher periodicky kontroluje "živost" coding agent jobs (stuck, no progress)
   - Pokud job přestane běžet nečekaně (pod deleted, crash), detekuje to
   - Může triggerovat restart jobu nebo fail task s error message
   - TaskDocument: přidat `agentHeartbeat` timestamp (update-ováno z jobu)
   - Pokud `agentHeartbeat` starší než X minut → považovat za dead

**Architektura:**

**A. TaskDocument Extensions**
```kotlin
data class TaskDocument(
    ...
    val agentTaskId: String? = null,      // K8s job name (for async agent tasks)
    val agentTaskState: String? = null,   // RUNNING, COMPLETED, FAILED
    val agentHeartbeat: Instant? = null,  // Last heartbeat from agent
    val agentResultJson: String? = null,  // Serialized result from agent
)
```

**B. JobRunner Async API**
- `run_coding_agent_async(task_id, agent_type, ...) -> AgentTaskDto`
  - Vytvoří K8s Job s unique name (job-{task_id}-{uuid})
  - Uloží job name do TaskDocument.agentTaskId
  - Vrátí AgentTaskDto{jobName, namespace, status="RUNNING"}
  - **Nepočká** na dokončení
- `get_agent_task_status(agentTaskId) -> AgentTaskStatusDto`
  - Poll K8s Job status (Complete/Failed/Running)
  - Pro Running: vrátit progress (pod phase, restart count, atd.)
- `cancel_agent_task(agentTaskId)` – delete K8s Job

**C. AgentTaskWatcher Service**
- Singleton service spuštěný při startupu orchestratoru
- Background loop (every 10s):
  1. Query: `TaskDocument.findByAgentTaskIdNotNullAndAgentTaskStateNotIn(COMPLETED, FAILED)`
  2. Pro každý task s agentTaskId:
     - Zavolat `job_runner.get_agent_task_status(agentTaskId)`
     - Pokud dokončeno:
       - Načíst job result (logs, exit code, artifacts)
       - Update TaskDocument (state, resultJson, completedAt)
       - Najít LangGraph thread_id (z TaskDocument.orchestratorThreadId)
       - Zavolat `resume_orchestration_streaming(thread_id, result)` (fire-and-forget)
     - Pokud still running: check heartbeat, detect stalls
  3. Remove stale tasks (older than timeout)
- Implementovat jako asyncio task v `main.py` (spustit při startupu)

**D. Orchestrator Graph Changes**
- Node `execute.py` (a `git_ops.py`, `epic.py`):
  - Místo `result = await job_runner.run_coding_agent(...)`
  - Použít `agentTask = await job_runner.run_coding_agent_async(...)`
  - Uložit `agentTaskId` do state: `state["agent_task_id"] = agentTask.jobName`
  - Vrátit `interrupt()` s `action="waiting_for_agent"` a `agent_task_id`
  - LangGraph checkpointne state (včetně agent_task_id)
- Nový node `wait_for_agent`:
  - Když graph resumed z interruptu, zkontrolovat:
    - Pokud agent task dokončeno → načíst result z TaskDocument, pokračovat
    - Pokud still running → znovu `interrupt()` (wait again)
  - Tento node běží vždy, když je `agent_task_id` present
- Simplified flow:
  ```
  execute → interrupt(waiting_for_agent) → checkpoint
  [background watcher monitored job, resumed when done]
  wait_for_agent → [resumed] → get result from TaskDocument → continue
  ```

**E. Concurrency & Semaphore**
- Aktuálně: `_orchestration_semaphore` limituje počet concurrent orchestrations (default 1)
- S async agent tasks:
  - Orchestrator thread **uvolní semaphore hned po dispatch** (ne po dokončení)
  - To umožňuje více orchestrations běžet současně (každý v jiném threadu)
  - Každý orchestration může mít svůj coding agent job běžící v background
  - Semaphore stále chrání před oversubscription (max concurrent orchestrations)
- Config: `MAX_CONCURRENT_ORCHESTRATIONS` (default 3-5)

**F. TaskDocument State Machine**
```
NEW → RUNNING (orchestration started)
    → PYTHON_ORCHESTRATING (dispatched to Python)
    → [interrupted] WAITING_FOR_AGENT (coding agent running)
    → [agent completed] COMPLETED/FAILED
```
- Stav `WAITING_FOR_AGENT` nový (nebo použít `USER_TASK` s flagem)
- TaskDocument: `agentTaskId`, `agentTaskState`, `agentResultJson`

**G. Health Monitoring & Recovery**
- AgentTaskWatcher:
  - Check `agentHeartbeat` (last log timestamp from agent)
  - Pokud žádný heartbeat > 5 minut → považovat za stuck
  - Stuck detection:
    - Log "Agent heartbeat missing for X minutes" do ErrorLog
    - Optionally: cancel K8s Job (if still running)
    - TaskDocument.state = ERROR, errorMessage = "Agent heartbeat timeout"
    - Resume orchestration s error → graph can handle failure
- Job failure handling:
  - K8s Job failed (exitCode != 0) → capture logs
  - TaskDocument.state = FAILED, agentResultJson = {error, logs}
  - Resume orchestration s error → graph can retry or fail task

**H. Result Retrieval**
- Když coding agent job dokončí:
  - Job result uložen do K8s (configmap, PVC, nebo stdout logs)
  - AgentTaskWatcher načte:
    - Exit code
    - Stdout/stderr logs (pro debugging)
    - Artifacts (changed files, PR links, atd.)
  - Serializuje do JSON a uloží do TaskDocument.agentResultJson
- Při resume orchestration:
  - Node `wait_for_agent` načte TaskDocument.agentResultJson
  - Parsuje result a předá do graph state
  - Graph pokračuje s result (success nebo error)

**I. Error Handling & Retries**
- Job creation failure:
  - `run_coding_agent_async()` může fail (invalid image, quota exceeded)
  - Vrátit error hned, orchestrator může retry s jiným agentem
- Job runtime failure:
  - Watcher detekuje failed job → TaskDocument.state = FAILED
  - Resume orchestration s error → graph can decide:
    - Retry with same agent (if transient)
    - Switch agent type (if agent-specific issue)
    - Fail task (if unrecoverable)
- Orchestrator pod restart:
  - TaskDocument.trvalé (MongoDB) – agentTaskId přežije restart
  - AgentTaskWatcher po restartu načte všechny RUNNING agent tasks a pokračuje v monitorování
  - LangGraph checkpointy v MongoDB – orchestration thread může být resumed

**J. Implementation Steps**
1. Extend TaskDocument (Kotlin) – add agentTaskId, agentTaskState, agentHeartbeat, agentResultJson
2. JobRunner (Python) – add `run_coding_agent_async()`, `get_agent_task_status()`, `cancel_agent_task()`
3. AgentTaskWatcher (Python) – new background service
4. Orchestrator graph nodes – modify `execute.py`, `git_ops.py`, `epic.py` to use async dispatch + interrupt
5. New node `wait_for_agent` – poll TaskDocument until agent task completes
6. Update `resume_orchestration()` – handle agent task result injection
7. Config – add `MAX_CONCURRENT_ORCHESTRATIONS`, agent heartbeat timeout
8. Testing – simulate long-running agent jobs, verify concurrent orchestrations

**Soubory:**
- `backend/server/src/main/kotlin/com/jervis/entity/TaskDocument.kt` – add agent task fields
- `backend/server/src/main/kotlin/com/jervis/dto/TaskDto.kt` – extend DTO
- `backend/service-orchestrator/app/agents/job_runner.py` – add async methods
- `backend/service-orchestrator/app/agent_task_watcher.py` – new watcher service
- `backend/service-orchestrator/app/graph/nodes/execute.py` – async dispatch
- `backend/service-orchestrator/app/graph/nodes/git_ops.py` – async dispatch
- `backend/service-orchestrator/app/graph/nodes/epic.py` – async dispatch
- `backend/service-orchestrator/app/graph/nodes/wait_for_agent.py` – new node
- `backend/service-orchestrator/app/main.py` – start AgentTaskWatcher on startup
- `backend/service-orchestrator/app/config.py` – add config options

**Priorita:** High (pro scalability)
**Complexity:** High
**Status:** Planned

**Poznámka:** Toto je kritické pro orchestrator scalability. Bez non-blocking agent invocation je orchestrator limitován na 1 úkol najednou. Tato změna umožní X koncurentních úkolů (configurabitelné) a výrazně zlepší throughput.

---

### Context Preservation During User Task Response

**Problém:**
Když uživatel odpoví na otázku v `USER_TASK` stavu (přes `sendToAgent()`), orchestrator ztrácí kontext:
- Agent neví, na co byla otázka (ztratil původní dotaz/kontext)
- Orchestrator nemá kontext proč se ptal a na co se ptal
- Předchozí orchestration nepokračuje správně v odpovědích
- Uživatelův response je uložen, ale orchestrator při resume nemá přístup k původnímu question context

**Příčina:**
Flow při USER_TASK response:
1. `UserTaskRpcImpl.sendToAgent()` upraví `task.content` = uživatelova odpověď, stav → `READY_FOR_GPU`
2. `ChatMessageDocument` se uloží s uživatelovou odpovědí
3. BackgroundEngine pickup: `agentOrchestrator.run(task, task.content)`
4. `AgentOrchestratorService.resumePythonOrchestrator()` volá `pythonOrchestratorClient.approve(threadId, approved, reason=userInput)`
5. Python `/approve/{thread_id}` → `resume_orchestration_streaming(thread_id, resume_value)`
6. Graph se resume-uje z checkpointu pomocí `Command(resume=resume_value)`

Checkpoint obsahuje:
- Graph state až k bodu `interrupt()` (včetně `messages` se systémovou otázkou)
- **NE** obsahuje nově přidanou `ChatMessageDocument` s uživatelovou odpovědí (přidána po checkpointu)

Problém: Když graph resume-uje, `respond.py` node dostane `user_response` = `resume_value` (dict s `approved` a `reason`). Tato hodnota se použije jako odpověď, ale **neexistuje způsob jak zjistit původní otázku**, protože:
- `interrupt()` value (original question) je v checkpointu, ale není automaticky dostupné po resume
- Chat history načtená přes `ChatHistoryService.prepareChatHistoryPayload()` může být **zastaralá** (načtená Před uživatelovou odpovědí)
- Graph state `messages` neobsahuje uživatelovu odpověď (je v DB, ale ne v state)

**Důsledek:** Orchestrator pokračuje s user response, ale bez kontextu "na co ta odpověď byla". To vede k:
- LLM neví, na jakou otázku se odpovídá
- Konverzační history je nekonzistentní
- Agent nemůže správně interpretovat odpověď

**Řešení – Enrich Chat History Before Resume:**

Cíl: Před dispatch/resume orchestratoru načíst **aktuální** chat history včetně právě přidané uživatelovy odpovědi.

**A. Synchronizace DB → Cache (Kotlin → Python)**
1. V `UserTaskRpcImpl.sendToAgent()` po `chatMessageRepository.save(userMessage)`:
   - Vynutit reload chat history cache (pokud existuje) nebo
   - Čekat krátce (100ms) na DB replikaci (ne ideální)
2. Lepší: Přidat cache invalidation mechanismus:
   - `ChatHistoryService` ukládá chat history do in-memory cache s TTL
   - `sendToAgent()` volá `chatHistoryService.invalidateCache(taskId)`
   - Při příštím `prepareChatHistoryPayload()` se načte čerstvá data z DB

**B. Explicit Context Passing (Robust)**
1. Extend `resumePythonOrchestrator()` v `AgentOrchestratorService.kt`:
   - Před `pythonOrchestratorClient.approve()` načíst **aktuální** chat history pro task
   - Přidat do `OrchestrateRequestDto` (při dispatch) nebo jako extra parametr při resume:
     ```kotlin
     val chatHistory = chatHistoryService.prepareChatHistoryPayload(task.id)
     // ... existing code ...
     pythonOrchestratorClient.approve(
         threadId = threadId,
         approved = approved,
         reason = userInput,
         chatHistory = chatHistory  // NOVÉ: poslat aktuální historii
     )
     ```
2. Python `/approve` endpoint:
   - Přijmout volitelný parametr `chat_history`
   - Při resume do grafu, **merge** chat_history do graph state před `Command(resume=resume_value)`
   - Nebo: uložit chat_history do MongoDB collection a graph node `respond.py` znovu načte

**C. Graph State Reconstruction (Simpler)**
1. V `resume_orchestration_streaming()` (Python):
   - Před `graph.astream_events(Command(resume=resume_value), ...)` načíst chat history z DB
   - Update config: `config["chat_history"] = fresh_history`
2. V `respond.py` node:
   - Místo použití `state["messages"]` z checkpointu, načíst fresh chat history z config/DB
   - Merge `resume_value` (user response) do messages před LLM call

**D. TaskDocument Context Fields (Alternative)**
Přidat do TaskDocument:
- `lastQuestionText` – poslední otázka od agenta
- `lastQuestionContext` – kontext (branch, goal, atd.)
- Tyto pole se nastavují při `interrupt()` (z Python grafu)
- Při `resumePythonOrchestrator()` se načtou z DB a předají do Pythonu

**Doporučené řešení (kombinace A + B):**
1. **Cache invalidation** v `sendToAgent()` – zajistí, že `prepareChatHistoryPayload()` vrátí čerstvá data
2. **Explicit chat history passing** v `resumePythonOrchestrator()` – posíláme aktuální historii jako parametr
3. **Python `/approve`** přijme `chat_history` a předá do grafu
4. **Graph `respond.py`** použije poskytnutou chat_history místo checkpoint `messages`

**Výhody:**
- Žádné race conditions (DB replikace)
- Explicitní control nad kontextem
- Snadno testovatelný
- Žádné nové DB pole na TaskDocument

**Soubory:**
- `backend/server/src/main/kotlin/com/jervis/rpc/UserTaskRpcImpl.kt` – invalidate cache po save
- `backend/server/src/main/kotlin/com/jervis/service/chat/ChatHistoryService.kt` – add cache + invalidate()
- `backend/server/src/main/kotlin/com/jervis/service/agent/coordinator/AgentOrchestratorService.kt` – pass chatHistory to approve
- `backend/service-orchestrator/app/main.py` – extend `/approve` endpoint to accept chat_history
- `backend/service-orchestrator/app/graph/orchestrator.py` – merge chat_history into state before resume
- `backend/service-orchestrator/app/graph/nodes/respond.py` – use provided chat_history

**Priorita:** High (kritický UX bug)
**Complexity:** Medium
**Status:** Analysis Complete

---

## UI & Chat Experience

### Token-by-Token Chat Streaming

**Problém:**
- Chat odpovědi přicházejí jako **kompletní zprávy** po dokončení LLM generování
- Infrastructure pro streaming existuje (`Flow<ChatResponseDto>`), ale zprávy jsou celé
- Uživatel nevidí průběžný progress během dlouhých odpovědí
- ChatGPT-style postupné vypisování textu je lepší UX

**Současný stav:**
- ✅ `Flow<ChatResponseDto>` subscription v UI
- ✅ Zprávy přicházejí asynchronně (fronta funguje)
- ❌ Orchestrator čeká na kompletní LLM response, pak emituje celou

**Řešení:**
1. **Backend streaming** - `respond.py` a další nodes musí použít LLM streaming API
   - OpenAI: `stream=True` v chat completion
   - Ollama: streaming endpoint
   - Collect tokens as they arrive

2. **Partial message emission** - emit `ChatResponseDto` s partial content
   - Add `isPartial: Boolean` flag to DTO
   - Add `messageId: String` to group partials
   - Emit incremental chunks via `emitToChatStream()`

3. **UI accumulation** - `MainViewModel` akumuluje partial messages
   - Group by `messageId`
   - Append text chunks to same message
   - Mark as complete when `isPartial=false`

4. **Markdown rendering** - Markdown renderer už podporuje partial content
   - `Markdown(content = accumulatedText)` recomposes on each chunk
   - Works seamlessly with existing implementation

**Implementace:**
- `backend/service-orchestrator/app/graph/nodes/respond.py` - streaming LLM calls
- `backend/service-orchestrator/app/llm/provider.py` - add streaming support
- `shared/common-dto/.../ChatMessageDto.kt` - add `isPartial`, `messageId` fields
- `shared/ui-common/.../MainViewModel.kt` - accumulate partial messages

**Priorita:** Medium
**Complexity:** Medium
**Status:** Planned

**Poznámka:** Infrastructure už existuje, jen potřebuje backend LLM streaming + partial emit logic.

