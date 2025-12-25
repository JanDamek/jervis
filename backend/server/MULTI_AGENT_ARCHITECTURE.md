# Multi-Agent Architecture - Implementation Summary

## Overview

Kompletní refaktoring z monolitického Koog agenta na multi-agent architekturu typu "Agent-as-Tool".

## Motivace

**Problém:**
- Původní `KoogCliTestAgent` byl ~1800 řádků, těžko udržovatelný
- Jeden model pro všechny fáze (neoptimální pro GPU P40)
- Velký kontext se špatně managoval
- Subgraphy nebyly optimální pro multi-model strategii

**Řešení:**
- Orchestrátor (malý, GPU fast model) - routing a koordinace
- Specializované agenty (různé modely podle potřeby)
- Agent volá agenta jako tool
- Kompaktní předávání dat (ProjectScopeSummary ~1-3 KB, ne celé historie)

## Architektura

### 1. DTO Modely (`AgentModels.kt`)

#### Agent Communication Models
```kotlin
data class ProjectScopeSummary(
    val clientId: String,
    val relevantProjects: List<ProjectInfo>,      // 3-5 max
    val contextTags: List<String>,                // 3-10 max
    val contextReferences: List<String>,          // URNs
    val certainty: ProjectCertainty,
)

data class AgentToolRequest<T>(...)
data class AgentToolResponse<T>(...)
data class AgentExecutionMetadata(...)
```

### 2. Samostatné Agenty

#### IntakeAgent (`IntakeAgent.kt`)
**Účel:** Parsování user requestu
**Input:** `String` (raw user text)
**Output:** `IntakeDocument`
**Tools:** NONE (pure structured output)
**Model:** GPU_FAST (14b, 16k context)

**Odpovědnosti:**
- Extrahovat intent (PROGRAMMING/QA/CHAT/UNKNOWN)
- Identifikovat entity (JIRA keys, people, systems, repos)
- Označit unknowns (bez hádání!)
- Extrahovat constraints a communication targets

#### ClientProjectScopeAgent (`ClientProjectScopeAgent.kt`)
**Účel:** Resolve project context early
**Input:** `ScopeAgentInput(IntakeDocument, clientId)`
**Output:** `ProjectScopeSummary`
**Tools:** resolveProjectContext, queryGraph, lookupOwnership (minimal)
**Model:** GPU_FAST nebo CPU_BIG podle potřeby

**Odpovědnosti:**
- Najít top 3-5 relevant projects
- Vytvořit compact context (1-3 KB)
- Uložit detailed data jako URN references
- Set certainty (CERTAIN/CANDIDATES/UNKNOWN)

#### OrchestratorAgent (`OrchestratorAgent.kt`)
**Účel:** Lightweight coordinator
**Input:** `String` (user request)
**Output:** `FinalResponseEnvelope`
**Tools:** IntakeAgentTool, ClientProjectScopeAgentTool
**Model:** GPU_FAST

**Workflow:**
1. Call IntakeAgent → get IntakeDocument
2. Call ClientProjectScopeAgent → get ProjectScopeSummary
3. Route to specialist agents (future: Triage, Discovery, Execution)
4. Assemble final response

### 3. Agent Tools (`AgentTools.kt`)

**Pattern:** Wrap AI agent as Koog Tool

```kotlin
class IntakeAgentTool(
    private val agent: AIAgent<String, IntakeDocument>
) : ToolSet {
    @Tool
    @LLMDescription("...")
    suspend fun runIntakeAgent(userRequest: String): IntakeDocument {
        return runBlocking { agent.run(userRequest) }
    }
}
```

**Klíčové:**
- Implements `ToolSet` interface
- `@Tool` annotation on method
- Synchronous execution via `runBlocking`
- Error handling with fallbacks
- Logging (duration, success/failure)

### 4. Multi-Agent CLI (`MultiAgentCliApplication.kt`)

**Runner pro testování multi-agent workflow**

```bash
--spring.profiles.active=multi-agent-cli
```

