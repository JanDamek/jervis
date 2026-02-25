# CLAUDE.md – Jervis Project Instructions

> This file is automatically loaded into every Claude Code session working on this repo.
> It applies to all agents, all machines, all sessions.

## Project Overview

- **Kotlin Multiplatform** (KMP) AI Assistant with **Compose Multiplatform** UI
- Platforms: Desktop (JVM), Android, iOS
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
- Guidelines engine: `shared/common-dto/.../guidelines/GuidelinesDtos.kt`, `backend/server/.../service/guidelines/GuidelinesService.kt`, `backend/service-orchestrator/app/context/guidelines_resolver.py`
- Indexing settings: `shared/common-dto/.../indexing/IndexingSettingsDtos.kt`, `backend/server/.../rpc/PollingIntervalRpcImpl.kt`
- Whisper settings: `shared/common-dto/.../whisper/WhisperSettingsDtos.kt`, `backend/server/.../rpc/WhisperSettingsRpcImpl.kt`
- Whisper runner: `backend/service-whisper/whisper_runner.py`, `backend/service-whisper/entrypoint-whisper-job.sh`
- Whisper REST server: `backend/service-whisper/whisper_rest_server.py`, `backend/service-whisper/Dockerfile.rest`
- Whisper REST client: `backend/server/.../service/meeting/WhisperRestClient.kt`

## Build Notes

- No network in CI/sandbox – cannot run Gradle
- `DOCKER_BUILD=true` skips Android/iOS/Compose targets
- Verify code manually against DTO fields and repository API signatures
