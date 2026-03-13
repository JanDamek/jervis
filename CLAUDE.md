# CLAUDE.md – Jervis Project Instructions

> This file is automatically loaded into every Claude Code session working on this repo.
> It applies to all agents, all machines, all sessions.

## Project Overview

- **Kotlin Multiplatform** (KMP) AI Assistant with **Compose Multiplatform** UI
- Platforms: Desktop (JVM), Android, iOS, watchOS (SwiftUI), Wear OS (Compose)
- Backend: Spring Boot with ArangoDB + MongoDB
- **UI language: Czech**, code/comments/logs: **English**
- Compose 1.9.3, Kotlin 2.3.0, Material 3

## Documentation – Read Before Changing UI

| Document | What's inside |
|----------|---------------|
| `docs/ui-design.md` | **SSOT** – adaptive layout architecture, all component signatures, ASCII diagrams, dialog patterns, typography, spacing, migration checklist, forbidden patterns |
| `docs/guidelines.md` § "UI Design System" | Quick-reference with **5 inline code patterns** (category nav, list→detail, edit form, flat list, expandable card), decision tree, source file locations |
| `docs/structures.md` | Data processing pipeline, CPU/GPU routing, BackgroundEngine, qualifier tools |
| `docs/architecture.md` | System architecture and module boundaries |
| `docs/implementation.md` | Implementation details and conventions |
| `docs/knowledge-base.md` | Knowledge Base SSOT – graph schema, RAG, ingest, normalization, indexers |
| `docs/orchestrator-final-spec.md` | Python Orchestrator spec – async dispatch, approval flow, concurrency |
| `docs/orchestrator-detailed.md` | **Orchestrator detailed reference** – complete technical description of all nodes, LLM, K8s Jobs, communication, state, approval flow |

## Workflow Rules

### Documentation is part of the deliverable

**After every code change, update relevant docs before committing:**

- New UI component or pattern → update `docs/ui-design.md` (SSOT) + `docs/guidelines.md` (inline examples)
- Data processing / routing change → update `docs/structures.md`
- KB / graph schema change → update `docs/knowledge-base.md`
- Architecture change → update `docs/architecture.md`
- Never commit code changes without updating affected docs

### Pull Request Checklist

1. Code changes done
2. Relevant docs updated
3. No duplicated helpers (check shared helpers in `ClientsSharedHelpers.kt`)
4. All interactive elements ≥ 44dp touch target
5. Cards use `CardDefaults.outlinedCardBorder()`
6. Loading/empty/error states use `JCenteredLoading` / `JEmptyState` / `JErrorState`

## UI Design Quick Rules

```
COMPACT_BREAKPOINT_DP = 600  (BoxWithConstraints, never platform expect/actual)

Compact (<600dp, phone):  full-screen list→detail, JTopBar with back arrow
Expanded (≥600dp, tablet/desktop):  240dp sidebar + content side-by-side
```

**Decision tree:**
- Category navigation → `JAdaptiveSidebarLayout`
- Entity CRUD list → `JListDetailLayout` + `JDetailScreen`
- Flat list with actions → `LazyColumn` + `JActionBar` + state components
- Edit form → `JDetailScreen` (provides back + save/cancel)

**Forbidden:** `Card(elevation/surfaceVariant)`, `TopAppBar` directly, `IconButton` without 44dp size, duplicating `getCapabilityLabel()`, platform expect/actual for layout decisions.

## Key Source Files

