# TODO – Plánované Features a Vylepšení

Tento dokument obsahuje seznam plánovaných features, vylepšení a refaktoringů,
které budou implementovány jako separate tickety.

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
   - `listGpgCertificates(userId: String): List<GpgCertificateDto>`
   - `deleteGpgCertificate(id: String)`
2. Implementovat validation:
   - Check GPG key format (ASCII armored)
   - Extract key ID from certificate
   - Verify key is private (has private part)
3. Encrypt private key before storage (using server key)

**Phase 3: UI Agent - Certificate Management UI**
1. Rozšířit `SettingsScreen` o nový tab "GPG Certificates"
2. Vytvořit `GpgCertificateList` composable
3. Vytvořit `UploadGpgCertificateDialog`:
   - TextField pro private key (multiline)
   - TextField pro passphrase (password)
   - Dropdown pro agent type
4. Implementovat ViewModel metody pro:
   - Načtení seznamu certifikátů
   - Upload certifikátu
   - Smazání certifikátu
5. Přidat RPC client volání do `MainViewModel` nebo nového `GpgCertificateViewModel`

**Phase 4: Orchestrator - Certificate Distribution**
1. V `AgentOrchestrator` při vytváření agenta:
   - Načíst certifikát pro daného agenta (query by agentType + userId)
   - Přidat certifikát do agent initialization parameters
2. V `AgentInitialization` DTO přidat pole:
   - `gpgCertificate: GpgCertificateDto?`
3. Při startu agenta předat certifikát přes RPC:
   - Git agent: `initialize(certificate: ...)`
   - Coding agent: `initialize(certificate: ...)`

**Phase 5: Git Agent - Certificate Usage**
1. V `GitAgent` přidat `GpgCertificate` field
2. Při initialization:
   - Import certifikátu do GPG keyring (použít `gpg --import`)
   - Pokud je passphrase, použít `--batch --yes --pinentry-mode loopback`
   - Uložit key ID pro pozdější použití
3. Konfigurace Git pro signing:
   - `git config --global user.signingkey <key-id>`
   - `git config --global commit.gpgsign true`
4. Při git operacích (commit, tag) automaticky používat `-S` flag

**Phase 6: Coding Agent - Certificate Usage**
1. V `CodingEngine` (service-coding-engine) přidat certificate handling
2. Při příkazu `git commit` v rámci agenta:
   - Přidat `-S` flag pro GPG signing
   - Ensure GPG agent je spuštěný s certifikátem
3. Alternativa: použít `GIT_SEAL_PATH` env variable pro detached signatures
4. Testování s reálným GPG setupem

**Phase 7: Security Hardening**
1. Encrypt certifikáty at rest (server DB) - použít AES-256
2. Encrypt certifikáty in transit (TLS) - už máme
3. Access control - jen owner může vidět/smazat své certifikáty
4. Audit logging - kdo uploadoval/smazal certifikát
5. Certificate rotation - možnost nahradit starý certifikát novým
6. Expiration check - GPG certifikáty mají expiration, kontrolovat

**Phase 8: Error Handling & Validation**
1. Invalid certificate format → reject with clear error
2. Missing passphrase → prompt user
3. Expired certificate → warn user
4. Git signing failure → fallback to unsigned commit (with warning)
5. Logging (no sensitive data) - log operations, not key content

**Soubory:**
- `shared/common-dto/.../GpgCertificateDto.kt` - NEW DTO
- `backend/server/src/main/kotlin/com/jervis/domain/security/GpgCertificateDocument.kt` - NEW entity
- `backend/server/src/main/kotlin/com/jervis/repository/GpgCertificateRepository.kt` - NEW repository
- `backend/server/src/main/kotlin/com/jervis/rpc/SecuritySettingsRpc.kt` - NEW RPC service
- `shared/ui-common/.../settings/GpgCertificateManagementScreen.kt` - NEW UI
- `shared/ui-common/.../settings/GpgCertificateViewModel.kt` - NEW ViewModel
- `backend/service-orchestrator/app/agent/AgentOrchestrator.kt` - certificate distribution
- `backend/service-gitlab/.../GitLabAgent.kt` - certificate usage
- `backend/service-github/.../GitHubAgent.kt` - certificate usage
- `backend/service-coding-engine/.../CodingEngine.kt` - certificate usage

