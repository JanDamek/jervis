# Koog Framework Removal - Complete

> Date: 2026-02-07
> Status: **DONE** - All Koog dependencies removed from the project

## What Was Done

### Removed
- **Entire `com.jervis.koog` package** (26 files) - LLM executors, tools, SmartModelSelector, factory
- **Entire `com.jervis.orchestrator` package** (31 files) - 12 AI agents, models, tools, prompts
- **`KoogWorkflowService`** - Koog agent wrapper
- **`KoogProperties`** - Koog configuration class
- **3 Gradle dependencies**: `koog.agents`, `koog.agents.features.snapshot`, `koog.agents.features.memory`
- **A2A dependencies** from `shared/domain`: `koog.a2a.client`, `koog.a2a.transport.client.jsonrpc.http`
- **Version catalog entries**: all 10 koog library entries + version
- **`docs/koog.md`** - old Koog documentation

### Migrated
- `SimpleQualifierAgent`: moved from `com.jervis.koog.qualifier` to `com.jervis.qualifier` (was already koog-free)
- `KoogProperties` -> `QualifierProperties` (only qualifier-related fields retained)
- `application.yml` + `k8s/configmap.yaml`: `jervis.koog.*` -> `jervis.qualifier.*`
- All comments referencing Koog updated to reflect new architecture

### Rewired
- `AgentOrchestratorService`: ALL requests now route to Python orchestrator (LangGraph)
  - No more `shouldUsePythonOrchestrator()` keyword matching
  - No more Koog fallback for "non-coding" tasks
  - Python unavailable -> returns error message (no local fallback)

## Architecture After Removal

```
Kotlin Server          Python Orchestrator       KB Microservice
  - RPC/API     --->     (LangGraph)               (qualification)
  - Task queue           - Coding tasks             - RAG indexing
  - Qualifier            - Conversational           - Graph building
    (thin)               - Analysis                 - Summarization
                         - K8s Job runner
```

- **Kotlin server**: thin proxy - enqueues tasks, dispatches to Python, polls results
- **Python orchestrator**: ALL AI orchestration (LangGraph state machine + litellm)
- **KB microservice**: qualification/indexing (Kotlin server calls via HTTP)
