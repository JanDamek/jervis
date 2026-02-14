# TODO – Plánované Features a Vylepšení

Tento dokument obsahuje seznam plánovaných features, vylepšení a refaktoringů,
které budou implementovány jako separate tickety.

## Workspace & Orchestrator Reliability

### Workspace Clone Failures and Orchestrator Unavailability

**Problém (převzatý z logu 2026-02-13):**

Logy ukazují na následující chyby pro projekt `nUFO`:

```
WORKSPACE_CLONE_FAILED: project=nUFO projectId=6899c2575ec8291c20f1a038 
resources=[mazlusek/moneta/nufo(conn=6986002d8bf32b35197e2bf2), 
mazlusek/moneta/ufo_engine(conn=6986002d8bf32b35197e2bf2), ...] lastCheck=2026-02-13T22:37:03.359Z
ORCHESTRATOR_UNAVAILABLE: correlationId=git:1abdfadca923b5e9e5654bfbeae45f7dd6a986f6
PYTHON_DISPATCH_BUSY: taskId=698f4c5b9e3e128db62ddbd2 — resetting to READY_FOR_GPU for silent retry
```

**Root Cause Analysis:**

1. **Workspace Clone Failure** (`WORKSPACE_CLONE_FAILED`):
   - Projekt `nUFO` má 7 git resources (nufo, ufo_engine, ufo_mediator, ufo_repository, ufo_framework, ufo_compiler, ufo_dependencies)
   - `GitRepositoryService.ensureAgentWorkspaceReady()` vrátila `null` pro alespoň jeden resource
   - Možné důvody:
     - Neplatné nebo vypršené přihlašovací údaje (OAuth2 token expired, invalid password)
     - Síťový problém (timeout, connection refused)
     - Neplatné repository URL
     - Permission denied (uživatel nemá přístup k repozitáři)
     - Repository neexistuje (404)
   - Když `ensureAgentWorkspaceReady()` selže, vrátí `null` a `initializeProjectWorkspace()` nastaví `workspaceStatus = CLONE_FAILED`
   - **Status CLONE_FAILED je PERMANENTNÍ** - neautomaticky se neopraví, vyžaduje zásah uživatele (oprava credentials, síťové připojení, atd.)

2. **Orchestrator Unavailability** (`ORCHESTRATOR_UNAVAILABLE`):
   - `AgentOrchestratorService.dispatchToPythonOrchestrator()` kontroluje `pythonOrchestratorClient.isHealthy()`
   - Pokud health check selže (HTTP neúspěšný, timeout, connection error), dispatch vrací `false`
   - `AgentOrchestratorService.run()` pak vrátí error "Orchestrátor není momentálně dostupný. Zkuste to prosím později."
   - Možné důvody:
     - Python orchestrator služba není running (pád, restart)
     - Python orchestrator je přetížený (všechny workery busy)
     - Síťový problém mezi Kotlin serverem a Python orchestratorem
     - Health check endpoint `/health` neodpovídá

3. **Python Dispatch Busy** (`PYTHON_DISPATCH_BUSY`):
   - Toto je **důsledek** orchestrator unavailability, ne samostatný problém
   - Když `dispatchToPythonOrchestrator()` vrátí `false` (orchestrator unavailable), task zůstane ve stavu `DISPATCHED_GPU`
   - `BackgroundEngine.executeTask()` detekuje, že `freshTask.state == DISPATCHED_GPU` a `finalResponse.message.isNotBlank()` (error zpráva)
   - Task se resetuje na `READY_FOR_GPU` pro silent retry po 15 sekundách
   - Tento retry cyklus se opakuje, dokud orchestrator nebude dostupný nebo workspace nebude ready

**Současné chování:**

- Při `CLONE_FAILED`: Uživatel dostane hlášku "Příprava prostředí selhala. Zkontrolujte připojení k repozitáři a zkuste to znovu." Task se NEresetuje, zůstane ve stavu `READY_FOR_GPU` (nebo `DISPATCHED_GPU`), ale dispatch selže znovu a znovu
- Při `ORCHESTRATOR_UNAVAILABLE`: Uživatel dostane hlášku "Orchestrátor není momentálně dostupný. Zkuste to prosím později." Task se resetuje na `READY_FOR_GPU` a automaticky se retryuje po 15s
- **Žádné automatické recovery** z `CLONE_FAILED` stavu - projekt musí být manuálně opraven (credentials, connection settings)
- **Žádné alerty/notifikace** pro adminy o trvalých selháních