**Priorita:** High (pro Git commit signing a bezpečnost)
**Complexity:** High (zahrnuje backend, UI, orchestrator, git agent, coding agent)
**Status:** Planned

**Poznámka:** Toto je komplexní feature která zahrnuje všechny komponenty systému. Certifikáty musí být bezpečně uloženy a distribuovány. Je důležité mít dobré error handling a validation, aby se neporušilo existující funkcionalita.

---

## Indexing & Qualification

### Exclusion Patterns - Vyřazení Položek z Orchestratoru

**Problém:**
- Některé položky (git commity, emaily, wiki stránky, bugtracker issues) nechceme zpracovávat orchestrátorem, jen je chceme indexovat do KB
- SimpleQualifierAgent už umí vyřadit neakční obsah (`hasActionableContent=false` → DISPATCHED_GPU), ale to je automatické na základě KB analýzy
- Uživatel chce mít manuální kontrolu - explicitně vyřadit konkrétní položky nebo typy položek z orchestratoru
- Potřebujeme pattern-based filtering: "všechny commity od uživatele X", "emaily s předmětem obsahujícím '[IGNORE]'", "wiki stránky v prostoru Y"

**Architektura:**

**1. Exclusion Pattern Storage (Server DB)**
- Nová MongoDB kolekce `exclusion_patterns` (ne v KB!)
- Pattern DTO:
  - `id: String`
  - `userId: String` (kdo vytvořil pattern)
  - `name: String` (popisný název, např. "Ignorovat maintenance commity")
  - `pattern: String` (regex nebo wildcard)
  - `matchField: ExclusionMatchField` (enum: TITLE, SOURCE_URN, CONNECTION, CLIENT, TYPE, SENDER)
  - `taskType: TaskTypeEnum?` (volitelné filtrování podle typu tasku)
  - `isActive: Boolean` (pattern lze deaktivovat)
  - `createdAt: Instant`
- Patterny se vztahují na **tasky** (ne na source položky). Když task odpovídá patternu, je hned označen jako DISPATCHED_GPU (přeskočí KB ingest a orchestrator).

**2. UI - Indexing Queue Integration**
- V `IndexingQueueSections.kt` přidat tlačítko "Vyřadit z orchestratoru" (ikona "Block" nebo "No Entry"):
  - V `QueueItemRow` (pro pending items - git, email, wiki, bugtracker)
  - V `PipelineItemRow` (pro pipeline tasks - READY_FOR_QUALIFICATION, QUALIFYING, atd.)
- Když uživatel klikne, otevře se `ExclusionPatternDialog`:
  - **TextField** pro pattern (regex, s help textem: "Podporuje regulární výrazy, např. `.*maintenance.*`")
  - **Dropdown** pro matchField: TITLE (název), SOURCE_URN (URN zdroje), CONNECTION (připojení), CLIENT (klient), TYPE (typ), SENDER (odesílatel)
  - **TextField** pro name (popis patternu)
  - **Checkbox** pro taskType (volitelné: GIT_PROCESSING, EMAIL_PROCESSING, WIKI_PROCESSING, BUGTRACKER_PROCESSING)
  - Tlačítka "Vytvořit pattern" a "Zrušit"
- Pattern se vytvoří pro aktuální položku (předvyplněné hodnoty podle položky) - uživatel může upravit

**3. Backend - RPC Endpoints**
- Nový `ExclusionPatternRpc` service (nebo rozšířit `IndexingQueueRpc`):
  - `createPattern(pattern: ExclusionPatternDto): String` (vrací pattern ID)
  - `updatePattern(id: String, pattern: ExclusionPatternDto): Boolean`
  - `deletePattern(id: String): Boolean`
  - `listPatterns(userId: String): List<ExclusionPatternDto>`
  - `getPattern(id: String): ExclusionPatternDto?`