**Flow:**
1. Vytvoří IntakeAgent + wrapper
2. Vytvoří ClientProjectScopeAgent + wrapper
3. Vytvoří OrchestratorAgent s těmito tools
4. Spustí test prompt
5. Vypíše výsledek

## Model Configuration

```kotlin
enum class ModelConfig(
    val id: String,
    val provider: LLMProvider,
    val contextLength: Long,
) {
    GPU_FAST("qwen2.5-coder:14b", Ollama, 16384L),
    GPU_BALANCED("qwen2.5-coder:32b", Ollama, 32768L),
    CPU_BIG("qwen2.5-coder:32b", Ollama, 131072L),
}
```

**Strategie:**
- **Orchestrátor:** GPU_FAST (rychlé rozhodování)
- **Intake:** GPU_FAST (malý input)
- **Scope:** GPU_FAST nebo CPU_BIG (podle velikosti context)
- **Future agents:**
  - Triage: GPU_FAST
  - Discovery: CPU_BIG (velký context)
  - Coding: podle velikosti codebase

## Koog Way Principy

### 1. Evidence-Driven Logic
```kotlin
// LLM fills evidence
val facts = llm.extract(TriageFacts::class)

// Kotlin determines deterministicky
val source = when {
    facts.emailThreadId != null -> EMAIL
    facts.slackThreadId != null -> SLACK
    facts.jiraCommentId != null -> JIRA_INTERNAL
    else -> UNKNOWN
}
```

### 2. Minimal Prompting, Maximal Schema
```kotlin
@LLMDescription("Extract structured information")
data class IntakeDocument(...)

// Prompt je krátký:
"""
Analyze user request and extract structured information.
Focus on: intent, entities, unknowns.
"""
```

### 3. Context Hygiene
```kotlin
// BAD: Pass full history
agent.run(fullConversationHistory)  // ❌

// GOOD: Pass compact summary + URNs
val summary = ProjectScopeSummary(
    relevantProjects = top5,  // Just IDs + brief
    contextReferences = urnList,  // Links to full data
)
agent.run(summary)  // ✅
```

### 4. Agent Contracts
```kotlin
// Clear input/output types
AIAgent<IntakeDocument, TriageFacts>

// Measurable limits
maxAgentIterations = 10
maxToolCalls = 20

// Strict tool scoping
tools = listOf("listTasks", "hybridSearch")  // Only these!
```

## Implementační Status

### ✅ Hotovo

1. **DTO modely** - ProjectScopeSummary, AgentToolRequest/Response
2. **IntakeAgent** - Kompletní, kompiluje, testováno
3. **ClientProjectScopeAgent** - Kompletní, kompiluje
4. **AgentTools** - Wrappers pro IntakeAgent a ScopeAgent
5. **OrchestratorAgent** - Základní orchestrace (2 agenty)
6. **MultiAgentCliApplication** - Test runner
7. **Config** - application-multi-agent-cli.yml
8. **Build** - ✅ Vše kompiluje

### 🚧 TODO (Future Work)

#### Short-term:
1. **TriageAgent** - Minimal mandatory facts collection
2. **DiscoveryAgent** - Evidence gathering loop
3. **DecompositionAgent** - ExecutionPlan creation
4. **ExecutionDispatcherAgent** - Workstream routing

#### Execution Agents:
5. **CommunicationExecutionAgent** - Email/Slack drafts
6. **JiraUpdateExecutionAgent** - JIRA write operations
7. **CodingExecutionAgent** - Code read/write + Aider/OpenHands
8. **TechAnalysisExecutionAgent** - Read-only analysis

#### Advanced:
9. **FinalizerAgent** - Final report assembly
10. **Model Selection Logic** - Deterministic GPU vs CPU routing
11. **History Compression** - Per-agent between subgraphs
12. **Parallel Execution** - Safe parallel workstreams
13. **Integration Tests** - End-to-end multi-agent scenarios

## Soubory