**Problémy s aktuální implementací:**

1. **Workspace status se neaktualizuje automaticky** - `CLONE_FAILED` zůstává navždy, dokud uživatel manuálně neaktualizuje connection nebo project
2. **Retry loop může být neukončující** - pokud je orchestrator down trvale, tasky se neustále resetují a retryují (15s interval), což generuje nepotřebný log noise a DB zátěž
3. **Chybí viditelnost** - UI nezobrazuje workspace status ani orchestrator health, uživatel neví proč tasky selhávají
4. **Chybí alerty** - admini nejsou notifikováni o kritických selháních (workspace clone failed, orchestrator down)
5. **Connection invalidation je agresivní** - `GitRepositoryService` při auth erroru okamžitě označí connection jako `INVALID`, což může být přehnané (token může být jen vypršený a může se refreshnout)
6. **No backoff pro workspace retry** - `initializeProjectWorkspace()` se volá při startu a při každém `CLONE_FAILED` (BackgroundEngine.ř.911-916), ale bez žádného backoffu - pokusy se opakují okamžitě při každém startu

**Navrhovaná řešení:**

#### 1. Workspace Recovery Mechanism

**A. Exponential Backoff pro Workspace Retry**
- Přidat `lastWorkspaceAttempt` a `workspaceRetryCount` do `ProjectDocument`
- Při `CLONE_FAILED` neokamžitě retryovat, ale s exponential backoff (1h, 2h, 4h, 8h, 24h, ...)
- Implementovat jako `TaskQualificationService` retry logiku (už existuje pro qualification)
- Background loop (přidat do `BackgroundEngine`) periodicky kontrolovat projects s `CLONE_FAILED` a zkusit re-initializovat (pokud backoff uplynul)

**B. Connection State Machine Refinement**
- Rozlišit mezi `INVALID` (permanent failure - wrong credentials, no access) a `EXPIRED` (temporary - token can be refreshed)
- `INVALID` connections: manuální oprava required (user must update credentials)
- `EXPIRED` connections: automatický refresh pomocí OAuth2 (už existuje `oauth2Service.refreshAccessToken()`)
- Při `GitAuthenticationException` zkontrolovat, zda je refresh possible, pokud ano → neinvalidate, jen log warning

**C. Workspace Status Details**
- Rozšířit `WorkspaceStatus` o více podrobností:
  - `CLONE_FAILED_AUTH` - auth error (credentials)
  - `CLONE_FAILED_NETWORK` - network/timeout
  - `CLONE_FAILED_NOT_FOUND` - repo neexistuje
  - `CLONE_FAILED_UNKNOWN` - other
- Uložit `lastWorkspaceError` a `workspaceErrorMessage` do `ProjectDocument` pro lepší diagnostiku
- UI: zobrazit konkrétní error message a suggerovat akci (e.g., "Přihlašovací údaje vypršely, prosím aktualizujte připojení")

#### 2. Orchestrator Health Monitoring & Circuit Breaker

**A. Enhanced Health Check**
- Python orchestrator: `/health` endpoint vrací `{status: "ok"|"error", busy: boolean, reason?: string}`
- Kotlin: `PythonOrchestratorClient.isHealthy()` by měl detekovat i `status != "ok"` (ne jen connection success)
- Logovat `reason` z health check response pro lepší diagnostiku

**B. Circuit Breaker Pattern**
- Implementovat circuit breaker pro orchestrator calls (pomocí `io.github.resilience4j:resilience4j-spring-boot3` nebo custom)
- Config: failure threshold = 5 consecutive failures, timeout = 30s, half-open state after 60s
- Když circuit breaker je OPEN → okamžitě fail (ne čekat na timeout), log "Circuit breaker OPEN - orchestrator unavailable"
- Background loop periodicky testovat circuit breaker (half-open) a resetovat při success

**C. Orchestrator Status Dashboard**
- Přidat endpoint `/internal/orchestrator-health` pro monitoring (nebo rozšířit existující)
- Vracet: `{healthy: boolean, busy: boolean, activeThreads: number, uptime: string, lastError?: string}`
- K8s liveness/readiness probes využívat tento endpoint
- Prometheus metriky: `jervis_orchestrator_healthy`, `jervis_orchestrator_active_threads`