- Implementace v `backend/server/src/main/kotlin/com/jervis/rpc/ExclusionPatternRpc.kt`
- Repository: `ExclusionPatternRepository` (MongoDB)

**4. Task Qualification - Pre-Filter**
- V `TaskQualificationService.processOne()` přidat kontrolu exclusion patterns PŘED voláním `simpleQualifierAgent.run()`:
  ```kotlin
  // Check exclusion patterns first
  val exclusionPatternService = ... // inject
  if (exclusionPatternService.matchesAnyPattern(task)) {
      // Task matches exclusion pattern → mark as DISPATCHED_GPU (indexed only, no orchestrator)
      taskService.updateState(task, TaskStateEnum.DISPATCHED_GPU)
      return "EXCLUDED: matches exclusion pattern"
  }
  ```
- `ExclusionPatternService.matchesAnyPattern(task)`:
  - Načíst všechny aktivní exclusion patterns (cached, refresh periodically)
  - Pro každý pattern zkontrolovat, zda task odpovídá:
    - Pokud `taskType` je nastaven, porovnat s `task.type`
    - Porovnat `pattern` s `matchField`:
      - TITLE: `extractTaskTitle(task)` (z TaskDocument)
      - SOURCE_URN: `task.sourceUrn.value`
      - CONNECTION: extrahovat connection ID ze sourceUrn (např. "conn:abc123")
      - CLIENT: `task.clientId.value`
      - TYPE: `task.type.name`
      - SENDER: extrahovat z content (např. email "From:", git "Author:")
    - Regex match (case-insensitive)
  - Pokud jakýkoliv pattern matches, vrátit true
- **Cache**: patterns cache s TTL 5 minut (aby se nemusely načítat pro každý task)

**5. Re-Qualification (Re-run Qualification)**
- Když uživatel vytvoří/upraví/odstraní exclusion pattern, potřebuje se tasky znovu kvalifikovat:
  - Nový RPC: `POST /indexing-queue/requalify` (nebo `POST /tasks/requalify`)
  - Tato operace:
    1. Najde všechny tasky ve stavu READY_FOR_QUALIFICATION (nebo i DISPATCHED_GPU? jen ty, které ještě nebyly kvalifikovány)
    2. Projde je přes exclusion pattern check znovu
    3. Pokud nyní odpovídají patternu, označí jako DISPATCHED_GPU
    4. Pokud neodpovídají a byly DISPATCHED_GPU, může je vrátit na READY_FOR_QUALIFICATION? (možná jen pro tasky které ještě nebyly kvalifikovány)
  - UI: tlačítko "Překvalifikovat" v Indexing Queue (pro celou frontu nebo pro konkrétní položku)
- **Pozor**: tasky ve stavu QUALIFYING, READY_FOR_GPU, DISPATCHED_GPU (již kvalifikované) by neměly být znovu kvalifikovány automaticky, jen pokud explicitně uživatel vyžaduje re-qualification pro konkrétní task.

**6. Migration & Data Model**
- Vytvořit `ExclusionPatternDocument`:
  ```kotlin
  @Document(collection = "exclusion_patterns")
  data class ExclusionPatternDocument(
      val userId: String,
      val name: String,
      val pattern: String, // regex
      val matchField: String, // enum name
      val taskType: String? = null,
      val isActive: Boolean = true,
      val createdAt: Instant,
  )
  ```
- Repository: `ExclusionPatternRepository extends ReactiveMongoRepository<ExclusionPatternDocument, ObjectId>`
- Načítání: `findByUserIdAndIsActiveTrue(userId)` + cache

**7. UI - Pattern Management (Settings)**
- Možnost spravovat patterny i v Settings (nový tab "Exclusion Patterns"):
  - Seznam patternů s možností edit/delete
  - Možnost aktivovat/deaktivovat
  - Náhled kolik tasků odpovídá patternu (volitelné)

