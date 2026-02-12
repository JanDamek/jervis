# TODO – Plánované Features a Vylepšení

Tento dokument obsahuje seznam plánovaných features, vylepšení a refaktoringů,
které budou implementovány jako separate tickety.

## Autoscaling & Performance

### KB Autoscaling při Read Timeout

**Problém:**
- KB může být přetížený při velkém množství současných ingest operací
- Orchestrator dostává read timeouts při čekání na embedding/retrieve
- 90% času KB stojí na Ollama při embeddingu (zápis) a generování vrcholů/hran (retrieve)
- CPU/RAM metriky nejsou indikátorem zátěže

**Řešení:**
- Orchestrator při read timeout může dynamicky zvýšit repliky KB deployment (kubectl scale)
- Default: 1 replika
- Max: 5 replik
- Scale-up trigger: read timeout při komunikaci s KB
- Scale-down: zatím bez automatiky (není dobrá metrika)

**Implementace:**
1. Orchestrator permissions: přidat RBAC práva pro `kubectl scale deployment/jervis-knowledgebase`
2. Python orchestrator: při timeout volat `kubectl scale --replicas=N`
3. Exponenciální scale-up: 1 → 2 → 3 → 5 (při opakovaných timeoutech)
4. Manual scale-down: admin musí ručně snížit, nebo timeout-based (pokud X minut žádný timeout, scale down o 1)

**Soubory:**
- `k8s/rbac.yaml` – rozšířit orchestrator ServiceAccount o scale permissions
- `backend/service-orchestrator/app/k8s/scaler.py` – KB scaling logic
- `backend/service-orchestrator/app/kb/client.py` – catch timeout, trigger scale-up

**Priorita:** Medium
**Complexity:** Simple
**Status:** Planned

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

## Další TODOs

_(Další features se budou přidávat sem podle potřeby)_