#### 3. Task Retry Throttling

**A. Exponential Backoff pro Orchestrator Unavailable**
- Currently: 15s fixed delay (BackgroundEngine.ř.350)
- Vylepšit: exponential backoff s max limit (podobně jako qualification)
- Přidat `orchestratorRetryCount` a `nextOrchestratorRetryAt` do `TaskDocument`
- Při resetu `READY_FOR_GPU` nastavit `nextOrchestratorRetryAt` s backoff (5s, 15s, 30s, 60s, 5min, ...)
- `runExecutionLoop()` before picking task check `nextOrchestratorRetryAt` - skip if future

**B. Stuck Task Detection**
- `PYTHON_ORCHESTRATING` tasks s `orchestrationStartedAt` starší než 1h → automaticky resetovat na `READY_FOR_GPU`
- Toto už existuje v `checkOrchestratorTaskStatus()` (HEARTBEAT_DEAD_THRESHOLD_MINUTES = 10), ale heartbeat je optional
- Pokud heartbeat není implementován ve všech orchestrator typech, fallback na časy

#### 4. Alerting & Notifications

**A. Error Logging Enhancement**
- Při `CLONE_FAILED` nebo `ORCHESTRATOR_UNAVAILABLE` (opakovaně) logovat na ERROR level s kontextem (project name, connection name, error details)
- Už existuje `ErrorLogService` - použít ho pro trvalé error záznamy

**B. Admin Notifications**
- Nový `AdminNotificationService` - posílat email/Slack/webhook při kritických chybách
- Triggers:
  - Project workspace `CLONE_FAILED` (first occurrence + daily digest if persists)
  - Orchestrator unhealthy for >5min
  - >5 tasks reset due to orchestrator unavailability in last 10min
- Config: `adminNotifications.enabled`, `admin.email`, `slack.webhook`

**C. UI Indicators**
- `MainScreen`: zobrazit banner pokud:
  - Aktuální project má `workspaceStatus != READY` (zobrazit "Workspace not ready" s tooltipem s detail)
  - Orchestrator je unhealthy (zobrazit "Orchestrator unavailable" banner)
- `ProjectSettingsScreen`: zobrazit workspace status a last error pro každý project
- `ConnectionSettingsScreen`: zobrazit connection state (VALID/INVALID/EXPIRED) a last check time

#### 5. Manual Recovery Actions

**A. Project Workspace Reset**
- UI tlačítko "Retry Workspace Initialization" v project settings
- RPC: `ProjectRpc.retryWorkspace(projectId)` → resetuje `workspaceStatus` na `null` nebo `CLONING` a triggeruje `initializeProjectWorkspace()`
- Backend: metoda `projectService.retryWorkspaceInit(projectId)`

**B. Connection Test**
- Nový RPC: `ConnectionRpc.testConnection(connectionId): ConnectionTestResult`
- Test: zkusit clone do temp directory, pak smazat
- Result: `{success: boolean, error?: string, latencyMs: long}`
- UI: tlačítko "Test Connection" v connection settings

**C. Orchestrator Restart Trigger**
- K8s: liveness probe → restart orchestrator pod pokud unhealthy
- Manual: RPC `OrchestratorControlRpc.restart()` (pro emergency)
- UI: tlačítko "Restart Orchestrator" (admin only)

**Implementační kroky (priority order):**

1. **High:** Workspace status details + error persistence (1.A, 1.C)
2. **High:** Circuit breaker pro orchestrator calls (2.A, 2.B)
3. **High:** Task retry throttling (3.A)
4. **Medium:** Admin notifications (4.A, 4.B)
5. **Medium:** UI indicators (4.C)
6. **Low:** Manual recovery actions (5.A, 5.B, 5.C)
7. **Low:** Connection state machine refinement (1.B)

**Soubory k modifikaci:**

