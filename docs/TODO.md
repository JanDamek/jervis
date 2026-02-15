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

✅ **DONE** — Typewriter-style token-by-token streaming for chat responses. Python orchestrator chunks final LLM answer text and emits `STREAMING_TOKEN` messages to Kotlin via `/internal/orchestrator-streaming-token` endpoint. Kotlin emits to `MutableSharedFlow<ChatResponseDto>` as `ChatResponseType.STREAMING_TOKEN`. UI `MainViewModel` accumulates tokens into a single `STREAMING` message (typewriter effect). When `OrchestratorStatusHandler` emits the authoritative `FINAL` message (with workflow steps), it seamlessly replaces the streaming preview. Error messages also clean up streaming state.

**Key files:**
- `shared/common-dto/.../ChatResponseDto.kt` — `STREAMING_TOKEN` enum value
- `shared/common-dto/.../ui/ChatMessage.kt` — `STREAMING` MessageType
- `backend/service-orchestrator/app/tools/kotlin_client.py` — `emit_streaming_token()` method
- `backend/service-orchestrator/app/graph/nodes/respond.py` — `_stream_answer_to_ui()` chunked delivery
- `backend/server/.../rpc/KtorRpcServer.kt` — `/internal/orchestrator-streaming-token` endpoint + `OrchestratorStreamingTokenCallback`
- `shared/ui-common/.../MainViewModel.kt` — `STREAMING` token accumulation, FINAL/ERROR cleanup

---

## Security & Git Operations

### GPG Certificate Management for Coding Agents

✅ **DONE** — Full-stack GPG certificate management for coding agent commit signing. Per-client GPG private keys stored in MongoDB (`gpg_certificates` collection). Settings UI with client selector, certificate list, upload form (key ID, name, email, ASCII-armored private key, optional passphrase), delete action. kRPC interface `IGpgCertificateService` with getCertificates, uploadCertificate, deleteCertificate. Internal endpoint `GET /internal/gpg-key/{clientId}` for orchestrator to fetch keys. `job_runner.py` injects `GPG_PRIVATE_KEY`, `GPG_KEY_ID`, `GPG_KEY_PASSPHRASE`, `GIT_COMMITTER_NAME`, `GIT_COMMITTER_EMAIL` as env vars into agent K8s Jobs when `allow_git=True`.

**Key files:**
- `shared/common-dto/.../coding/GpgCertificateDto.kt` — DTOs (GpgCertificateDto, GpgCertificateUploadDto, GpgCertificateDeleteDto)
- `shared/common-api/.../service/IGpgCertificateService.kt` — kRPC interface
- `backend/server/.../entity/GpgCertificateDocument.kt` — MongoDB entity
- `backend/server/.../repository/GpgCertificateRepository.kt` — Spring Data repository
- `backend/server/.../rpc/GpgCertificateRpcImpl.kt` — RPC implementation + `getActiveKey()` for orchestrator
- `backend/server/.../rpc/KtorRpcServer.kt` — `/internal/gpg-key/{clientId}` endpoint, RPC registration
- `backend/service-orchestrator/app/tools/kotlin_client.py` — `get_gpg_key()` method
- `backend/service-orchestrator/app/agents/job_runner.py` — GPG env var injection in `_build_job_manifest()`
- `shared/ui-common/.../screens/settings/sections/GpgCertificateSettings.kt` — Full settings UI
- `shared/ui-common/.../screens/settings/SettingsScreen.kt` — GPG_CERTIFICATES category