```
backend/server/src/main/kotlin/com/jervis/cli/
├── AgentModels.kt                  # DTO modely (✅)
├── agents/
│   ├── IntakeAgent.kt             # Parse user request (✅)
│   ├── ClientProjectScopeAgent.kt # Resolve context (✅)
│   ├── OrchestratorAgent.kt       # Coordinator (✅)
│   └── AgentTools.kt              # Tool wrappers (✅)
├── MultiAgentCliApplication.kt    # Test runner (✅)
├── KoogCliTestAgent.kt            # Legacy monolith (zachovat pro referenci)
└── KoogCliApplication.kt          # Legacy runner

backend/server/src/main/resources/
└── application-multi-agent-cli.yml # Config (✅)
```

## Jak Spustit

```bash
# Zajistit běžící Ollama
ollama serve

# Stáhnout model
ollama pull qwen2.5-coder:14b

# Spustit multi-agent CLI
./gradlew :backend:server:bootRun --args="--spring.profiles.active=multi-agent-cli"
```

**Výstup:**
```
Available test prompts:
[0] Fix the login bug in AUTH-123 and notify the team via email
[1] What is the status of deployment to production?
[2] Implement user authentication feature for mobile app

Select prompt (0-2, or press Enter for default 0):
> 0

🚀 Running multi-agent orchestrator...

================================================================================
ORCHESTRATOR RESULT
================================================================================

User Summary:
Request processed: PROGRAMMING. Found 2 relevant projects.

Executive Summary:
Multi-agent orchestration completed.

Metadata:
- Request ID: abc-123
- Confidence: 70%
================================================================================
```

## Výhody Nové Architektury

### 1. **Maintainability**
- Každý agent je ~150-200 řádků (vs 1800 původně)
- Clear separation of concerns
- Snadné unit testing

### 2. **Model Flexibility**
- GPU fast pro routing
- CPU big pro heavy context
- Per-agent model selection

### 3. **Context Management**
- Compact summaries (1-3 KB)
- URN references místo raw data
- No history bloat

### 4. **Scalability**
- Agents můžou běžet parallel (future)
- Independent deployment (future microservices)
- Hierarchical agent calls (agent → agent → agent)

### 5. **Testability**
- Mock individual agents
- Test tool wrappers independently
- Clear input/output contracts

## Best Practices

### Agent Design
- ✅ Input/Output strongly typed
- ✅ Minimal tool allowlist
- ✅ Clear @LLMDescription
- ✅ Fallback on errors
- ✅ Execution metadata logging

### Tool Wrappers
- ✅ Implement ToolSet interface
- ✅ Use runBlocking for sync execution
- ✅ Log start/duration/result
- ✅ Graceful error handling
- ✅ Return fallback on failure

### Orchestrator
- ✅ Minimal prompts
- ✅ Deterministic routing (Kotlin, not LLM)
- ✅ Pass compact context
- ✅ Store intermediate results in storage keys
- ✅ Use agent tools, not subgraphs

## Troubleshooting

### Tool Registration Issues
**Problem:** `Argument type mismatch: actual type is 'XTool', but 'List<Tool>' was expected`

**Solution:** Ensure tool wrapper implements `ToolSet`:
```kotlin
class MyAgentTool(...) : ToolSet {  // ← Must implement ToolSet
    @Tool
    suspend fun runMyAgent(...): Output { ... }
}
```

### Model Not Found
**Problem:** `Model 'qwen2.5-coder:14b' not found`

**Solution:**
```bash
ollama pull qwen2.5-coder:14b
```

### Context Too Large
**Problem:** Agent exceeds context window

**Solution:**
1. Use CPU_BIG model config
2. Compress context (use summaries)
3. Split into smaller agents

## Metrics & Monitoring

Each agent tool logs:
- Duration (ms)
- Success/failure
- Model used
- Token estimates (future)
- Tool calls count (future)

**Example:**
```
TOOL | runIntakeAgent | SUCCESS | duration=1234ms | intent=PROGRAMMING
TOOL | runScopeAgent | SUCCESS | duration=2345ms | projects=3 | certainty=CANDIDATES
```

## References

- Koog SDK: https://docs.koog.ai
- Original issue: Multi-agent refactoring task
- Design doc: ZADÁNÍ section in conversation history