- `backend/server/src/main/kotlin/com/jervis/entity/ProjectDocument.kt` - přidat `lastWorkspaceError`, `workspaceErrorMessage`, `workspaceRetryCount`, `lastWorkspaceAttempt`, `nextWorkspaceRetryAt`
- `backend/server/src/main/kotlin/com/jervis/entity/WorkspaceStatus.kt` (nebo v ProjectDocument) - rozšířit enum o pod-stavy
- `backend/server/src/main/kotlin/com/jervis/service/background/BackgroundEngine.kt` - přidat workspace retry loop, circuit breaker, task retry throttling
- `backend/server/src/main/kotlin/com/jervis/configuration/PythonOrchestratorClient.kt` - vylepšit health check, přidat circuit breaker
- `backend/server/src/main/kotlin/com/jervis/service/error/ErrorLogService.kt` - trvalé error záznamy pro workspace/orchestrator failures
- `backend/server/src/main/kotlin/com/jervis/rpc/ProjectRpcImpl.kt` - `retryWorkspace()` metoda
- `backend/server/src/main/kotlin/com/jervis/rpc/ConnectionRpcImpl.kt` - `testConnection()` metoda
- `shared/ui-common/.../MainViewModel.kt` - orchestrator health a workspace status monitoring
- `shared/ui-common/.../screens/ProjectSettingsScreen.kt` - workspace status display, retry button
- `shared/ui-common/.../screens/ConnectionSettingsScreen.kt` - connection test button

**Priorita:** High (kritické pro reliability a user experience)
**Complexity:** Medium-High
**Status:** Analysis complete, awaiting implementation

---

## Autoscaling & Performance

### KB Autoscaling při Read Timeout

**Status:** ✅ **IMPLEMENTED** (2026-02-12) – KB split na read/write deployments

**Implementace:**
- ✅ KB rozděleno na dva samostatné deployments:
  - `jervis-knowledgebase-read`: 5 replik, `KB_MODE=read`, high priority
  - `jervis-knowledgebase-write`: 2 repliky, `KB_MODE=write`, normal priority
- ✅ Dva samostatné Services:
  - `jervis-knowledgebase` → read deployment (orchestrator, retrieve operace)
  - `jervis-knowledgebase-write` → write deployment (server indexing, ingest operace)
- ✅ Orchestrator timeout zvýšen z 10s na 120s (pro případ velkého zatížení)
- ✅ Server používá write endpoint pro všechny ingest operace
- ✅ Read operace nejsou blokovány write operacemi (separate Ollama request queues)

**Soubory:**
- `k8s/app_knowledgebase.yaml` – split deployments + services + PriorityClass
- `backend/service-orchestrator/app/kb/prefetch.py` – timeout 120s
- `backend/server/src/main/kotlin/com/jervis/configuration/properties/EndpointProperties.kt` – `knowledgebaseWrite` property
- `backend/server/src/main/kotlin/com/jervis/configuration/RpcClientsConfig.kt` – write endpoint helper
- `k8s/configmap.yaml` – separate URLs pro read/write
- `k8s/build_kb.sh` – deploy both read+write deployments

**Priorita:** ~~Medium~~ **Done**
**Complexity:** Simple

---

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

## Orchestrator & Agent Flow

### User Interaction Pause/Resume

**Problém:**
- Když agent (OpenHands, Claude, Junie) potřebuje user input, posílá dotaz do chatu
- **Orchestrator thread stále běží a blokuje se** - čeká na odpověď
- Orchestrator nemůže zpracovávat další tasky pro backend
- Agent nemá důvod dál běžet, měl by se zastavit
- Chybí status `WAITING_FOR_USER_INPUT`

**Současné chování:**
1. Agent pošle message do chatu (ask_user tool)
2. Orchestrator thread **běží dál a blokuje**
3. User odpoví v chatu
4. Odpověď se vrátí do agenta
5. Thread se uvolní

**Požadované chování:**
1. Agent pošle message do chatu (ask_user tool)
2. Orchestrator nastaví task status → `WAITING_FOR_USER_INPUT`
3. **Thread se ukončí** (uvolní orchestrator pro další práci)
4. LangGraph checkpoint se uloží do MongoDB
5. User odpoví v chatu
6. Backend detekuje odpověď → zavolá orchestrator API `/resume/{thread_id}`
7. Orchestrator načte checkpoint a pokračuje od ask_user node

