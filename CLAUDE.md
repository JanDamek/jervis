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
| `docs/thought-map-spec.md` | **Thought Map spec** – navigation layer over KB graph, spreading activation, ThoughtNodes/Edges/Anchors, maintenance, cold start |

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

> Full index of all source files is in **KB** (search: "Key Source Files Index").
> Below are only the most critical paths needed for everyday decisions.

- Design system: `shared/ui-common/.../design/` (DesignTheme, DesignLayout, DesignButtons, DesignCards, DesignForms, DesignDialogs, DesignDataDisplay, DesignState)
- Shared helpers: `sections/ClientsSharedHelpers.kt` (getCapabilityLabel, GitCommitConfigFields – `internal`)
- Navigation: `shared/ui-common/.../navigation/AppNavigator.kt`
- DTOs: `shared/common-dto/`, Repository: `shared/domain/`
- Agent (unified): `backend/service-orchestrator/app/agent/` (models, graph, decomposer, langgraph_runner, tool_sets, persistence, vertex_executor, chat_router)
- KB service: `backend/service-knowledgebase/app/` (graph_service.py, knowledge_service.py, routes.py)
- Chat handler: `backend/service-orchestrator/app/chat/handler_agentic.py`
- Memory: `backend/service-orchestrator/app/memory/` (agent.py, affairs.py, lqm.py, composer.py)
- Tools: `backend/service-orchestrator/app/tools/definitions.py`, `executor.py`

## K8s Deployment

> Full deployment reference in **KB** (`kb_search: "k8s deployment"`) and in memory (`reference-k8s-deployment.md`).

- **Registry**: `registry.damek-soft.eu/jandamek`, images `:latest`, `imagePullPolicy: Always`
- **Namespace**: `jervis`, PVC `jervis-data-pvc` (10Gi RWX → `/opt/jervis/data`)
- **Deploy**: Always via `k8s/build_*.sh` scripts, NEVER Gradle/Docker/kubectl directly
- **Build flow**: Gradle (Kotlin only) → Docker build (linux/amd64) → push → kubectl apply → rollout restart
- **Key scripts**: `build_server.sh`, `build_orchestrator.sh`, `build_kb.sh`, `build_mcp.sh`, `build_all.sh`
- **Redeploy** (no rebuild): `redeploy_service.sh <name>`, `redeploy_all.sh`
- **Full deploy** (infra + all): `deploy_all.sh` (namespace → PVC → secrets → configmap → redeploy all → ingress)
- **ConfigMaps**: `configmap.yaml` (5 ConfigMaps: server, KB, orchestrator, ollama-router, MCP)
- **Ingress**: `jervis.damek-soft.eu` (server), `jervis-mcp.damek-soft.eu` (MCP SSE)

| Doc | What's inside |
|-----|---------------|
| `docs/architecture.md` | System architecture, service topology, K8s layout |
| KB: "k8s deployment" | Detailed deployment procedures, troubleshooting |

## Build Notes

- No network in CI/sandbox – cannot run Gradle
- `DOCKER_BUILD=true` skips Android/iOS/Compose targets
- Verify code manually against DTO fields and repository API signatures
