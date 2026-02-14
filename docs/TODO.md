# TODO – Plánované Features a Vylepšení

Tento dokument obsahuje seznam plánovaných features, vylepšení a refaktoringů,
které budou implementovány jako separate tickety.

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

## Environment Viewer UI (Phase 2)

### Standalone UI Screen for K8s Environment Inspection

**Prerequisite:** Phase 1 (backend + MCP) ✅ DONE — fabric8 K8s methods, EnvironmentResourceService, internal REST endpoints, MCP server, workspace manager integration, RBAC.

**Zbývá:**
- EnvironmentViewScreen v MainScreen.kt (sidebar s resource tree)
- K8sResourceTree component (expandable tree view pro pods/deployments/services)
- LogViewerDialog (real-time log tailing s SSE stream)
- YAML detail viewer (raw resource yaml/json)
- EnvironmentViewModel (kRPC calls to server)
- RPC endpoints pro UI (extend EnvironmentService → EnvironmentResourceService bridge)

**Priorita:** Medium
**Complexity:** Medium
**Status:** Planned (Phase 2)

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

---

## Security & Git Operations

### GPG Certificate Management for Coding Agents

**Problém:**
- Coding agenty (OpenHands, Claude, Junie) potřebují mít možnost podepisovat Git commity pomocí GPG
- Každý agent potřebuje mít přístup k vlastnímu GPG privátnímu klíči (certifikátu)
- Certifikáty musí být bezpečně uloženy na serveru a distribuovány agentům
- UI agent musí umožnit uživateli nahrát a spravovat certifikáty
- Orchestrator musí certifikáty předávat git agentovi a coding agentům při jejich startu

**Architektura:**

**1. Certificate Storage (Server)**
- Server ukládá GPG certifikáty v encrypted form v databázi
- Každý certifikát je asociován s:
  - User ID (kdo vlastní certifikát)
  - Agent type (git, coding, atd.)
  - Key ID (GPG key fingerprint)
  - Encrypted private key (pro export)
  - Passphrase (pokud je zašifrovaný klíč)

**2. UI Agent - Certificate Management**
- UI zobrazuje seznam existujících certifikátů
- Možnost nahrát nový certifikát:
  - Upload privátního klíče (PEM/ASCII armored)
  - Input passphrase (optional)
  - Vybrat typ agenta (git, coding)
- Možnost exportovat certifikát (pro backup)
- Možnost smazat certifikát
- Certifikáty se ukládají přes RPC na server

**3. Orchestrator - Certificate Distribution**
- Při startu agenta (git, coding) orchestrator načte příslušný certifikát z serveru
- Certifikát se předá agentovi přes RPC jako part of initialization
- Agent ukládá certifikát do svého local storage (encrypted)
- Agent používá certifikát pro GPG operace (git commit --gpg-sign)

**4. Git Agent Integration**
- Git agent přijme certifikát od orchestratoru
- Certifikát se importuje do GPG keyring (nebo použije jako detached signature)
- Git operace (clone, commit, push) používají certifikát pro signing
- Config: `git config commit.gpgsign true` a `user.signingkey <key-id>`

**5. Coding Agent Integration**
- Coding agent (OpenHands/Claude/Junie) přijme certifikát od orchestratoru
- Agent ukládá certifikát do secure storage (env variable, temp file)
- Při git commit operacích použije certifikát pro signing
- Agent musí vědět jak volat `git commit -S` s správným key

**Implementační kroky:**

**Phase 1: Backend - Certificate DTO & Storage**
1. Vytvořit `GpgCertificateDto` s poli:
   - `id: String`
   - `userId: String`
   - `agentType: String` (git, coding)
   - `keyId: String` (fingerprint)
   - `privateKeyEncrypted: String` (ASCII armored)
   - `passphrase: String?` (optional, encrypted at rest)
   - `createdAt: Instant`
2. Vytvořit `GpgCertificateDocument` (MongoDB entity)
3. Vytvořit `GpgCertificateRepository` (CRUD operace)
4. Pridat metodu do `CodingAgentSettingsService` pro správu certifikátů

**Phase 2: Server RPC Endpoints**
1. Rozšířit `CodingAgentSettingsRpc`:
   - `uploadGpgCertificate(certificate: GpgCertificateDto): String`
   - `getGpgCertificate(id: String): GpgCertificateDto?`