**Implementace:**
1. Přidat `WAITING_FOR_USER_INPUT` do TaskStatus enum (Kotlin + Python)
2. Python orchestrator: `ask_user` tool node nastaví status a vrátí `interrupt()`
3. LangGraph checkpoint se automaticky uloží
4. Kotlin: nový endpoint `POST /chat/{taskId}/user-message` - přijme odpověď, zavolá orchestrator `/resume`
5. Python: endpoint `POST /resume/{thread_id}` - načte checkpoint, pokračuje
6. UI: když task je WAITING_FOR_USER_INPUT, zobrazit input pole v chatu

**Soubory:**
- `shared/common-dto/.../TaskDto.kt` – přidat `WAITING_FOR_USER_INPUT` status
- `backend/service-orchestrator/app/models.py` – přidat status do TaskStatus enum
- `backend/service-orchestrator/app/graph/nodes/` – ask_user node s interrupt()
- `backend/service-orchestrator/app/main.py` – POST `/resume/{thread_id}` endpoint
- `backend/server/.../rpc/AgentOrchestratorRpcImpl.kt` – nový `resumeWithUserInput()` method
- `shared/ui-common/.../ChatScreen.kt` – input pole pro WAITING_FOR_USER_INPUT tasky

**Priorita:** High
**Complexity:** Medium
**Status:** Planned

**Poznámka:** Toto je kritické pro multi-tasking orchestratoru. Jeden blokovaný task nesmí zastavit zpracování ostatních tasků.

---

### Frontend Queue Display for Inline Messages During Agent Execution

**Problém:**
- Když uživatel pošle další zprávu do chatu, zatímco agent (Python orchestrator) ještě zpracovává předchozí požadavek (stav tasku = `PYTHON_ORCHESTRATING`), nová zpráva se uloží do stejného FOREGROUND tasku (inline message)
- Tato nová zpráva se NEzobrazuje v UI frontě (foreground queue), protože `getPendingForegroundTasks()` vrací pouze tasky se stavem `READY_FOR_GPU`
- UI zobrazuje pouze aktuálně běžící task (runningTask) a samostatnou frontu (foregroundQueue), ale inline messages jsou "neviditelné"
- Pouze po dokončení předchozího tasku se fronta aktualizuje a nové zprávy se pak zobrazí

**Požadavek:**
- Inline messages (tasky ve stavu `PYTHON_ORCHESTRATING`) se mají zobrazovat v UI frontě jako součást fronty
- User by měl vidět, že do fronty bylo přidáno dalších X zpráv, zatímco agent pracuje

**Root Cause:**
- `TaskService.getPendingForegroundTasks()` filtruje pouze `TaskStateEnum.READY_FOR_GPU`
- Tasky ve stavu `PYTHON_ORCHESTRATING` jsou vyloučeny, i když mají nově přidané inline messages
- `getPendingTasks()` vrací runningTask separately, ale UI foregroundQueue zobrazuje pouze z `getPendingForegroundTasks()`

**Řešení:**
1. Rozšířit `getPendingForegroundTasks()` o vrácení tasků ve stavu `PYTHON_ORCHESTRATING` (kromě `READY_FOR_GPU`)
2. Povolit vícevrástý `findByProcessingModeAndStateOrderByQueuePositionAsc()` s `Collection<TaskStateEnum>` parametrem
3. Aktualizovat repository metodu `findByProcessingModeAndStateOrderByQueuePositionAsc()` → `findByProcessingModeAndStateInOrderByQueuePositionAsc()`

**Soubory:**
- `backend/server/src/main/kotlin/com/jervis/service/background/TaskService.kt` – upravit `getPendingForegroundTasks()` pro includes `PYTHON_ORCHESTRATING`
- `backend/server/src/main/kotlin/com/jervis/repository/TaskRepository.kt` – přidat metodu `findByProcessingModeAndStateInOrderByQueuePositionAsc()`

**Priorita:** High (UI/UX - viditelnost fronty)
**Complexity:** Simple
**Status:** Planned

---

## UI & UX

### Populate Workflow Steps in Chat Messages

**Status:** ✅ Implemented - workflow steps displayed in chat