**Implementační fáze:**

**Phase 1: Backend DTO & Repository**
1. Vytvořit `ExclusionPatternDto` a `ExclusionPatternDocument`
2. Vytvořit `ExclusionPatternRepository`
3. Přidat DB index: `{ userId: 1, isActive: 1 }`

**Phase 2: RPC Endpoints**
4. Vytvořit `ExclusionPatternRpc` s CRUD metodami
5. Přidat registraci v `KtorRpcServer`

**Phase 3: Task Qualification Integration**
6. Vytvořit `ExclusionPatternService` s `matchesAnyPattern(task)` metodou
7. Přidat cache (Caffeine nebo simple in-memory s periodic refresh)
8. Upravit `TaskQualificationService.processOne()` - přidat exclusion check před SimpleQualifierAgent
9. Test: task odpovídající patternu by měl skončit v DISPATCHED_GPU bez KB volání

**Phase 4: UI - Exclusion Pattern Dialog**
10. Vytvořit `ExclusionPatternDialog.kt` (JFormDialog s pattern, matchField, name, taskType)
11. Přidat do `IndexingQueueSections.kt` tlačítko "Exclude" v `QueueItemRow` a `PipelineItemRow`
12. Přidat ViewModel logiku: `showExclusionDialog(item)`, `createPattern(...)`
13. Předvyplnit dialog hodnotami z položky (title, sourceUrn, connection, client, type)

**Phase 5: Re-Qualification**
14. Přidat RPC `requalifyTask(taskId: String)` nebo `requalifyAll()`
15. UI: tlačítko "Překvalifikovat" (pro konkrétní task nebo celou frontu)
16. Logika: tasky ve stavu READY_FOR_QUALIFICATION projdou exclusion check znovu

**Phase 6: Settings Management (optional)**
17. Nový Settings tab "Exclusion Patterns" - seznam, edit, delete, activate/deactivate
18. Statistiky: kolik tasků bylo vyřazeno patternem

**Phase 7: Testing & Refinement**
19. Unit testy pro `ExclusionPatternService.matchesAnyPattern()`
20. Integrační testy: task odpovídající patternu → DISPATCHED_GPU
21. UI testy: dialog, tlačítka, re-qualification

**Soubory:**
- `shared/common-dto/.../ExclusionPatternDto.kt` - NEW DTO
- `backend/server/src/main/kotlin/com/jervis/domain/filter/ExclusionPatternDocument.kt` - NEW entity
- `backend/server/src/main/kotlin/com/jervis/repository/ExclusionPatternRepository.kt` - NEW repository
- `backend/server/src/main/kotlin/com/jervis/rpc/ExclusionPatternRpc.kt` - NEW RPC
- `backend/server/src/main/kotlin/com/jervis/service/qualification/ExclusionPatternService.kt` - NEW service
- `backend/server/src/main/kotlin/com/jervis/service/background/TaskQualificationService.kt` - modify (add pre-check)
- `shared/ui-common/.../screens/IndexingQueueSections.kt` - modify (add exclude button)
- `shared/ui-common/.../dialogs/ExclusionPatternDialog.kt` - NEW dialog
- `shared/ui-common/.../viewmodel/IndexingQueueViewModel.kt` - modify (add RPC calls)
- `backend/server/src/main/kotlin/com/jervis/rpc/IndexingQueueRpcImpl.kt` - add requalification endpoint

**Priorita:** Medium (užitečné pro user control, ale není kritické)
**Complexity:** Medium (zahrnuje backend, UI, qualification flow)
**Status:** Planned

**Poznámka:** Toto je user-facing feature pro manuální kontrolu, co se má zpracovávat. Je doplněk k automatickému SimpleQualifierAgent. Patterny se vztahují na tasky, ne na source položky - když task odpovídá patternu, je vyřazen z orchestratoru (DISPATCHED_GPU). Uživatel může vytvořit pattern přímo z položky v Indexing Queue.

---

## Další TODOs

_(Další features se budou přidávat sem podle potřeby)_