- Design system: `shared/ui-common/.../design/` (DesignTheme, DesignLayout, DesignButtons, DesignCards, DesignForms, DesignDialogs, DesignDataDisplay, DesignState)
- Settings: `shared/ui-common/.../screens/settings/SettingsScreen.kt` + `sections/`
- Shared helpers: `sections/ClientsSharedHelpers.kt` (getCapabilityLabel, GitCommitConfigFields – `internal`)
- Extension: `ConnectionResponseDto.displayUrl` is in ConnectionsSettings.kt (not a DTO field)
- Navigation: `shared/ui-common/.../navigation/AppNavigator.kt`
- DTOs: `shared/common-dto/`, Repository: `shared/domain/`
- OpenRouter settings: `shared/common-dto/.../openrouter/OpenRouterSettingsDtos.kt`, `shared/common-api/.../IOpenRouterSettingsService.kt`, `backend/server/.../rpc/OpenRouterSettingsRpcImpl.kt`
- OpenRouter UI: `shared/ui-common/.../screens/settings/sections/OpenRouterSettings.kt`
- OpenRouter entity: `backend/server/.../entity/OpenRouterSettingsDocument.kt`
- Cloud model policy: `backend/server/.../entity/CloudModelPolicy.kt` (includes `OpenRouterTier` enum, `maxOpenRouterTier`)
- Guidelines engine: `shared/common-dto/.../guidelines/GuidelinesDtos.kt`, `backend/server/.../service/guidelines/GuidelinesService.kt`, `backend/service-orchestrator/app/context/guidelines_resolver.py`
- Indexing settings: `shared/common-dto/.../indexing/IndexingSettingsDtos.kt`, `backend/server/.../rpc/PollingIntervalRpcImpl.kt`
- Whisper settings: `shared/common-dto/.../whisper/WhisperSettingsDtos.kt`, `backend/server/.../rpc/WhisperSettingsRpcImpl.kt`
- Whisper runner: `backend/service-whisper/whisper_runner.py`, `backend/service-whisper/entrypoint-whisper-job.sh`
- Whisper REST server: `backend/service-whisper/whisper_rest_server.py`, `backend/service-whisper/Dockerfile.rest`
- Speaker entity: `backend/server/.../entity/SpeakerDocument.kt`, `backend/server/.../repository/SpeakerRepository.kt`
- Speaker DTOs: `shared/common-dto/.../meeting/SpeakerDtos.kt` (`AutoSpeakerMatchDto`, `SpeakerEmbeddingDto`, `hasVoiceprint`)
- Speaker RPC: `shared/common-api/.../ISpeakerService.kt`, `backend/server/.../rpc/SpeakerRpcImpl.kt` (incl. `setVoiceEmbedding`)
- Speaker UI: `shared/ui-common/.../meeting/SpeakerAssignmentPanel.kt` → `SpeakerAssignmentDialog` (AlertDialog, auto-match confidence badge with matchedEmbeddingLabel, multi-embedding save on confirm)
- Speaker Settings: `shared/ui-common/.../screens/settings/sections/SpeakerSettings.kt` (CRUD, JListDetailLayout, voiceprint labels)
- Segment speaker detail: `SegmentCorrectionDialog.kt` (speaker info + confidence + dropdown), `TranscriptPanel.kt` (confidence badge + embedding label)
- Speaker auto-ID: `MeetingTranscriptionService.kt` (cosine similarity matching across all embeddings), `MeetingDocument.speakerEmbeddings`, `SpeakerDocument.voiceEmbeddings` (multi-embedding with VoiceEmbeddingEntry)
- Whisper REST client: `backend/server/.../service/meeting/WhisperRestClient.kt`
- TTS service: `backend/service-tts/` (Piper TTS FastAPI, CPU-only), deploy: `k8s/build_tts.sh`
- TTS client: `shared/ui-common/.../audio/TtsClient.kt` (POST /tts, /tts/stream)
- watchOS app: `apps/watchApp/` (SwiftUI, WatchConnectivity → iPhone WatchSessionManager → RecordingUploadService)
- Wear OS app: `apps/wearApp/` (Compose for Wear OS, DataLayer API → phone → RecordingUploadService)
- Siri intents (iOS): `apps/iosApp/iosApp/JervisIntents.swift` (AskJervisIntent, StartRecordingIntent, JervisShortcutsProvider)
- Siri intents (watchOS): `apps/watchApp/JervisWatch/JervisIntents.swift` (AskJervisIntent, StartWatchChatIntent, StartWatchRecordingIntent)
- Siri API client: `apps/iosApp/iosApp/JervisApiClient.swift`, `apps/watchApp/JervisWatch/WatchJervisApiClient.swift`
- Google Assistant (Android): `apps/mobile/src/androidMain/res/xml/actions.xml`, `apps/mobile/src/androidMain/kotlin/.../VoiceQueryActivity.kt`
- Google Assistant (Wear OS): `apps/wearApp/src/main/res/xml/actions.xml`, `apps/wearApp/src/main/kotlin/.../VoiceQueryActivity.kt`
- Siri/Assistant backend: `backend/server/src/main/kotlin/com/jervis/rpc/SiriChatRouting.kt` (POST /api/v1/chat/siri)
- Environment Manager: `shared/ui-common/.../screens/environment/` (EnvironmentManagerScreen, OverviewTab, ComponentsTab, ComponentEditPanel, PropertyMappingsTab, K8sResourcesTab, LogsEventsTab)
- Environment sidebar: `shared/ui-common/.../environment/` (EnvironmentPanel, EnvironmentViewModel, EnvironmentTreeComponents)
- Environment mapper: `backend/server/.../mapper/EnvironmentMapper.kt` (toDto, toDocument, toAgentContext, toAgentContextJson)
- Environment services: `backend/server/.../service/environment/` (EnvironmentService, EnvironmentK8sService — incl. ensureAgentRbac, discoverFromNamespace, syncFromK8s, ComponentDefaults)
- Environment internal API: `backend/server/.../rpc/internal/InternalEnvironmentRouting.kt` (CRUD + discover + replicate + sync-from-k8s REST for MCP/orchestrator)
- Cache invalidation API: `backend/server/.../rpc/internal/InternalCacheRouting.kt` (POST /internal/cache/invalidate, calls GuidelinesService.clearCache())
- Environment MCP tools: `backend/service-mcp/app/main.py` (environment_list, _get, _create, _deploy, _discover_namespace, _replicate, _sync_from_k8s, etc.)
- Environment orchestrator tools: `backend/service-orchestrator/app/tools/definitions.py` (ENVIRONMENT_TOOLS, DEVOPS_AGENT_TOOLS)
- KB document DTOs: `shared/common-dto/.../kb/KbDocumentDtos.kt`
- KB document service: `shared/common-api/.../IKbDocumentService.kt`, `backend/server/.../rpc/KbDocumentRpcImpl.kt`
- KB document storage: `backend/server/.../storage/DirectoryStructureService.kt` (storeKbDocument, readKbDocument, deleteKbDocument)
- KB document Python endpoints: `backend/service-knowledgebase/app/api/routes.py` (/documents/*, /documents/extract-text)
- KB document MCP tools: `backend/service-mcp/app/main.py` (kb_document_upload with base64 support, kb_document_list, kb_document_delete)
- Email thread consolidation: `backend/server/.../service/email/EmailThreadService.kt` (thread analysis, auto-resolve), `backend/server/.../service/email/EmailContinuousIndexer.kt` (thread-aware indexing)
- Email threading: `backend/server/.../entity/email/EmailMessageIndexDocument.kt` (threadId, direction, computeThreadId), `EmailDirection` enum
- IMAP SENT folder: `backend/server/.../polling/handler/email/ImapPollingHandler.kt` (auto-includes SENT, per-folder polling state)
- Topic consolidation: `TaskDocument.topicId` (general grouping: email-thread, mr, slack), `TaskDocument.lastActivityAt`
- Attachment extraction: `backend/server/.../entity/AttachmentExtractDocument.kt`, `backend/server/.../repository/AttachmentExtractRepository.kt`
- Attachment extraction service: `backend/server/.../service/indexing/AttachmentExtractionService.kt` (VLM-first text extraction)
- Attachment KB indexing: `backend/server/.../service/indexing/AttachmentKbIndexingService.kt` (register pre-stored attachments)
- VLM image service: `backend/service-knowledgebase/app/services/image_service.py` (qwen3-vl-tool, ChatOllama)
- Text extraction endpoint: `backend/service-knowledgebase/app/services/knowledge_service.py` (extract_text_only — DocumentExtractor/VLM without RAG)
- Agent (unified): `backend/service-orchestrator/app/agent/` (models.py, graph.py, decomposer.py, gemini_decomposer.py, validation.py, langgraph_runner.py, tool_sets.py, persistence.py, progress.py, artifact_graph.py, impact.py, vertex_executor.py, chat_router.py, sse_handler.py)
- MongoDB tools + cache invalidation: `backend/service-orchestrator/app/tools/definitions.py` (MONGO_TOOLS), `backend/service-orchestrator/app/tools/executor.py` (handlers + auto cache invalidation), `backend/service-orchestrator/app/tools/kotlin_client.py` (invalidate_cache)
- Graph UI visualization: `shared/ui-common/.../chat/TaskGraphComponents.kt` (TaskGraphSection, VertexCard, EdgeRow)
- Graph DTOs: `shared/common-dto/.../graph/TaskGraphDtos.kt`, `shared/common-api/.../ITaskGraphService.kt`, `backend/server/.../rpc/TaskGraphRpcImpl.kt`
- Agent background dispatch: `backend/service-orchestrator/app/background/handler.py` (_run_graph_agent_background)
- Agent task watcher: `backend/service-orchestrator/app/agent_task_watcher.py` (monitors CODING tasks, creates MR/PR, triggers code review, two-step completion for direct coding tasks)
- Code review handler: `backend/service-orchestrator/app/review/code_review_handler.py` (orchestration: KB prefetch → static analysis → dispatch review agent → post MR comment → fix task)
- Review engine: `backend/service-orchestrator/app/review/review_engine.py` (static analysis: forbidden patterns, credentials, file restrictions)
- MR/PR internal API: `backend/server/.../rpc/internal/InternalMergeRequestRouting.kt` (create-merge-request, post-mr-comment — GitHub PR + GitLab MR)
- Issue tracker internal API: `backend/server/.../rpc/internal/InternalBugTrackerRouting.kt` (list, create, update, comment — GitHub Issues + GitLab Issues)
- Issue tools (orchestrator): `backend/service-orchestrator/app/tools/definitions.py` (TOOL_CREATE_ISSUE, TOOL_UPDATE_ISSUE, TOOL_ADD_ISSUE_COMMENT, TOOL_LIST_ISSUES, ISSUE_TOOLS, TRACKER_AGENT_TOOLS)
- Issue tools (MCP): `backend/service-mcp/app/main.py` (create_issue, update_issue, add_issue_comment, list_issues)
- MR/PR continuous indexer: `backend/server/.../service/indexing/git/MergeRequestContinuousIndexer.kt` (polls GitLab/GitHub for open MRs, creates review tasks)
- MR/PR state: `backend/server/.../service/indexing/git/state/MergeRequestDocument.kt`, `MergeRequestRepository.kt`
- Gemini decomposer: `backend/service-orchestrator/app/agent/gemini_decomposer.py` (large context >100k tokens → Gemini 1M → sub-vertices + synthesis)
- Claude SDK runner: `backend/service-claude/claude_sdk_runner.py` (K8s Job entrypoint, result.json with branch field, kubectl binary available)
- Coding agent RBAC: `k8s/orchestrator-rbac.yaml` (ServiceAccount jervis-coding-agent + ClusterRole jervis-environment-manager)
- Coding agent job: `backend/service-orchestrator/app/agents/job_runner.py` (dispatch_coding_agent — SA, KUBE_NAMESPACES env var)
- Qualifier handler: `backend/service-orchestrator/app/unified/qualification_handler.py` (_record_incoming_vertex — creates INCOMING vertex in memory map after QUEUED qualification)
- Task queue API: `backend/server/.../rpc/internal/InternalTaskApiRouting.kt` (GET /internal/tasks/queue, POST /internal/tasks/{id}/priority)
- Queue tools: `backend/service-orchestrator/app/tools/definitions.py` (TOOL_TASK_QUEUE_INSPECT, TOOL_TASK_QUEUE_SET_PRIORITY, QUEUE_TOOLS)
- Project management internal API: `backend/server/.../rpc/internal/InternalProjectManagementRouting.kt` (create client/project/connection REST)
- Git internal API: `backend/server/.../rpc/internal/InternalGitRouting.kt` (create repo, init workspace)
- Git repo creation: `backend/server/.../service/git/GitRepositoryCreationService.kt` (GitHub/GitLab API)
- Project templates: `backend/server/.../service/project/ProjectTemplateService.kt` (KMP/Spring Boot/full-stack scaffolding)
- Project management MCP tools: `backend/service-mcp/app/main.py` (create_client, create_project, create_connection, create_git_repository, init_workspace)
- MCP OAuth provider: `backend/service-mcp/app/oauth_provider.py` (OAuth 2.1 server: DCR, Google IdP, token issuance for Claude.ai/iOS)
- MCP config: `backend/service-mcp/app/config.py` (Settings — tokens, OAuth, MongoDB, service URLs)
- Content reducer: `backend/service-orchestrator/app/memory/content_reducer.py` (reduce_for_prompt, reduce_messages_for_prompt, trim_for_display)
- Memory Agent: `backend/service-orchestrator/app/memory/agent.py` (MemoryAgent, LQM singleton)
- Memory affairs: `backend/service-orchestrator/app/memory/affairs.py` (create, park, resume, resolve)
- Memory composer: `backend/service-orchestrator/app/memory/composer.py` (compose_affair_context — async, token-budgeted)
- Context switch: `backend/service-orchestrator/app/memory/context_switch.py` (detect_context_switch, LLM classification)
- Memory consolidation: `backend/service-orchestrator/app/memory/consolidation.py` (topic-aware summary consolidation)
- LQM: `backend/service-orchestrator/app/memory/lqm.py` (LocalQuickMemory — hot cache, write buffer, warm cache)
- O365 Gateway: `backend/service-o365-gateway/` (Kotlin/Ktor — relay auth to Graph API via browser pool tokens)
- O365 Gateway entry: `backend/service-o365-gateway/src/main/kotlin/com/jervis/o365gateway/O365GatewayApplication.kt`
- O365 Gateway Graph API: `backend/service-o365-gateway/src/main/kotlin/com/jervis/o365gateway/service/GraphApiService.kt` (Teams chats, channels, messages)
- O365 Gateway token: `backend/service-o365-gateway/src/main/kotlin/com/jervis/o365gateway/service/TokenService.kt` (cache + browser pool fetch)
- O365 Browser Pool: `backend/service-o365-browser-pool/` (Python/FastAPI/Playwright — manages browser sessions per client)
- O365 Browser Pool entry: `backend/service-o365-browser-pool/app/main.py`
- O365 Browser Manager: `backend/service-o365-browser-pool/app/browser_manager.py` (Playwright contexts, persistent profiles)
- O365 Token Extractor: `backend/service-o365-browser-pool/app/token_extractor.py` (network interception, Bearer token capture)
- O365 MCP tools: `backend/service-mcp/app/main.py` (o365_teams_list_chats, o365_teams_read_chat, o365_teams_send_message, o365_teams_list_teams, o365_teams_list_channels, o365_teams_read_channel, o365_teams_send_channel_message, o365_session_status, o365_mail_list, o365_mail_read, o365_mail_send, o365_calendar_events, o365_calendar_create, o365_files_list, o365_files_download, o365_files_search)
- O365 orchestrator tools: `backend/service-orchestrator/app/tools/definitions.py` (O365_TEAMS_TOOLS, O365_MAIL_TOOLS, O365_CALENDAR_TOOLS, O365_FILES_TOOLS, O365_ALL_TOOLS)
- O365 orchestrator executor: `backend/service-orchestrator/app/tools/executor.py` (O365 tool handlers via O365 Gateway REST API)
- Chat polling (Teams): `backend/server/.../service/polling/handler/teams/O365PollingHandler.kt` (O365 Gateway → MongoDB)
- Chat polling (Slack): `backend/server/.../service/polling/handler/slack/SlackPollingHandler.kt` (Slack Web API → MongoDB)
- Chat polling (Discord): `backend/server/.../service/polling/handler/discord/DiscordPollingHandler.kt` (Discord REST API → MongoDB)
- Chat indexing (Teams): `backend/server/.../service/teams/TeamsContinuousIndexer.kt` (NEW → CHAT_PROCESSING task → INDEXED)
- Chat indexing (Slack): `backend/server/.../service/slack/SlackContinuousIndexer.kt` (NEW → SLACK_PROCESSING task → INDEXED)
- Chat indexing (Discord): `backend/server/.../service/discord/DiscordContinuousIndexer.kt` (NEW → DISCORD_PROCESSING task → INDEXED)
- Chat entities: `backend/server/.../entity/teams/`, `entity/slack/`, `entity/discord/` (message index documents)
- Chat reply service: `backend/server/.../integration/chat/ChatReplyService.kt` (outbound messages, EPIC 11-S5)

## Build Notes

- No network in CI/sandbox – cannot run Gradle
- `DOCKER_BUILD=true` skips Android/iOS/Compose targets
- Verify code manually against DTO fields and repository API signatures