**Hotovo:**
- ✅ ChatMessage DTO extended with `workflowSteps: List<WorkflowStep>`
- ✅ UI component `WorkflowStepsDisplay` shows steps, tools, status
- ✅ Icons for step status (✓ completed, ✗ failed, ↻ in-progress, ⏰ pending)
- ✅ `OrchestratorWorkflowTracker` - backend in-memory tracker for workflow steps
- ✅ `/internal/orchestrator-progress` endpoint tracks nodes per task
- ✅ `OrchestratorStatusHandler` attaches workflow steps to final message
- ✅ `ChatMessageDocument.metadata` stores serialized workflow steps
- ✅ `ChatMessageDto` extended with metadata field
- ✅ `MainViewModel` deserializes workflow steps from metadata
- ✅ Node names mapped to Czech labels (intake → "Analýza úlohy", etc.)

**TODO (enhancement):**
- Extrahovat použité tools z každého node (např. `github_search`, `read_file`, `web_search`) - requires LangGraph state access

**Soubory:**
- `backend/server/.../service/agent/coordinator/OrchestratorWorkflowTracker.kt` - NEW tracker
- `backend/server/.../rpc/KtorRpcServer.kt` - progress endpoint updates tracker
- `backend/server/.../service/agent/coordinator/OrchestratorStatusHandler.kt` - attaches steps to final message
- `shared/ui-common/.../MainViewModel.kt` - deserializes workflow steps
- `shared/common-dto/.../ChatMessageDto.kt` - metadata field added

**Priorita:** ~~Medium~~ **Done**
**Complexity:** Simple

---

## Agent Memory & Knowledge

### User Context & Preferences Ingestion

**Problém:**
- Když uživatel poskytne kontext ("jsem z Palkovic", "máme traktor z Montfildu", "preferuji Kotlin idiomaticky"), agent si to nepamatuje pro budoucí konverzace
- Tyto informace se ztratí po komprimaci chat history
- Agent nemá strukturovaný způsob, jak ukládat a vyhledávat user preferences a context
- **Chybí kategorizace ("škatulky")** - agent nedokáže správně najít relevantní context

**Architektura:**

**Kategorie KB entit pro user context:**
1. **User Preferences** - coding style, tooling, workflow preferences
   - `preferuji Kotlin idiomaticky, ne Java styl`
   - `používám IntelliJ IDEA`
   - `commit messages v angličtině`

2. **Domain Context** - business domain, industry, vertical
   - `jsme z Palkovic` (location)
   - `máme traktor z Montfildu` (equipment, vendor)
   - `vyvíjíme AI asistenta pro software engineering`

3. **Team & Organization** - people, roles, processes
   - `Jan je tech lead`
   - `používáme Scrum s 2-week sprinty`
   - `code review povinné před merge`

4. **Technical Stack** - frameworks, libraries, patterns
   - `Kotlin Multiplatform`
   - `Compose for UI`
   - `MongoDB + ArangoDB`

**Implementace:**
1. **Real-time extraction** - během konverzace detekovat user context pomocí LLM
   - Patterns: "jsem z...", "používám...", "preferuji...", "máme..."
   - Extract entity type + value

2. **Immediate KB ingestion** - uložit do KB okamžitě (ne čekat na task completion)
   - Category-based indexing (User Preferences, Domain Context, atd.)
   - Graph connections: user → preference → context

3. **Semantic search** - embeddings pro context retrieval
   - Při orchestraci načíst relevantní user context
   - "Coding style preferences" → vyhledá "preferuji Kotlin idiomaticky"

4. **Context injection** - přidat do system promptu pro agenty
   - Dynamicky sestavit context block z KB
   - Relevantní pro aktuální úkol

**Soubory:**
- `backend/service-orchestrator/app/context/extractor.py` - LLM-based context extraction
- `backend/service-knowledgebase/app/services/user_context_service.py` - category-based ingestion
- `backend/service-knowledgebase/app/models/context_categories.py` - enum for categories
- `backend/service-orchestrator/app/context/injector.py` - inject context into prompts

**Priorita:** High
**Complexity:** Medium
**Status:** Planned

**Poznámka:** Toto je kritické pro long-term agent personalization. Agent musí znát user preferences a domain context, aby poskytoval relevantní odpovědi bez opakování stejných dotazů.

---

### Dvouúrovňová paměťová architektura

**Problém:**
- Agent má jen **chat history** (krátkodobá paměť) - komprese starých zpráv do `ChatSummaryDocument`
- **Chybí KB ingestion** dokončených tasků - dlouhodobá strukturovaná paměť
- Když agent dokončí úkol, výsledek se neukládá do KB pro budoucí použití

