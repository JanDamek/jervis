# Koog Framework Audit - Server Module

> Date: 2026-02-07
> Scope: `backend/server` module

## Summary

Koog framework (`ai.koog.*`) is still deeply integrated in the server module as the **agent runtime** for non-coding orchestration. Qualification and cost tracking are already koog-free (just sitting in wrong package). The Python orchestrator handles coding tasks externally.

## What is NO LONGER Koog

| Component | Files | Status |
|---|---|---|
| Qualifier | `koog/qualifier/SimpleQualifierAgent.kt` | Calls KB microservice directly, no `ai.koog` imports |
| Cost tracking | `koog/cost/CostTrackingService.kt`, `koog/cost/LlmPriceService.kt` | Pure Spring, no `ai.koog` imports |
| Python orchestrator | `AgentOrchestratorService.kt` routes coding tasks to Python | Fully external |

**Action:** Move these out of `com.jervis.koog` package.

## What IS STILL Koog

### 1. LLM Executor Layer (7 files)

All implement `ai.koog.prompt.executor.model.PromptExecutor`:

- `koog/executor/OllamaPromptExecutor.kt` — uses `OllamaClient`, `SingleLLMPromptExecutor`
- `koog/executor/OllamaQualifierPromptExecutor.kt` — same
- `koog/executor/AnthropicPromptExecutor.kt` — uses `AnthropicLLMClient`
- `koog/executor/OpenAiPromptExecutor.kt` — uses `OpenAILLMClient`
- `koog/executor/GooglePromptExecutor.kt` — uses `GoogleLLMClient`
- `koog/executor/KoogTimeouts.kt` — `ConnectionTimeoutConfig` helper
- `koog/KoogPromptExecutorFactory.kt` — factory returning `PromptExecutor` by provider name

**Koog APIs used:** `PromptExecutor`, `SingleLLMPromptExecutor`, provider-specific clients, `ConnectionTimeoutConfig`

### 2. Smart Model Selector (1 file)

- `koog/SmartModelSelector.kt` — returns `ai.koog.prompt.llm.LLModel` with `LLMProvider`, `LLMCapability`

**Koog APIs used:** `LLModel`, `LLMProvider`, `LLMCapability` (data types only)

### 3. Tool Definitions (15 files)

All implement `ai.koog.agents.core.tools.reflect.ToolSet` with `@Tool` + `@LLMDescription`:

- `koog/tools/KnowledgeStorageTools.kt` — RAG search/store, graph traversal
- `koog/tools/CommunicationTools.kt` — Mock email/Slack/Teams
- `koog/tools/CodingRules.kt` — Coding rules data
- `koog/tools/analysis/JoernTools.kt` — Static code analysis
- `koog/tools/analysis/LogSearchTools.kt` — Log search
- `koog/tools/coding/CodingTools.kt` — A2A coding delegation
- `koog/tools/external/BugTrackerReadTools.kt` — Jira read
- `koog/tools/external/EmailReadTools.kt` — Email read
- `koog/tools/external/IssueTrackerTool.kt` — Issue tracking
- `koog/tools/external/WikiReadTools.kt` — Confluence read
- `koog/tools/learning/LearningTools.kt` — Learning/memory
- `koog/tools/preferences/PreferenceTools.kt` — User preferences
- `koog/tools/qualifier/QualifierRoutingTools.kt` — Task routing
- `koog/tools/scheduler/SchedulerTools.kt` — Task scheduling
- `koog/tools/user/UserInteractionTools.kt` — Ask user, create tasks

**Koog APIs used:** `ToolSet`, `@Tool`, `@LLMDescription`, `ToolRegistry`

### 4. Orchestrator Agents (12 files) — DEEPEST INTEGRATION

All use `ai.koog.agents.core.agent.AIAgent` with `strategy{}` DSL:

- `orchestrator/OrchestratorAgent.kt` — Main multi-goal orchestrator (strategy with custom nodes/edges)
- `orchestrator/GoalExecutor.kt` — Single goal execution (LLM request + tool loop)
- `orchestrator/agents/ContextAgent.kt` — Context gathering (structured output)
- `orchestrator/agents/PlannerAgent.kt` — Task decomposition (structured output)
- `orchestrator/agents/ReviewerAgent.kt` — Result review
- `orchestrator/agents/ResearchAgent.kt` — Research via tools
- `orchestrator/agents/EvidenceCollector.kt` — Evidence gathering
- `orchestrator/agents/InterpreterAgent.kt` — Request interpretation
- `orchestrator/agents/SolutionArchitectAgent.kt` — Solution design
- `orchestrator/agents/WorkflowAnalyzer.kt` — Workflow analysis
- `orchestrator/agents/MemoryRetriever.kt` — Memory retrieval
- `orchestrator/agents/CodeMapper.kt` — Code mapping

**Koog APIs used:** `AIAgent`, `AIAgentConfig`, `strategy{}`, `node{}`, `edge()`, `forwardTo`, `nodeLLMRequest`, `nodeLLMRequestStructured`, `nodeLLMSendToolResult`, `nodeExecuteTool`, `onToolCall`, `onAssistantMessage`, `onIsInstance`, `ToolRegistry`, `Prompt.build()`, `LLModel`

### 5. Config & Wiring (5 files)

- `configuration/properties/KoogProperties.kt` — `@ConfigurationProperties(prefix = "jervis.koog")`
- `service/agent/coordinator/KoogWorkflowService.kt` — thin wrapper calling `OrchestratorAgent.run()`
- `service/agent/coordinator/AgentOrchestratorService.kt` — routes Python vs Koog
- `application.yml` — `jervis.koog.*` config block + `ai.koog: INFO` logging
- `build.gradle.kts` — 3 deps: `koog.agents`, `koog.agents.features.snapshot`, `koog.agents.features.memory`

## Removal Difficulty

| Layer | Files | Difficulty | Replacement Options |
|---|---|---|---|
| Package rename (koog-free code) | 4 | Trivial | Move to `com.jervis.qualifier`, `com.jervis.service.cost` |
| Config & wiring | 5 | Easy (after above) | Rename properties |
| LLM Executor | 7 | Medium | LangChain4j, raw HTTP clients, own abstraction |
| Smart Model Selector | 1 | Easy | Own `LLModel` data class |
| Tool definitions | 15 | High | MCP server, OpenAI function calling format, own annotations |
| Agent runtime (orchestrator) | 12 | **Very High** | Move to Python orchestrator (LangGraph), or write own Kotlin state machine |

## Gradle Dependencies

```kotlin
// build.gradle.kts
implementation(libs.koog.agents)
implementation(libs.koog.agents.features.snapshot)
implementation(libs.koog.agents.features.memory)
```

## Recommendation

To fully remove Koog:
1. **Quick wins (trivial):** Move qualifier + cost out of `com.jervis.koog` package
2. **Medium effort:** Replace LLM executor layer with direct HTTP clients or LangChain4j
3. **Major effort:** Move non-coding orchestration to Python orchestrator (extend existing LangGraph), or build own agent runtime in Kotlin
4. Tool definitions need new schema format (MCP or custom) if agent runtime changes
