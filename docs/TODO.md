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

**Status:** Partially implemented - UI ready, data connection needed

**Hotovo:**
- ✅ ChatMessage DTO extended with `workflowSteps: List<WorkflowStep>`
- ✅ UI component `WorkflowStepsDisplay` shows steps, tools, status
- ✅ Icons for step status (✓ completed, ✗ failed, ↻ in-progress, ⏰ pending)

**TODO:**
- Kotlin backend: při finalizaci chat response (OrchestratorStatusHandler) sesbírat workflow steps z orchestrator progress events
- MainViewModel: trackovat průběh nodes do temporary storage
- Když přijde FINAL message, attachnout workflow steps
- Map node names na české labely (intake → "Analýza úlohy", evidence_pack → "Shromažďování kontextu", respond → "Generování odpovědi")
- Extrahovat použité tools z každého node (např. `github_search`, `read_file`, `web_search`)

**Soubory:**
- `backend/server/.../service/agent/coordinator/OrchestratorStatusHandler.kt` - collect workflow steps
- `shared/ui-common/.../MainViewModel.kt` - track progress, attach to final message
- `backend/service-orchestrator/app/graph/nodes/` - node labeling

**Priorita:** Medium
**Complexity:** Simple

---

## Agent Memory & Knowledge

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

## Další TODOs

_(Další features se budou přidávat sem podle potřeby)_