**Předchozí řešení – KB Query Transformation (2026-02-13):**

**Problém s KB vyhledáváním:**
- Orchestrator přímo používal uživatelský dotaz (v libovolném jazyce) pro KB vyhledávání
- Např. dotaz "ukaž mi co najdeš v KB pro email jazyková škola" (čeština) vracel 0 výsledků
- KB obsahuje anglický technický obsah, takže přímé dotazy v češtině nebo příliš specifické nefungovaly

**Řešení – LLM-based query transformation:**
1. V `intake` node přidána funkce `transform_user_query_to_kb_queries()` která pomocí LLM převádí uživatelský dotaz na 2-3 obecné anglické technické vyhledávací termíny
2. Transformované dotazy se ukládají do stavu (`kb_search_queries`) a používají pro všechny KB dotazy v rámci celého orchestration workflow
3. V `prefetch.py` upraveny funkce `prefetch_kb_context()` a `fetch_project_context()` pro podporu více vyhledávacích dotazů (sequentially, first that returns results)
4. V `evidence.py` a `intake.py` předány `search_queries` do prefetch funkcí

**Výhody:**
- KB vyhledávání nyní funguje nezávisle na jazyce uživatele
- Více šancí na nalezení relevantního obsahu (více variant dotazů)
- Deduplikace výsledků (uniqueness by sourceUrn)
- Fallback na původní dotaz, pokud transformace selže

**Soubory:**
- `backend/service-orchestrator/app/graph/nodes/intake.py` – query transformation + state storage
- `backend/service-orchestrator/app/kb/prefetch.py` – multi-query support
- `backend/service-orchestrator/app/graph/nodes/evidence.py` – use transformed queries

**Priorita:** High
**Complexity:** Medium
**Status:** ✅ **IMPLEMENTED**

---

**Architektura:**

**1. Local Memory (Chat History)** - krátkodobá, dočasná
- Průběžná konverzace (back-and-forth)
- MongoDB `ChatMessageDocument` + `ChatSummaryDocument` (komprese)
- **Levné**, rychlé, dočasné
- ✅ **Již implementováno** (`ChatHistoryService`)

**2. Knowledge Base** - dlouhodobá, strukturovaná
- **Jen významné celky** co dávají smysl jako celek
- Dokončené úkoly, implementace, rozhodnutí
- Code patterns, best practices, lessons learned
- ArangoDB graph + Weaviate embeddings
- **Drahé** (embedding, graph), trvalé
- ❌ **CHYBÍ ingestion dokončených tasků**

**Kdy ukládat do KB:**
- ✅ Task COMPLETED → extract outcomes, ingest to KB
- ✅ Architektonické rozhodnutí přijato
- ✅ Bug vyřešen (root cause + fix)
- ✅ Feature implementován (design + code)
- ❌ Nedokončená konverzace
- ❌ Exploratorní dotazy ("co je X?")
- ❌ Back-and-forth debugging (to je pro chat history)

**Co extrahovat z dokončeného tasku:**
1. **Outcome summary** - co bylo vyřešeno, jak
2. **Key decisions** - proč zvoleno řešení X místo Y
3. **Code patterns** - použité patterns, best practices
4. **Artifacts** - changed files, PRs, commits
5. **Related entities** - project, client, capabilities
6. **Lessons learned** - co fungovalo, co ne

**Implementace:**
1. `OrchestratorStatusHandler` - když task → COMPLETED, trigger KB ingestion
2. `TaskFinalizationService` - extract meaningful data z chat history
3. LLM-based summarization - distill conversation → structured outcome
4. `KnowledgeService.ingestTaskOutcome()` - ingest do KB
5. Graph connections - task → project → client → capabilities
6. Embeddings - outcome summary pro semantic search

**Soubory:**
- `backend/server/.../service/task/TaskFinalizationService.kt` - extract outcomes
- `backend/server/.../service/agent/coordinator/OrchestratorStatusHandler.kt` - trigger ingestion
- `backend/service-knowledgebase/app/api/routes.py` - POST `/ingest-task-outcome`
- `backend/service-orchestrator/app/summarization/` - LLM summarization

**Priorita:** High
**Complexity:** Medium
**Status:** Planned

