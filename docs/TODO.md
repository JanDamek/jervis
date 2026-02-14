# TODO – Plánované Features a Vylepšení

Tento dokument obsahuje seznam plánovaných features, vylepšení a refaktoringů,
které budou implementovány jako separate tickety.

## Autoscaling & Performance

### DB Performance Metrics & Monitoring

✅ **DONE** — Prometheus metrics for KB service: RAG ingest/query latency (Weaviate), graph ingest/traversal latency (ArangoDB), LLM extraction call tracking, queue depth gauges, worker task duration, concurrency gauges (active reads/writes). Exposed via `/metrics` endpoint with `prometheus_client`. K8s ServiceMonitor + Grafana dashboard included.

**Key files:** `app/metrics.py`, `app/main.py` (/metrics endpoint), `k8s/kb-servicemonitor.yaml`, `grafana/kb-dashboard.json`

---

## Environment Viewer UI (Phase 2)

### Standalone UI Screen for K8s Environment Inspection

✅ **DONE** — Full K8s resource viewer screen accessible from main menu ("Prostředí K8s"). Typed DTOs (`K8sResourceDtos.kt`) for pods, deployments, services, namespace status, conditions, events. kRPC interface (`IEnvironmentResourceService`) with listResources, getPodLogs, getDeploymentDetails, scaleDeployment, restartDeployment, getNamespaceStatus. Server-side RPC impl maps fabric8 data to typed DTOs. UI: environment selector chips, namespace health summary, collapsible Pod/Deployment/Service sections, pod log dialog, deployment detail dialog with conditions/events, restart action.

**Key files:**
- `shared/common-dto/.../environment/K8sResourceDtos.kt` — typed DTOs
- `shared/common-api/.../IEnvironmentResourceService.kt` — kRPC interface
- `backend/server/.../rpc/EnvironmentResourceRpcImpl.kt` — RPC implementation
- `shared/ui-common/.../screens/EnvironmentViewerScreen.kt` — full Compose screen
- `MainScreen.kt` — menu entry (ENVIRONMENT_VIEWER)

---

## Orchestrator & Agent Flow

### Non-blocking Coding Agent Invocation — Phase 2: Agent Pool & Queue

**Phase 1:** ✅ DONE — job_runner split (create + check + read), interrupt()-based execution in execute.py and git_ops.py, AgentJobWatcher background service, Kotlin agent_wait handling.

✅ **DONE (Phase 2)** — Agent pool with configurable per-type limits (`max_concurrent_aider/openhands/claude/junie` via env vars). Priority queue using `asyncio.Event` with foreground > background ordering. K8s Job timeout watchdog (stuck detection at `N × timeout`, auto-cleanup). Pod restart recovery via MongoDB persistence (`jervis_agent_watcher.watched_jobs` collection). Prometheus metrics: `jervis_agent_slots_active`, `jervis_agent_job_duration_seconds`, `jervis_agent_queue_depth`, `jervis_agent_jobs_total`, `jervis_agent_stuck_detected_total`, `jervis_agent_queue_wait_seconds`. ServiceMonitor + `/metrics` endpoint.

**Key files:**
- `app/agents/agent_pool.py` — `AgentPool` with limits, priority queue, metrics, stuck detection
- `app/agents/agent_job_watcher.py` — pool integration, recovery, stuck watchdog, MongoDB persistence
- `app/agents/job_runner.py` — uses pool acquire/release instead of K8s API counting
- `app/config.py` — `max_concurrent_*`, `pool_wait_timeout`, `stuck_job_timeout_multiplier`
- `k8s/orchestrator-servicemonitor.yaml` — Prometheus auto-scraping

---

### Předání příloh TaskDocument do Python orchestratoru s Tika+VLM zpracováním

✅ **DONE** — Kotlin loads attachments from disk (base64), sends via `OrchestratorAttachmentDto` in `OrchestrateRequestDto`. Python `AttachmentData` model receives them. Intake node calls `process_all_attachments()` which extracts text via Tika (or uses existing vision description from Qualifier Agent). Extracted text stored in `state["attachment_context"]` for all downstream nodes.

**Key files:**
- `PythonOrchestratorClient.kt` — `OrchestratorAttachmentDto`, extended `OrchestrateRequestDto`
- `AgentOrchestratorService.kt` — loads files from disk, base64-encodes, includes vision descriptions
- `app/tools/attachment_processor.py` — Tika-based text extraction + vision description reuse
- `app/models.py` — `AttachmentData` model, `CodingTask.attachments` field
- `app/graph/nodes/intake.py` — processes attachments, appends to LLM context

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