**Poznámka:** Toto je kritické pro long-term agent memory. Agent si musí pamatovat co udělal, aby se z toho učil a neřešil stejné problémy znovu.

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

### Refactoring Pending Message System

**Problém:**
- **Pending message retry logika je křehká** a způsobuje nežádoucí re-execution tasků
- PendingMessageStorage ukládá zprávy do persistent storage (survives app restart)
- Při reconnectu se pending message automaticky retry → resetuje DISPATCHED_GPU task → agent znovu zpracovává staré zprávy
- **Časování je problematické**: message cleared po RPC success, ne po server confirmation
- Není žádný timeout - staré pending messages (dny/týdny) se retry i když už nejsou relevantní
- Chybí viditelnost pro uživatele - není jasné, že message je pending a bude retry

**Současné chování:**
1. User pošle zprávu → `sendMessage()` volá RPC
2. Při chybě → `pendingMessage = text`, `PendingMessageStorage.save(text)`
3. App restart → `init` načte pending message z persistent storage
4. `reloadHistory()` → `pendingMessage?.let { sendMessage() }` → automatický retry
5. ❌ **Problém**: Pokud RPC uspěl ale nepřišlo potvrzení ze streamu, zpráva zůstane pending
6. ❌ **Problém**: Staré zprávy (hodiny/dny staré) se retry i když už nejsou relevantní
7. ❌ **Problém**: Retry při reconnectu může resetovat DISPATCHED_GPU task → re-execution

**Quick fix (implementováno 2026-02-13):**
- ✅ Pending message se maže až po **server confirmation** (USER_MESSAGE echo ze streamu), ne po RPC success
- ✅ Zkontroluje se `pendingMessage == response.message` před vymazáním
- Stále zbývá refactoring celého systému

**Požadovaný refactoring:**
1. **Timeout pro pending messages** - zprávy starší než X hodin automaticky zahodit
   - Config: `PENDING_MESSAGE_TIMEOUT_HOURS = 24` (default)
   - Při načtení z storage: zkontrolovat timestamp, zahodit staré
   - Metadata: `{text: string, timestamp: ISO8601, attemptCount: number}`

2. **Viditelnost pro uživatele** - jasně ukázat pending message v UI
   - Banner/alert: "Máte neodeslané zprávy: [text] (před X hodinami)"
   - Tlačítka: "Odeslat znovu" | "Zahodit"
   - Ne automatický retry bez user confirmation pro staré zprávy (>1h)

3. **Exponential backoff pro retry** - ne okamžitý retry při každém reconnectu
   - První retry: okamžitě
   - Druhý retry: po 5s
   - Třetí retry: po 30s
   - Čtvrtý retry: po 5min
   - Pak už jen manuální retry z UI

4. **Server-side deduplication** - prevent duplicate processing
   - Client-side message ID: `UUID.randomUUID()` při vytváření zprávy
   - RPC: `ChatRequestDto(text, context, messageId)`
   - Server: zkontrolovat `ChatMessageDocument.clientMessageId` - skip duplicates
   - Idempotence: stejný messageId = skip, vrátit success

5. **Robustnější error handling** - rozlišit typy errorů
   - Network error (timeout, connection refused) → retry má smysl
   - Server error (400, 500) → retry nemá smysl, user musí opravit
   - UI: zobrazit konkrétní error, ne generický "Nepodařilo se odeslat"

**Implementace:**
- `shared/ui-common/.../MainViewModel.kt` - timeout check, exponential backoff
- `shared/ui-common/.../storage/PendingMessageStorage.kt` - metadata support (timestamp, attemptCount)
- `shared/ui-common/.../screens/MainScreen.kt` - pending message banner UI
- `shared/common-dto/.../ChatRequestDto.kt` - add `messageId: String?` field
- `backend/server/.../rpc/AgentOrchestratorRpcImpl.kt` - deduplication check
- `backend/server/.../entity/ChatMessageDocument.kt` - add `clientMessageId: String?` field

**Priorita:** High (aktuálně způsobuje nežádoucí re-execution tasků)
**Complexity:** Medium
**Status:** Planned (quick fix implementován, full refactoring pending)

**Poznámka:** Pending message systém je kritický pro UX, ale současná implementace je křehká. Full refactoring vyžaduje změny v DTO, server logice a UI.

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
