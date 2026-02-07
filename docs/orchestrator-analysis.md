# Orchestrator Agent – Kompletní analýza a plán vylepšení

**Datum:** 2026-02-07 (rev.7)
**Autor:** Automatizovaná analýza
**Rozsah:** Kompletní redesign – architektura, agenti, tools, GPU, streaming, approval, UI settings, K8s scaling, MCP KB, Claude CLI, Jobs

---

## Obsah

1. [Současná architektura](#1-současná-architektura)
2. [Identifikované slabé místa](#2-identifikované-slabé-místa)
3. [Analýza Koog frameworku – limity a omezení](#3-analýza-koog-frameworku)
4. [Python microservice varianta – analýza](#4-python-microservice-varianta)
5. [Plán vylepšení – 3 varianty](#5-plán-vylepšení)
6. [Doporučená varianta a roadmapa](#6-doporučená-varianta-a-roadmapa)
7. [Streaming & Live Process Visibility](#7-streaming--live-process-visibility)
8. [Approval Flow – Risky Step Protection](#8-approval-flow--risky-step-protection)
9. [Tool Migration – kompletní plán přesunu do Pythonu](#9-tool-migration--kompletní-plán-přesunu-do-pythonu)
10. [Komunikační architektura Python ↔ Kotlin ↔ UI](#10-komunikační-architektura)
11. [Model Strategy – P40 GPU, VRAM budget, hybridní routing](#11-model-strategy--p40-gpu-vram-budget-hybridní-routing)
12. [Hybridní model routing – lokální + placené modely](#12-hybridní-model-routing--lokální--placené-modely)
13. [Unified Agent Interface – Claude Code jako 4. coding agent](#13-unified-agent-interface--claude-code-jako-4-coding-agent)
14. [Agent Settings UI & K8s Dynamic Scaling](#14-agent-settings-ui--k8s-dynamic-scaling)
15. [K8s Strategie – Job vs Deployment vs Hybrid (revidovaná)](#15-k8s-strategie--job-vs-deployment-vs-hybrid-revidovaná)
16. [MCP Server pro Knowledge Base – přístup coding agentů ke KB](#16-mcp-server-pro-knowledge-base)
17. [Claude CLI Agent – analýza aktuální implementace a vylepšení](#17-claude-cli-agent)
18. [Cílová architektura – Python orchestrator, K8s Jobs, univerzální KB přístup](#18-cílová-architektura)

---

## 1. Současná architektura

### 1.1 Řetězec volání

```
UI (Desktop/Mobile)
  → kRPC → AgentOrchestratorRpcImpl
    → AgentOrchestratorService
      → KoogWorkflowService
        → OrchestratorAgent.run()
          → OrchestratorAgent.create() – vytvoří AIAgent s graph strategy
            → InterpreterAgent  (decompose query → MultiGoalRequest)
            → GoalExecutor.executeGoal() (pro každý goal)
              → ContextAgent (gather context)
              → PlannerAgent (create OrderedPlan)
              → executePlan() – nový AIAgent s tool-loop
              → ReviewerAgent (pokud HIGH complexity)
            → Agregace výsledků → final answer
```

### 1.2 Sub-agenti (11 celkem)

| # | Agent | Účel | Typ strategie |
|---|-------|------|---------------|
| 1 | InterpreterAgent | Decompose query → MultiGoalRequest | Structured output (single LLM call) |
| 2 | ContextAgent | Gather project context | Structured output + tools |
| 3 | PlannerAgent | Create OrderedPlan | Structured output (single LLM call) |
| 4 | ResearchAgent | Evidence gathering (tool loop) | Graph strategy s tool loop |
| 5 | ReviewerAgent | Completeness review | Structured output + tools |
| 6 | SolutionArchitectAgent | Technical spec for coding | Structured output |
| 7 | WorkflowAnalyzer | Map tracker states | Structured output |
| 8 | ProgramManager | Epic planning (deterministic) | Pure Kotlin (no LLM) |
| 9 | MemoryRetriever | Query RAG/GraphDB | Structured output + tools |
| 10 | EvidenceCollector | Fetch from trackers | Structured output + tools |
| 11 | CodeMapper | Identify code entrypoints | Structured output + tools |

### 1.3 Datový tok

```
UserInput
  → InterpreterAgent → MultiGoalRequest (goals[], dependencyGraph)
  → for each goal (sequential, respecting deps):
      → ContextAgent → ContextPack
      → PlannerAgent → OrderedPlan
      → GoalExecutor.executePlan() → EvidencePack
      → ReviewerAgent → ReviewResult (optional)
  → GoalResult[] → Aggregation → Final string answer
```

---

## 2. Identifikované slabé místa

### 🔴 KRITICKÉ (P0)

#### 2.1 Mutable state capture ve strategy closures

**Soubor:** `OrchestratorAgent.kt:215-217`

```kotlin
var multiGoalRequest: MultiGoalRequest? = restoredCheckpoint?.multiGoalRequest
var completedGoals = restoredCheckpoint?.completedGoals?.toMutableMap() ?: mutableMapOf()
var conversationContext = restoredCheckpoint?.conversationContext
```

**Problém:** Koog framework explicitně varuje před external mutable state v nodech (viz `koog.md:466-480` – "Anti-Pattern: External Mutable State"). Orchestrátor zachycuje `var` proměnné z vnějšího scope ve strategy closures. To je:
- Nespolehlivé při restartech/retries
- Netestovatelné v izolaci
- Porušuje state-flow princip Koog frameworku

**Dopad:** Při selhání a retry se stav neobnoví korektně. Při paralelních requestech může dojít k race condition na `completedGoals`.

#### 2.2 Každý goal spawn-uje nového Koog AIAgenta

**Soubor:** `GoalExecutor.kt:283-294`

```kotlin
val executionAgent = AIAgent(
    promptExecutor = promptExecutorFactory.getExecutor("OLLAMA"),
    toolRegistry = toolRegistry,
    strategy = executionStrategy,
    agentConfig = AIAgentConfig(prompt = prompt, model = model, maxAgentIterations = 50),
)
```

**Problém:** Pro každý goal se vytváří nový `AIAgent` s novou strategií, novým prompt executorem, novým tool registry. To znamená:
- Žádný sdílený kontext mezi goaly (každý agent začíná od nuly)
- Obrovský overhead na inicializaci (~50-100ms per agent + LLM warm-up)
- Ztráta tool call history - LLM neví co ostatní goaly udělaly
- Žádná history compression mezi goaly

**Dopad:** Multi-goal requesty trvají násobně déle než by musely. Agent opakuje search queries které už jiný agent provedl.

#### 2.3 Checkpoint nefunkční – data se zahazují po dokončení

**Soubor:** `OrchestratorAgent.kt:776-778`

```kotlin
val checkpoint = OrchestratorCheckpoint(
    multiGoalRequest = null,        // ← VŽDY null po dokončení
    completedGoals = emptyMap(),     // ← VŽDY prázdná
    ...
)
```

**Problém:** Po dokončení goalu se checkpoint serializuje s `null/empty` hodnotami. Checkpoint je tedy užitečný POUZE pokud agent spadne uprostřed. Ale protože checkpoint se zapisuje až PO dokončení, nemá to praktický efekt – při selhání se checkpoint nezapíše.

**Dopad:** Checkpoint systém je efektivně mrtvý kód. Resume po selhání nefunguje.

#### 2.4 `extractArtifacts()` vrací vždy prázdný list

**Soubor:** `GoalExecutor.kt:331-334`

```kotlin
private fun extractArtifacts(evidence: EvidencePack?): List<Artifact> {
    if (evidence == null) return emptyList()
    return emptyList()  // ← VŽDY prázdné
}
```

**Problém:** Artifacts (změněné soubory, vytvořené JIRA tickets, etc.) se nikdy neextrahují z výsledků. UI tedy nikdy neuvidí co agent konkrétně udělal.

**Dopad:** Uživatel neví jaké soubory se změnily, jaké tickety se vytvořily. Output je jen text bez strukturovaných metadat.

---

### 🟠 VYSOKÉ (P1)

#### 2.5 Hardcoded executor "OLLAMA" – ignoruje model selection

**Soubory:** `OrchestratorAgent.kt:213`, `GoalExecutor.kt:285`, všechny sub-agenti

```kotlin
val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")
```

**Problém:** SmartModelSelector sice vybere vhodný model (4k/16k/64k/256k), ale prompt executor je VŽDY Ollama. Nelze přepnout na Anthropic/OpenAI/Google pro složitější úlohy. System prompt říká "Cloud coding agents = PAID", ale routing na cloud providery neexistuje.

**Dopad:** Agent nemůže použít kvalitnější model ani když má uživatel budget. Vše běží přes lokální Ollama.

#### 2.6 Duplicitní tool registrace – stejné tools ve 3 vrstvách

**Problém:** Stejné tools se registrují na 3 místech:
1. `OrchestratorAgent.create()` – toolRegistry s 18+ tool setů
2. `GoalExecutor.executePlan()` – toolRegistry se předává celý
3. Sub-agenti (ResearchAgent, EvidenceCollector, etc.) – registrují vlastní podmnožinu stejných tools

**Dopad:**
- GoalExecutor executionAgent má přístup ke VŠEM orchestrátorovým tools (i k InterpreterAgent, PlannerAgent atd.) – vnitřní agent může volat orchestrátorovy meta-tools a vytvořit rekurzivní smyčku
- Sub-agenti mají nekonzistentní podmnožiny – ResearchAgent nemá scheduling tools, ale má coding tools

#### 2.7 Absence error recovery v GoalExecutor

**Soubor:** `GoalExecutor.kt:109-127`

```kotlin
} catch (e: Exception) {
    return GoalResult(
        success = false,
        errors = listOf(e.message ?: "Unknown error"),
        ...
    )
}
```

**Problém:** Při selhání goalu se vrátí `GoalResult(success=false)` a pokračuje se na další goal. Ale:
- Žádný retry mechanismus
- Žádný fallback (např. zkusit jednodušší plán)
- Závislé goaly se nespustí (ale nevrátí se chyba typu "dependency failed")
- WaitingForDependencies stav je dead-end (nodeWaitingError → nodeFinish)

**Dopad:** Když selže goal g1 a goal g3 závisí na g1, uživatel dostane cryptický "Některé úkoly nelze dokončit kvůli nevyřešeným závislostem."

#### 2.8 `maxAgentIterations: 100` na VŠECH sub-agentech (i single-shot)

**Soubory:** Všechny sub-agenti

```kotlin
maxAgentIterations = 100  // Na PlannerAgent, InterpreterAgent, WorkflowAnalyzer...
```

**Problém:** Agenti jako `PlannerAgent`, `InterpreterAgent`, `WorkflowAnalyzer` dělají JEDEN structured LLM call (start → nodeLLMRequestStructured → finish). Limit 100 iterací je zcela zbytečný a při stuck LLM by agent donekonečna opakoval neúspěšný call.

**Dopad:** Waste compute resources. Když LLM selhává na structured output, bude to opakovat 100x místo fail-fast.

#### 2.9 InternalAgentTools exponuje agenty jako tools – bezpečnostní riziko

**Soubor:** `InternalAgentTools.kt`

**Problém:** `InternalAgentTools` wrappuje sub-agenty (InterpreterAgent, PlannerAgent, ResearchAgent...) jako Koog `@Tool` callables. Tyto tools jsou registrovány v executionAgentovi v GoalExecutoru (přes `toolRegistry`). To znamená že execution LLM může:
- Zavolat `interpretRequest()` → spustit novou decomposition
- Zavolat `createPlan()` → přepsat aktuální plán
- Zavolat `gatherEvidence()` → spustit nový research loop uvnitř execution loop

**Dopad:** Nekontrolovaná rekurze. Execution agent by mohl zavolat `interpretRequest()` uvnitř `executePlan()`, čímž by spustil novou decomposition uvnitř execution. Koog framework nemá built-in rekurzní limity.

---

### 🟡 STŘEDNÍ (P2)

#### 2.10 Naivní language detection

**Soubor:** `OrchestratorAgent.kt:150-177`

```kotlin
private fun detectLanguage(text: String): String {
    val czechKeywords = listOf("najdi", "které", ...)
    val lowerText = text.lowercase()
    val czechCount = czechKeywords.count { lowerText.contains(" $it ") || ... }
    return if (czechCount >= 2) "cs" else "en"
}
```

**Problém:** Hardcoded seznam 17 českých slov. Selhává na:
- Slovenštině (velmi podobná, ale jiné idiomy)
- Krátkých zprávách ("oprav to" → 1 word match, detected as "en")
- Mixed-language queries ("fix bug v UserService")

#### 2.11 Aggregace je hardcoded česky

**Soubor:** `OrchestratorAgent.kt:386-454`

```kotlin
appendLine("# 📋 Výsledky zpracování")
appendLine("Zpracováno **${results.size} úkolů** z původního požadavku:")
// ...
appendLine("- ✅ Dokončeno: $successCount")
```

**Problém:** Agregační template je hardcoded v češtině. Pokud uživatel píše anglicky, dostane českou odpověď.

#### 2.12 GoalExecutor nepoužívá ReviewerAgent výsledek

**Soubor:** `GoalExecutor.kt:88-90`

```kotlin
if (shouldReview(goal)) {
    reviewExecution(goal, plan, evidence, entityTask)
    // ← výsledek se ignoruje!
}
```

**Problém:** `reviewExecution()` vrací `ReviewResult` (complete/missingParts/violations), ale výsledek se zahazuje. Žádná iterace na základě review findings.

#### 2.13 Conversation history se načte ale nepoužije

**Soubor:** `OrchestratorAgent.kt:687-701`

```kotlin
val conversationHistory = chatMessageRepository
    .findByTaskIdOrderBySequenceAsc(task.id).toList()
val hasHistory = conversationHistory.isNotEmpty()
// ... jen logování, nikdy se nepředá agentovi!
```

**Problém:** Chat history se načte z MongoDB, zapíše se log, ale nikdy se nepřidá do promptu agenta. Agent tak nezná předchozí konverzaci.

#### 2.14 OrchestratorState je dead code

**Soubor:** `OrchestratorState.kt`

**Problém:** Celá třída `OrchestratorState` s 20+ metodami (hasInterpretation, hasPlan, withEvidence...) se NIKDE nepoužívá. Je to pozůstatek starší architektury. Aktuální orchestrátor používá `MultiGoalRequest` + `GoalResult` + `GoalSelection`.

#### 2.15 NormalizedRequest model nepoužit orchestrátorem

**Soubory:** `NormalizedRequest.kt`, `OrchestratorState.kt`

**Problém:** `NormalizedRequest` (s type, goals, entities, outcome) se nepoužívá v aktuálním flow. InterpreterAgent vrací `MultiGoalRequest`. Je to stará abstrakce z doby jednociálového orchestrátoru.

#### 2.16 ExecutionMemoryTools je globální static state

**Soubor:** `ExecutionMemoryTools.kt:28`

```kotlin
private val executionMemory = ConcurrentHashMap<String, MutableMap<String, String>>()
```

**Problém:** Globální `ConcurrentHashMap` jako companion object. Memory se nikdy automaticky nečistí – jen explicitně přes `clearExecutionMemory()` tool call. Pokud agent tool nezavolá, paměť roste neomezeně.

---

### 🔵 NÍZKÉ (P3)

#### 2.17 TaskDocument existuje 2x s různými typy

**Soubory:** `entity.TaskDocument` vs `orchestrator.model.TaskDocument`

**Problém:** Existují 2 třídy `TaskDocument` – entity verze (MongoDB document s typovými wrappery jako TaskId, ClientId) a model verze (jednoduchý serializable data class se stringy). Konverze mezi nimi je rozptýlená po kódu (`convertTask()` v InternalAgentTools, `toEntity()` v GoalExecutor).

#### 2.18 Nekonzistentní model selection (blocking vs suspend)

Některé agenti volají `selectModelBlocking()`, jiné `selectModel()` (suspend). Nekonzistentní pattern.

#### 2.19 Žádné testy

Neexistují žádné unit testy ani integration testy pro orchestrátor, sub-agenty, ani tools. Jediný test v celém backendu je `TikaDocumentProcessorTest`.

---

## 3. Analýza Koog frameworku

### 3.1 Co Koog dělá dobře

| Vlastnost | Hodnota pro Jervis |
|-----------|-------------------|
| Type-safe graph DSL | Compile-time validace node/edge typů |
| Structured output | `nodeLLMRequestStructured<T>()` s automatickými retries |
| Tool system | `@Tool` annotations s LLM descriptions |
| History compression | `nodeLLMCompressHistory()` pro long-running agenty |
| Event system | OpenTelemetry tracing s bohatými eventy |
| Multi-provider support | Ollama, Anthropic, OpenAI, Google |

### 3.2 Kde Koog OMEZUJE Jervis

#### 3.2.1 Žádný native multi-agent coordination

Koog nemá built-in koncept "orchestrátor volá sub-agenty". Workaround v Jervis je:
- Sub-agenti wrappovaní jako `@Tool` v `InternalAgentTools`
- Každý sub-agent je nový `AIAgent` instance
- Žádný sdílený kontext, paměť, nebo message bus

V Pythonu: LangGraph má `StateGraph` s native multi-agent handoff. CrewAI má `Crew` s rolemi. AutoGen má `GroupChat`.

#### 3.2.2 Context window management je manuální

Koog `contextLength` je deklarativní (metadata). Skutečné řízení kontextu (num_ctx pro Ollama) musí být v Modelfile. To znamená:
- Nelze dynamicky měnit context window za běhu
- SmartModelSelector vybere model ale nemůže nastavit num_ctx
- History compression je manuální (`prompt.messages.size > 50`)

V Pythonu: LangChain má `ConversationBufferWindowMemory`, `ConversationSummaryMemory` s automatickým managementem.

#### 3.2.3 Žádný streaming support pro agenty

Koog nemá native streaming pro agentové výstupy. Orchestrátor emituje progress přes callback `onProgress()`, ale nemůže streamovat partial results z LLM.

V Pythonu: LangChain/LangGraph mají native `astream_events()`, `async for chunk in chain.astream()`.

#### 3.2.4 Omezené debugging/observability

Koog eventy jsou bohaté, ale chybí:
- Vizuální debugger pro strategy graphy
- Replay capability (zopakovat agent run z checkpointu)
- Token counting per node/edge
- Cost tracking per agent invocation

V Pythonu: LangSmith, Weights & Biases, Phoenix mají full agent tracing s vizualizací.

#### 3.2.5 Koog 0.6.0 je young framework

- Omezená komunita a ecosystem
- Nedostatek příkladů pro complex multi-agent patterns
- Breaking changes mezi verzemi (0.5.3 → 0.5.4 → 0.6.0 – viz docs)
- Závislost na JetBrains maintaineru

### 3.3 Kde Koog je VÝHODA oproti Pythonu

| Vlastnost | Koog (Kotlin) | Python alternativy |
|-----------|---------------|-------------------|
| Type safety | Compile-time typové kontroly | Runtime errors, mypy nepovinný |
| JVM performance | Nativní Spring Boot integrace | Dvojí framework (FastAPI + agent fw) |
| Shared codebase | Backend + Desktop + Mobile sdílejí typy | Potřeba gRPC/REST API boundary |
| Kotlin coroutines | Native structured concurrency | asyncio + executor pool |
| Multiplatform DTO | `shared/common-dto` pro všechny klienty | Potřeba OpenAPI codegen |

---

## 4. Python microservice varianta

### 4.1 Kandidátní frameworky

| Framework | Silné stránky | Slabé stránky | Vhodnost |
|-----------|---------------|---------------|----------|
| **LangGraph** | StateGraph, checkpointing, human-in-the-loop, streaming, multi-agent | Komplexita, vendor lock (LangChain) | ⭐⭐⭐⭐⭐ |
| **CrewAI** | Role-based agents, jednoduchý API | Omezená kontrola nad flow, méně flexibilní | ⭐⭐⭐ |
| **AutoGen** | Multi-agent conversation, group chat | Komplexní setup, Microsoft ekosystém | ⭐⭐⭐ |
| **DSPy** | Optimalizace promptů, modulární | Jiný paradigm (compile-time), méně vhodný pro orchestration | ⭐⭐ |
| **Pydantic AI** | Type-safe, rychlý, jednoduchý | Méně mature, omezený agent pattern | ⭐⭐⭐ |

### 4.2 Doporučení: LangGraph

**Proč LangGraph:**
1. **StateGraph** = přímý ekvivalent Koog graph strategy, ale s native checkpointing
2. **Human-in-the-loop** = vestavěný `interrupt()` pro askUser() pattern
3. **Streaming** = `astream_events()` pro real-time progress
4. **Multi-agent** = sub-grafy s native handoff
5. **Persistence** = PostgreSQL/Redis checkpointer out-of-the-box
6. **Observability** = LangSmith integration, vizuální debugger
7. **Community** = Velká komunita, mnoho příkladů, aktivní vývoj

### 4.3 Architektura Python microservice

```
┌─────────────────────────────────────────────────────────────┐
│                 Python Orchestrator Service                    │
│                     (FastAPI + LangGraph)                      │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────────┐    ┌────────────────────┐              │
│  │   API Layer      │    │   State Management │              │
│  │  (FastAPI/gRPC)  │    │   (LangGraph       │              │
│  │                  │    │    Checkpointer)    │              │
│  └──────────────────┘    └────────────────────┘              │
│                                                               │
│  ┌──────────────────────────────────────────────┐            │
│  │            Orchestrator Graph                  │            │
│  │                                                │            │
│  │  decompose → select_goal → execute_goal       │            │
│  │       ↑                       ↓               │            │
│  │       └──── next_goal ←── review              │            │
│  │                               ↓               │            │
│  │                          aggregate → end       │            │
│  └──────────────────────────────────────────────┘            │
│                                                               │
│  ┌──────────────────────────────────────────────┐            │
│  │            Sub-Agent Graphs                    │            │
│  │  • InterpreterGraph                            │            │
│  │  • ContextGraph                                │            │
│  │  • PlannerGraph                                │            │
│  │  • ResearchGraph (tool loop)                   │            │
│  │  • ReviewerGraph                               │            │
│  │  • ArchitectGraph                              │            │
│  └──────────────────────────────────────────────┘            │
│                                                               │
│  ┌──────────────────────────────────────────────┐            │
│  │            Tool Integrations                   │            │
│  │  • KnowledgeBase client (→ service-kb Python)  │            │
│  │  • BugTracker client (→ Kotlin server REST)    │            │
│  │  • Wiki client (→ Kotlin server REST)          │            │
│  │  • CodingAgent client (→ Aider/OpenHands)      │            │
│  │  • LLM providers (Ollama/Anthropic/OpenAI)     │            │
│  └──────────────────────────────────────────────┘            │
│                                                               │
│  ┌──────────────────────────────────────────────┐            │
│  │            LLM Provider Abstraction            │            │
│  │  • litellm (unified API for all providers)     │            │
│  │  • SmartModelSelector (ported logic)           │            │
│  │  • Cost tracking                               │            │
│  └──────────────────────────────────────────────┘            │
└─────────────────────────────────────────────────────────────┘
```

### 4.4 Komunikace s Kotlin serverem

```
Kotlin Server (Spring Boot)
  ├── REST API pro orchestrátor:
  │   ├── GET  /api/internal/projects/{id}/context
  │   ├── POST /api/internal/knowledge/search
  │   ├── POST /api/internal/knowledge/ingest
  │   ├── GET  /api/internal/bugtracker/{clientId}/issues
  │   ├── GET  /api/internal/wiki/{clientId}/pages
  │   └── POST /api/internal/coding/delegate
  │
  └── gRPC/WebSocket pro streaming:
      ├── OrchestratorStream.execute(request) → stream<progress>
      └── ChatStream.send(response) → UI
```

### 4.5 Migrace tools – mapování

| Kotlin Tool | Python ekvivalent |
|-------------|-------------------|
| KnowledgeStorageTools | REST client → service-knowledgebase (Python, už existuje!) |
| BugTrackerReadTools | REST client → Kotlin server `/api/internal/bugtracker` |
| WikiReadTools | REST client → Kotlin server `/api/internal/wiki` |
| EmailReadTools | REST client → Kotlin server `/api/internal/email` |
| CodingTools | REST/WebSocket client → service-aider, service-coding-engine |
| JoernTools | REST client → service-joern |
| ExecutionMemoryTools | LangGraph state (native, žádný hack) |
| ChatHistoryTools | LangGraph memory (native) |
| ValidationTools | Python function |
| ProjectStructureTools | REST client → Kotlin server |
| SchedulerTools | REST client → Kotlin server |
| UserInteractionTools | LangGraph interrupt() (native human-in-the-loop) |

---

## 5. Plán vylepšení – 3 varianty

### Varianta A: Opravit stávající Koog implementaci (konzervativní)

**Rozsah:** Opravit kritické bugy, zachovat Koog framework
**Odhadovaná složitost:** Střední
**Riziko:** Nízké

#### Kroky:

1. **Fix mutable state capture** – Přepsat strategy na proper state flow přes nodes
   - Vytvořit `OrchestratorFlowState` data class
   - Předávat stav přes node input/output místo var captures

2. **Fix checkpoint** – Checkpoint zapisovat PŘED execution, ne po
   - Implementovat checkpoint do GoalExecutor na úrovni každého phase
   - Checkpoint po každém dokončeném goalu

3. **Fix artifact extraction** – Implementovat `extractArtifacts()`
   - Parsovat EvidencePack pro file paths, JIRA keys, etc.

4. **Fix conversation history** – Přidávat do promptu
   - Serializovat posledních N zpráv do system promptu

5. **Fix review usage** – Implementovat review → iterate loop
   - Pokud ReviewResult.complete == false, re-execute missing steps

6. **Izolovat tool registry** – Separate tools pro orchestrátor vs execution agent
   - ExecutionAgent NESMÍ mít přístup k InternalAgentTools

7. **Fix maxIterations** – Single-shot agenti na 3-5, tool-loop agenti na 30-50

8. **Přidat testy** – Unit testy pro každý sub-agent, integration test pro celý flow

9. **Remove dead code** – OrchestratorState, NormalizedRequest, unused imports

#### Výhody:
- Minimální riziko regrese
- Zachová existující Kotlin type safety
- Zachová shared codebase s UI

#### Nevýhody:
- Koog limitace zůstávají (žádný native multi-agent, limited streaming)
- Neřeší fundamentální design problémy
- Framework dependency risk (Koog 0.6.0 maturity)

---

### Varianta B: Python microservice orchestrátor (přepisová)

**Rozsah:** Nový Python service nahradí orchestrátor, Kotlin server se stane "API gateway"
**Odhadovaná složitost:** Vysoká
**Riziko:** Střední-vysoké

#### Kroky:

**Fáze 1: Infrastruktura**
1. Vytvořit `backend/service-orchestrator/` (Python, FastAPI)
2. Nastavit Docker container, K8s deployment
3. Implementovat komunikaci s Kotlin serverem (REST internal API)
4. Implementovat LLM provider abstraction (litellm)

**Fáze 2: Core orchestrátor**
5. Implementovat LangGraph StateGraph pro orchestrátor
6. Portovat InterpreterAgent → Python (structured output)
7. Portovat GoalExecutor → Python sub-graph
8. Implementovat native checkpointing (PostgreSQL)

**Fáze 3: Sub-agenti**
9. Portovat ContextAgent, PlannerAgent, ReviewerAgent
10. Portovat ResearchAgent s tool loop
11. Portovat SolutionArchitectAgent, WorkflowAnalyzer
12. Portovat ProgramManager (pure logic, easy)

**Fáze 4: Tool integrace**
13. Implementovat REST clients pro Kotlin server tools
14. Přímá integrace s service-knowledgebase (Python ↔ Python)
15. Implementovat streaming progress → UI

**Fáze 5: Migrace**
16. Shadow mode – obě implementace běží paralelně
17. A/B testing – porovnat kvalitu odpovědí
18. Cutover – přepnout na Python orchestrátor

#### Výhody:
- LangGraph native checkpointing, streaming, human-in-the-loop
- Obrovský Python AI ecosystem (LangSmith, evaluation tools)
- Lepší debugging a observability
- Přímá integrace s service-knowledgebase (Python)
- Větší komunita, více příkladů
- litellm = jednotný interface pro 100+ LLM providerů

#### Nevýhody:
- Nový service v stacku = operational overhead
- Ztráta Kotlin type safety na boundary
- Potřeba REST/gRPC API boundary místo direct method calls
- 2 jazyky v hlavním pipeline = vyšší cognitive load
- Risk migrace – paralelní běh po dobu přechodu

---

### Varianta C: Hybridní – Python orchestrátor + Kotlin tools (pragmatická) ⭐ DOPORUČENÁ

**Rozsah:** Python microservice pro orchestration logic, Kotlin server zachová tools/integrace
**Odhadovaná složitost:** Střední-vysoká
**Riziko:** Střední

#### Architektura:

```
┌─────────────────────────┐     ┌─────────────────────────┐
│  Python Orchestrator    │     │  Kotlin Server           │
│  (FastAPI + LangGraph)  │◄───►│  (Spring Boot)           │
│                         │REST │                          │
│  • Graph orchestration  │     │  • Tool execution        │
│  • Multi-agent coord.   │     │  • DB access (Mongo)     │
│  • Checkpointing        │     │  • External integrations │
│  • Streaming            │     │  • kRPC to UI            │
│  • LLM provider routing │     │  • Auth/Security         │
└─────────────────────────┘     └──────────────────────────┘
         │                                  │
         ▼                                  ▼
┌─────────────────────┐          ┌──────────────────────┐
│ service-knowledgebase│          │ service-aider/coding │
│ (Python – existuje!)│          │ service-joern        │
│                     │          │ service-atlassian    │
└─────────────────────┘          └──────────────────────┘
```

#### Klíčový princip: "Brain in Python, Hands in Kotlin"

- **Python** = rozhodování, plánování, orchestrace, LLM volání
- **Kotlin** = tool execution, DB operace, UI komunikace, bezpečnost

#### Kroky:

**Fáze 1: Základ (týden 1-2)**
1. Vytvořit `backend/service-orchestrator/` (Python, FastAPI, LangGraph)
2. Kotlin server: Vytvořit `/api/internal/orchestrator/*` REST endpointy
   - `/api/internal/tools/knowledge/search`
   - `/api/internal/tools/knowledge/ingest`
   - `/api/internal/tools/bugtracker/*`
   - `/api/internal/tools/wiki/*`
   - `/api/internal/tools/coding/delegate`
   - `/api/internal/tools/project/context`
   - `/api/internal/tools/user/ask`
3. Python: LLM provider setup (litellm + SmartModelSelector port)
4. Docker + K8s deployment config

**Fáze 2: Core flow (týden 3-4)**
5. Python: Implementovat InterpreterGraph (decompose → MultiGoalRequest)
6. Python: Implementovat GoalExecutorGraph (context → plan → execute → review)
7. Python: Implementovat tool wrappers (REST volání do Kotlin serveru)
8. Python: Implementovat checkpointing (PostgreSQL/Redis)
9. Kotlin: AgentOrchestratorService → delegovat na Python microservice

**Fáze 3: Sub-agenti (týden 5-6)**
10. Python: ResearchAgent s LangGraph tool loop
11. Python: SolutionArchitectAgent
12. Python: ProgramManager (pure Python logic)
13. Python: Streaming progress → Kotlin → UI

**Fáze 4: Advanced features (týden 7-8)**
14. Python: Human-in-the-loop (LangGraph interrupt)
15. Python: Learning/Preferences integration
16. Python: LangSmith observability
17. Python: Evaluation framework (zlaté testy)

**Fáze 5: Cutover (týden 9-10)**
18. Shadow mode + A/B testing
19. Performance tuning
20. Remove old Kotlin orchestrátor kód
21. Documentation update

#### Výhody:
- Nejlepší z obou světů – Python AI power + Kotlin stability
- Inkrementální migrace – starý orchestrátor funguje do cutoveru
- Kotlin server zachovává své silné stránky (type safety, UI, security)
- Python service je izolovaný – selhání neovlivní zbytek systému
- Přímá integrace s existujícím service-knowledgebase (Python)
- Native checkpointing, streaming, human-in-the-loop z LangGraph

#### Nevýhody:
- Přidává network hop (Python ↔ Kotlin REST)
- Potřeba udržovat API kontrakt mezi službami
- Monitoring/logging přes 2 služby

---

## 6. Doporučená varianta a roadmapa

### Doporučení: Varianta C (Hybridní)

**Důvody:**
1. Python AI ecosystem je řádově větší než Koog – LangGraph, LangSmith, litellm, evaluation tools
2. Existující service-knowledgebase už je Python → přirozená integrace
3. Kotlin server zůstává pro své silné stránky (UI, security, DB)
4. LangGraph řeší VŠECHNY kritické problémy identifikované v sekci 2:
   - State management → StateGraph (P0 2.1)
   - Checkpointing → Native PostgreSQL persister (P0 2.3)
   - Multi-agent context → Shared state (P0 2.2)
   - Artifact tracking → State field (P0 2.4)
   - Provider routing → litellm (P1 2.5)
   - Tool isolation → Separate tool sets per node (P1 2.6, 2.9)
   - Error recovery → Retry with fallback nodes (P1 2.7)
   - Streaming → astream_events() (P1)
   - Human-in-the-loop → interrupt() (nativní)

### Immediate actions (nezávisle na variantě)

Tyto opravy by měly proběhnout HNED, protože jsou jednoduché a zvyšují stabilitu:

1. **Izolovat tool registry v GoalExecutor** – Vytvořit `executionToolRegistry` bez InternalAgentTools
2. **Fix `extractArtifacts()`** – Implementovat parsování artifacts z evidence
3. **Fix `maxAgentIterations`** – Single-shot agenti na 5, tool-loop na 30
4. **Remove dead code** – `OrchestratorState`, `NormalizedRequest`, `OrchestratorDecision`, `DecisionType`, `NextAction`
5. **Fix conversation history** – Injektovat do promptu

---

## Příloha A: Mapování schopností dle docs

Z `docs/` plyne že orchestrátor musí umět:

| Schopnost | Současný stav | Poznámka |
|-----------|--------------|----------|
| Search/Analysis | ✅ Funguje | Přes RAG + GraphDB |
| Code Change delegation | ✅ Funguje | Přes CodingTools → Aider/OpenHands/Junie |
| Multi-goal decomposition | ⚠️ Částečně | InterpreterAgent funguje, ale execution má bugy |
| Checkpoint/Resume | ❌ Nefunguje | Checkpoint se zapisuje s prázdnými daty |
| Human-in-the-loop | ⚠️ Částečně | askUser() tool existuje, ale checkpoint nedrží stav |
| Streaming progress | ⚠️ Částečně | onProgress callback existuje, ale ne real-time streaming |
| Learning/Preferences | ✅ Funguje | LearningTools + PreferenceTools |
| Vision processing | ✅ Funguje | V Qualifier agent (mimo orchestrátor) |
| Cost awareness | ⚠️ Částečně | CostTrackingService existuje ale nepropojený s orchestrátorem |
| Cross-validation | ⚠️ Částečně | NoGuessingDirectives v promptu, ale ne enforced |
| Execution memory | ✅ Funguje | ExecutionMemoryTools, ale memory leak risk |
| Graph DB traversal | ✅ Funguje | Přes KnowledgeStorageTools |
| Epic/Backlog management | ⚠️ Částečně | ProgramManager logika existuje, ale neotestováno |
| Email/Wiki/Bugtracker | ✅ Funguje | Read-only tools dostupné |

---

## Příloha B: LangGraph ekvivalenty Koog patterns

```python
# Koog: strategy("name") { ... }
# LangGraph:
graph = StateGraph(OrchestratorState)

# Koog: val nodeX by node<A, B> { ... }
# LangGraph:
def decompose(state: OrchestratorState) -> OrchestratorState:
    result = interpreter_chain.invoke(state.user_query)
    return {**state, "goals": result.goals}

graph.add_node("decompose", decompose)

# Koog: edge(nodeA forwardTo nodeB)
# LangGraph:
graph.add_edge("decompose", "select_goal")

# Koog: edge((nodeA forwardTo nodeB).onCondition { ... })
# LangGraph:
def route_after_select(state: OrchestratorState) -> str:
    if state.all_done:
        return "aggregate"
    return "execute_goal"

graph.add_conditional_edges("select_goal", route_after_select)

# Koog: nodeLLMRequestStructured<T>()
# LangGraph:
from langchain_core.output_parsers import PydanticOutputParser
chain = prompt | llm | PydanticOutputParser(pydantic_object=MultiGoalRequest)

# Koog: AIAgent.run(input)
# LangGraph:
app = graph.compile(checkpointer=PostgresSaver(...))
result = await app.ainvoke({"user_query": "..."}, config={"thread_id": "..."})

# Koog: executionMemory (ConcurrentHashMap hack)
# LangGraph: Nativní – state je automaticky checkpointovaný

# Koog: askUser() → USER_TASK → resume
# LangGraph:
from langgraph.prebuilt import interrupt
answer = interrupt({"question": "What do you prefer?"})
# Runtime automaticky pausne graph a resumne po odpovědi uživatele
```

---

## 7. Streaming & Live Process Visibility

### 7.1 Současný stav (co nefunguje)

**Aktuální streaming v Kotlin serveru:**

```
AgentOrchestratorRpcImpl.emitProgress(clientId, projectId, message, metadata)
  → MutableSharedFlow<ChatResponseDto> (buffer=100, DROP_OLDEST)
  → UI přes kRPC WebSocket (subscribeToChat)
```

**Problémy:**
1. **Pouze textové statusy** – "processing", "planning", "executing" – žádný detail
2. **Coding agenti (Aider/OpenHands/Junie) jsou black box** – volání `ICodingClient.execute(request)` je synchronní RPC, UI nevidí průběh
3. **Žádný přehled běžících procesů** – `subscribeToQueueStatus()` vrací jen `queueSize` + `runningTaskPreview`, ne co agent DĚLÁ
4. **Koog nepodporuje token streaming** – `AIAgent.run()` vrátí kompletní výsledek, žádné partial chunks

### 7.2 Cílový stav

UI chat by měl ukazovat:

```
┌─────────────────────────────────────────────────────────────────┐
│  Chat                                                            │
│                                                                  │
│  User: Refaktoruj authentication modul na JWT                    │
│                                                                  │
│  ┌── Orchestrator ──────────────────────────────────────────┐   │
│  │ ✅ Decomposition: 3 goals identified                     │   │
│  │ ✅ Goal 1: Research current auth implementation           │   │
│  │   └─ Found: SessionService.kt, AuthFilter.kt, 3 tests   │   │
│  │ 🔄 Goal 2: Implement JWT authentication                  │   │
│  │   ├─ Plan: 5 steps                                       │   │
│  │   └─ ┌── Aider (running) ──────────────────────────┐    │   │
│  │      │ Modifying AuthService.kt...                   │    │   │
│  │      │ Adding JwtTokenProvider.kt...                 │    │   │
│  │      │ ▌ (live output stream)                        │    │   │
│  │      └──────────────────────────────────────────────┘    │   │
│  │ ⏳ Goal 3: Update tests (waiting for Goal 2)             │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌── Running Processes ─────────────────────────────────────┐   │
│  │ 🟢 Orchestrator: Goal 2/3 – JWT implementation           │   │
│  │ 🟡 Aider: Modifying 2 files (AuthService.kt, JWT...)     │   │
│  │ 🔵 Background: Indexing 12 new Jira tickets               │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 7.3 Architektura streamingu v Python orchestrátoru

```
Python Orchestrator (LangGraph)
  │
  │  astream_events() → Server-Sent Events (SSE)
  │
  ▼
┌─────────────────────────────────────────────────────────────┐
│  SSE Event Stream (Python → Kotlin)                          │
│                                                               │
│  Event types:                                                 │
│  ├── orchestrator.phase_start   {phase: "decomposition"}     │
│  ├── orchestrator.phase_end     {phase: "decomposition", result: {...}} │
│  ├── orchestrator.goal_start    {goalId: "g1", title: "..."}│
│  ├── orchestrator.goal_progress {goalId: "g1", step: 2/5}   │
│  ├── orchestrator.goal_end      {goalId: "g1", success: true}│
│  ├── orchestrator.llm_chunk     {token: "partial text..."}   │
│  ├── orchestrator.tool_call     {tool: "search_kb", args: {...}} │
│  ├── orchestrator.tool_result   {tool: "search_kb", result: "..."} │
│  ├── orchestrator.approval_req  {action: "code_change", details: {...}} │
│  ├── coding.agent_start         {agent: "aider", task: "..."} │
│  ├── coding.agent_progress      {agent: "aider", output: "line..."} │
│  ├── coding.agent_end           {agent: "aider", success: true} │
│  └── orchestrator.final         {result: "...", artifacts: [...]} │
└─────────────────────────────────────────────────────────────┘
  │
  ▼
Kotlin Server (Spring Boot)
  │  Přijímá SSE stream z Python orchestrátoru
  │  Transformuje → ChatResponseDto
  │  Emituje → MutableSharedFlow
  │
  ▼
UI (kRPC WebSocket → subscribeToChat)
  │  Renderuje structured progress
  │  Zobrazuje live output z coding agentů
  │  Ukazuje Running Processes panel
```

### 7.4 Streaming z coding agentů

**Současný problém:** `ICodingClient.execute(request)` je request-response RPC. Výsledek přijde až po dokončení celého coding tasku (může trvat minuty).

**Řešení:** Python orchestrátor komunikuje přímo s coding servisy přes WebSocket/SSE:

```python
# Python orchestrátor – tool pro coding s live streamingem
async def execute_coding_with_stream(
    agent: str,  # "aider" | "openhands" | "junie"
    instructions: str,
    files: list[str],
) -> AsyncGenerator[CodingEvent, None]:
    """Volá coding service a streamuje průběh."""

    async with websockets.connect(f"ws://{agent_url}/ws/execute") as ws:
        await ws.send(json.dumps({
            "instructions": instructions,
            "files": files,
        }))

        async for message in ws:
            event = json.loads(message)
            # Emitovat jako SSE event pro Kotlin server
            yield CodingEvent(
                agent=agent,
                type=event["type"],  # "progress" | "file_changed" | "error" | "done"
                output=event.get("output", ""),
                file_path=event.get("file"),
            )
```

**Změny v coding microservicích (service-aider, service-coding-engine, service-junie):**

Tyto služby musí přidat WebSocket/SSE endpoint pro live streaming outputu:

```
Současný interface:
  POST /execute → CodingResult (synchronní, celý výsledek najednou)

Nový interface (přidaný):
  WS /ws/execute → stream CodingEvent[] (live output, file changes, progress)

  CodingEvent:
    type: "started" | "progress" | "file_read" | "file_write" | "command_run" | "error" | "done"
    output: string      # stdout/stderr line
    file_path: string?  # affected file
    timestamp: instant
```

### 7.5 Running Processes panel (UI)

**Nový kRPC endpoint:**

```kotlin
// IAgentOrchestratorService – rozšíření
fun subscribeToProcesses(clientId: String): Flow<ProcessStatusDto>

data class ProcessStatusDto(
    val processes: List<ProcessInfo>,
)

data class ProcessInfo(
    val id: String,
    val type: String,           // "orchestrator" | "coding" | "background" | "qualifier"
    val status: String,         // "running" | "waiting_approval" | "paused"
    val title: String,          // "JWT implementation – Goal 2/3"
    val agent: String?,         // "aider" | "openhands" | "junie" | null
    val progress: Float?,       // 0.0-1.0 if known
    val currentAction: String?, // "Modifying AuthService.kt..."
    val startedAt: Instant,
    val projectId: String?,
)
```

**Python orchestrátor emituje process updates jako součást SSE streamu.** Kotlin server agreguje procesy z:
1. Python orchestrátor (hlavní flow)
2. Coding agenti (sub-procesy)
3. BackgroundEngine (qualifier, background tasks)

---

## 8. Approval Flow – Risky Step Protection

### 8.1 Současný stav

Aktuální `UserInteractionTools` má:
- `askUser(question)` – BLOCKING, pausne celý agent
- `createUserTask(title)` – NON-BLOCKING, ale taky pausne agent (bug: oba volají `failAndEscalateToUserTask`)
- `requestCloudSpendApproval()` – vytvoří user task pro schválení

**Problémy:**
1. **Žádná approval policy** – Agent se sám rozhoduje kdy se zeptat, nemá pravidla co je "risky"
2. **askUser() zabije celý orchestrátor** – Po `failAndEscalateToUserTask()` se celý task přesune do USER_TASK stavu. Checkpoint je mrtvý (viz P0 2.3), takže po schválení se musí začít znova
3. **Background procesy nemají approval** – Qualifier/workflow agenti v BackgroundEngine nemají žádný mechanismus pro schválení riskantních kroků

### 8.2 Cílový stav

#### Pravidla pro approval (RiskyActionPolicy)

| Akce | Typ | Approval v chatu | Approval v background |
|------|-----|-----------------|----------------------|
| **Code change** (Aider/OpenHands/Junie) | RISKY | Chat dialog: "Chystám se změnit 3 soubory. Potvrdíte?" | Task state → WAITING_APPROVAL |
| **Jira ticket transition** | RISKY | Chat dialog | Task state → WAITING_APPROVAL |
| **Email send** | RISKY | Chat dialog | Task state → WAITING_APPROVAL |
| **Cloud model spend > $X** | RISKY | Chat dialog | Task state → WAITING_APPROVAL |
| **Delete operation** (graph node, file, etc.) | RISKY | Chat dialog | Task state → WAITING_APPROVAL |
| **RAG search** | SAFE | No approval | No approval |
| **Knowledge read** | SAFE | No approval | No approval |
| **Code analysis (Joern)** | SAFE | No approval | No approval |
| **Plan creation** | SAFE | No approval | No approval |

#### Approval v chatu (FOREGROUND tasks)

```
┌─────────────────────────────────────────────────────────────┐
│ Chat                                                         │
│                                                              │
│ Orchestrator: Plán vyžaduje změnu 3 souborů:                │
│ • AuthService.kt – přidat JWT validaci                      │
│ • SecurityConfig.kt – přepnout z session na JWT             │
│ • build.gradle.kts – přidat jwt dependency                  │
│                                                              │
│ Odhadovaný dopad: MEDIUM (existující testy pokrývají 60%)   │
│                                                              │
│ ┌─────────────┐  ┌──────────────┐  ┌──────────────────┐    │
│ │ ✅ Schválit  │  │ ❌ Odmítnout │  │ 📝 Upravit plán  │    │
│ └─────────────┘  └──────────────┘  └──────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

#### Approval v background procesech (BACKGROUND tasks)

```
Background task flow:
  1. Agent identifikuje risky action
  2. Task state: DISPATCHED_GPU → WAITING_APPROVAL
  3. UI notifikace: "Background task XY čeká na schválení"
  4. User v UI vidí detail + approve/reject
  5. Approve → Task state: WAITING_APPROVAL → DISPATCHED_GPU (resume)
  6. Reject → Task state: WAITING_APPROVAL → DONE (with rejection note)
```

### 8.3 Implementace v Python orchestrátoru (LangGraph)

```python
from langgraph.prebuilt import interrupt
from pydantic import BaseModel

class ApprovalRequest(BaseModel):
    action_type: str          # "code_change" | "jira_transition" | "email_send" | ...
    description: str          # Human-readable summary
    details: dict             # Structured data (files, ticket key, etc.)
    risk_level: str           # "LOW" | "MEDIUM" | "HIGH" | "CRITICAL"
    estimated_impact: str     # "3 files modified, 60% test coverage"
    reversible: bool          # Can this be undone?

class ApprovalResponse(BaseModel):
    approved: bool
    modification: str | None  # User can modify the plan
    reason: str | None        # Why rejected

# V orchestrátor grafu – node pro risky action
def execute_with_approval(state: OrchestratorState) -> OrchestratorState:
    action = state.pending_action

    if is_risky(action):
        # LangGraph interrupt = pausne graph, čeká na user input
        response: ApprovalResponse = interrupt(ApprovalRequest(
            action_type=action.type,
            description=f"Chystám se: {action.summary}",
            details=action.to_dict(),
            risk_level=assess_risk(action),
            estimated_impact=action.impact_summary,
            reversible=action.is_reversible,
        ))

        if not response.approved:
            return {**state, "action_rejected": True, "rejection_reason": response.reason}

        if response.modification:
            action = modify_action(action, response.modification)

    # Execute the action
    result = execute_action(action)
    return {**state, "action_result": result}

# Risk assessment
def is_risky(action) -> bool:
    RISKY_TYPES = {
        "code_change", "jira_transition", "email_send",
        "cloud_spend", "delete_operation", "git_push",
    }
    return action.type in RISKY_TYPES

def assess_risk(action) -> str:
    if action.type == "delete_operation":
        return "CRITICAL"
    if action.type == "code_change" and action.file_count > 5:
        return "HIGH"
    if action.type == "cloud_spend" and action.estimated_cost > 1.0:
        return "HIGH"
    return "MEDIUM"
```

### 8.4 Kotlin server – zprostředkování approval

```kotlin
// Nový task stav
enum class TaskStateEnum {
    // ... existující stavy ...
    WAITING_APPROVAL,  // ← NOVÝ: čeká na schválení uživatelem
}

// Nový RPC endpoint pro UI
suspend fun approveAction(
    taskId: String,
    approved: Boolean,
    modification: String? = null,
    reason: String? = null,
): ApprovalResultDto

// Flow:
// 1. Python orchestrátor → SSE event: "approval_required" s ApprovalRequest
// 2. Kotlin server → uloží do TaskDocument.pendingApproval
// 3. Kotlin server → změní stav na WAITING_APPROVAL
// 4. Kotlin server → emituje do UI streamu
// 5. UI zobrazí approval dialog
// 6. User approve/reject → Kotlin server → POST do Python orchestrátoru
// 7. Python orchestrátor → LangGraph resume s ApprovalResponse
// 8. Pokračuje execution
```

### 8.5 Rozdíl mezi chat a background approval

| Aspekt | Chat (FOREGROUND) | Background |
|--------|-------------------|------------|
| **UI** | Inline v chatu – tlačítka approve/reject | Notifikace + detail v task panelu |
| **Timeout** | Žádný – čeká dokud user neodpoví | Konfigurovatelný (např. 24h) |
| **Default** | Žádný default | Reject po timeout (bezpečné) |
| **Resume** | Okamžitý – graph pokračuje | Vrátí se do execution queue |
| **Stav tasku** | Zůstává DISPATCHED_GPU (jen graph je paused) | Přechází na WAITING_APPROVAL |

---

## 9. Tool Migration – kompletní plán přesunu do Pythonu

### 9.1 Princip: Všechny tools které orchestrátor potřebuje žijí v Pythonu

Současný stav je nepřehledný – tools jsou roztroušené přes Kotlin server, registrované v Koog, wrappované v InternalAgentTools. V nové architektuře Python orchestrátor vlastní VŠECHNY tools.

### 9.2 Kompletní seznam tools a kam se přesunou

#### Skupina A: Přímo v Pythonu (nativní implementace)

| Tool | Důvod | Implementace |
|------|-------|-------------|
| **ExecutionMemoryTools** | LangGraph state = nativní | `state["memory"]` – nepotřeba tool |
| **ChatHistoryTools** | LangGraph memory = nativní | `state["messages"]` – nepotřeba tool |
| **ValidationTools** | Pure logic, žádná závislost | Python funkce |
| **InternalAgentTools** | Sub-agenti = LangGraph sub-grafy | Sub-grafy, ne wrappery |
| **ProgramManager** | Pure logic (no LLM) | Python funkce |
| **CodingRules** | Prompt constants | Python constants |

#### Skupina B: Python tool s přímým přístupem k existujícím Python službám

| Tool | Cílová služba | Komunikace |
|------|--------------|------------|
| **KnowledgeStorageTools** | service-knowledgebase (Python!) | Přímý Python import NEBO HTTP localhost |
| **JoernTools** | service-joern | REST API (existující) |

#### Skupina C: Python tool volající Kotlin server přes REST

| Tool | Kotlin endpoint | Poznámka |
|------|----------------|----------|
| **BugTrackerReadTools** | `/api/internal/bugtracker/*` | Read-only, jednoduché |
| **IssueTrackerTool** | `/api/internal/bugtracker/write/*` | Write ops = RISKY → approval |
| **WikiReadTools** | `/api/internal/wiki/*` | Read-only |
| **EmailReadTools** | `/api/internal/email/*` | Read-only |
| **ProjectStructureTools** | `/api/internal/project/*` | Read-only |
| **SchedulerTools** | `/api/internal/scheduler/*` | Write = approval |
| **PreferenceTools** | `/api/internal/preferences/*` | Read/Write |
| **LearningTools** | `/api/internal/learning/*` | Write (safe – internal) |
| **LogSearchTools** | `/api/internal/logs/*` | Read-only |

#### Skupina D: Python tool volající coding microservisy přímo (s streamingem)

| Tool | Cílová služba | Komunikace |
|------|--------------|------------|
| **CodingTools.executeAider** | service-aider | WebSocket (live stream) |
| **CodingTools.executeOpenHands** | service-coding-engine | WebSocket (live stream) |
| **CodingTools.executeJunie** | service-junie | WebSocket (live stream) |

#### Skupina E: Python nativní náhrada (UserInteraction → LangGraph interrupt)

| Tool | LangGraph ekvivalent |
|------|---------------------|
| **UserInteractionTools.askUser** | `interrupt(question)` → nativní pause/resume |
| **UserInteractionTools.createUserTask** | `interrupt(task_request)` s metadata `{blocking: false}` |
| **UserInteractionTools.requestCloudSpendApproval** | `interrupt(approval_request)` s metadata `{type: "cost"}` |
| **CommunicationTools.sendEmail** | `interrupt(approval)` → po schválení REST call |

### 9.3 Kotlin server – nové internal REST API endpointy

Kotlin server musí vystavit REST API pro Python orchestrátor. Tyto endpointy NEEXISTUJÍ na public API – jsou interní (no security headers, internal network only).

```yaml
# Nový controller: InternalToolsController.kt

# Bug Tracker
GET    /api/internal/bugtracker/{clientId}/search?query=...&project=...
GET    /api/internal/bugtracker/{clientId}/issue/{key}
GET    /api/internal/bugtracker/{clientId}/issue/{key}/comments
POST   /api/internal/bugtracker/{clientId}/issue/{key}/comment    # WRITE
POST   /api/internal/bugtracker/{clientId}/issue/{key}/transition # WRITE

# Wiki
GET    /api/internal/wiki/{clientId}/search?query=...
GET    /api/internal/wiki/{clientId}/page/{id}
GET    /api/internal/wiki/{clientId}/spaces

# Email
GET    /api/internal/email/{clientId}/search?query=...
GET    /api/internal/email/{clientId}/message/{id}
GET    /api/internal/email/{clientId}/thread/{id}

# Project
GET    /api/internal/project/{projectId}/info
GET    /api/internal/project/{projectId}/git-path
GET    /api/internal/project/{projectId}/structure

# Scheduler
POST   /api/internal/scheduler/schedule    # WRITE
GET    /api/internal/scheduler/tasks

# Preferences
GET    /api/internal/preferences/{clientId}?scope=...&key=...
POST   /api/internal/preferences/{clientId}  # WRITE

# Learning
POST   /api/internal/learning/store
GET    /api/internal/learning/retrieve?category=...

# Logs
GET    /api/internal/logs/search?query=...&regex=...
GET    /api/internal/logs/tail?lines=...

# Chat (pro orchestrátor → zobrazení v UI)
POST   /api/internal/chat/{clientId}/{projectId}/emit   # Emit message to chat stream
POST   /api/internal/chat/{clientId}/{projectId}/approval  # Emit approval request

# Process tracking
POST   /api/internal/processes/update   # Update running process status
```

### 9.4 Python tool base class

```python
from abc import ABC, abstractmethod
from httpx import AsyncClient
from langchain_core.tools import tool

class KotlinServerClient:
    """Base HTTP client for Kotlin server internal API."""

    def __init__(self, base_url: str = "http://jervis-server:8080"):
        self.client = AsyncClient(base_url=base_url, timeout=30.0)

    async def get(self, path: str, **params) -> dict:
        resp = await self.client.get(f"/api/internal{path}", params=params)
        resp.raise_for_status()
        return resp.json()

    async def post(self, path: str, data: dict) -> dict:
        resp = await self.client.post(f"/api/internal{path}", json=data)
        resp.raise_for_status()
        return resp.json()

# Příklad tool implementace
kotlin_client = KotlinServerClient()

@tool
async def search_issues(
    client_id: str,
    query: str,
    project: str | None = None,
) -> str:
    """Search bug tracker issues (Jira/GitHub/GitLab)."""
    result = await kotlin_client.get(
        f"/bugtracker/{client_id}/search",
        query=query,
        project=project,
    )
    return json.dumps(result, indent=2)

@tool
async def transition_issue(
    client_id: str,
    issue_key: str,
    target_status: str,
) -> str:
    """Transition a bug tracker issue to new status. REQUIRES APPROVAL."""
    # Approval je handled na úrovni orchestrátor grafu (viz sekce 8)
    result = await kotlin_client.post(
        f"/bugtracker/{client_id}/issue/{issue_key}/transition",
        data={"targetStatus": target_status},
    )
    return json.dumps(result, indent=2)
```

---

## 10. Komunikační architektura

### 10.1 Celkový diagram

```
┌────────────────────────────────────────────────────────────────────────────┐
│                              UI (Desktop/Mobile)                            │
│                                                                             │
│  ┌─────────────┐  ┌──────────────────┐  ┌──────────────────────────────┐  │
│  │   Chat       │  │ Running Processes │  │ Approval Dialogs             │  │
│  │  (messages,  │  │ (live status of   │  │ (approve/reject risky       │  │
│  │   streaming, │  │  all agents and   │  │  actions inline in chat     │  │
│  │   progress)  │  │  background jobs) │  │  or in task panel)          │  │
│  └──────┬───────┘  └────────┬─────────┘  └──────────────┬───────────────┘  │
│         │ kRPC/WS           │ kRPC/WS                   │ kRPC/WS          │
└─────────┼───────────────────┼───────────────────────────┼──────────────────┘
          │                   │                           │
          ▼                   ▼                           ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                         Kotlin Server (Spring Boot)                         │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  AgentOrchestratorRpcImpl                                           │   │
│  │  • subscribeToChat() → Flow<ChatResponseDto>                        │   │
│  │  • subscribeToProcesses() → Flow<ProcessStatusDto>                  │   │
│  │  • sendMessage() → POST do Python orchestrátoru                     │   │
│  │  • approveAction() → POST do Python orchestrátoru                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  InternalToolsController (REST, internal-only)                      │   │
│  │  • /api/internal/bugtracker/*                                       │   │
│  │  • /api/internal/wiki/*                                             │   │
│  │  • /api/internal/email/*                                            │   │
│  │  • /api/internal/project/*                                          │   │
│  │  • /api/internal/chat/*/emit  (orchestrátor → UI)                   │   │
│  │  • /api/internal/processes/update                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────┐  ┌────────────────────────────────────────┐  │
│  │  BackgroundEngine        │  │  MongoDB, Weaviate, ArangoDB access    │  │
│  │  (qualifier loop,        │  │  (repositorie, embeddings, graph)      │  │
│  │   scheduler loop)        │  │                                        │  │
│  └─────────────────────────┘  └────────────────────────────────────────┘  │
└──────────────────────┬─────────────────────────────────────────────────────┘
                       │
          ┌────────────┼────────────────────────────────┐
          │            │ REST/SSE                        │
          ▼            ▼                                 ▼
┌──────────────────────────────┐    ┌──────────────────────────────────────┐
│  Python Orchestrator Service  │    │  Coding Microservices                 │
│  (FastAPI + LangGraph)        │    │                                       │
│                               │    │  ┌────────────┐  ┌────────────────┐  │
│  REST API:                    │    │  │ Aider       │  │ OpenHands      │  │
│  POST /orchestrate            │    │  │ (WS stream) │  │ (WS stream)    │  │
│  POST /resume                 │    │  └────────────┘  └────────────────┘  │
│  GET  /stream/{thread_id} SSE │    │                                       │
│  POST /approve/{thread_id}    │    │  ┌────────────┐                      │
│                               │    │  │ Junie       │                      │
│  Volá:                        │    │  │ (WS stream) │                      │
│  • Kotlin server tools (REST) │    │  └────────────┘                      │
│  • service-kb (Python, přímé) │    └──────────────────────────────────────┘
│  • Coding services (WebSocket)│
│  • LLM providers (litellm)    │
└──────────────────────────────┘
```

### 10.2 Sekvence: User message → streamed response s approval

```
UI                 Kotlin Server         Python Orchestrator    Aider Service
 │                      │                       │                    │
 │──sendMessage()──────>│                       │                    │
 │                      │──POST /orchestrate───>│                    │
 │                      │                       │                    │
 │                      │<─SSE: phase_start─────│ (decomposition)    │
 │<─subscribeToChat()───│                       │                    │
 │  "Analyzing request" │                       │                    │
 │                      │<─SSE: goal_start──────│ (goal 1: research) │
 │<─"Researching..."────│                       │                    │
 │                      │<─SSE: tool_call───────│ (search_kb)        │
 │                      │                       │──GET /internal/kb──>│
 │                      │                       │<─results────────────│
 │                      │<─SSE: tool_result─────│                    │
 │                      │                       │                    │
 │                      │<─SSE: approval_req────│ (code change!)     │
 │<─approval dialog─────│                       │                    │
 │                      │                       │ [PAUSED - interrupt]│
 │──approveAction(yes)─>│                       │                    │
 │                      │──POST /approve────────>│                    │
 │                      │                       │ [RESUMED]           │
 │                      │                       │──WS: execute───────>│
 │                      │<─SSE: coding.progress─│<─WS: progress──────│
 │<─"Aider: editing..." │                       │<─WS: progress──────│
 │<─"Aider: editing..." │                       │<─WS: done──────────│
 │                      │<─SSE: coding.done─────│                    │
 │                      │<─SSE: goal_end────────│                    │
 │                      │<─SSE: final───────────│                    │
 │<─final response──────│                       │                    │
```

### 10.3 Jak Python orchestrátor komunikuje s Kotlin serverem

**Dva kanály:**

1. **Python → Kotlin (tool calls):** REST HTTP. Python volá `/api/internal/*` endpointy pro čtení dat (bugtracker, wiki, email, project info) a zápis (chat emit, process update).

2. **Kotlin → Python (orchestration requests):** REST HTTP. Kotlin server volá Python `/orchestrate` endpoint když přijde user message. Python odpoví SSE streamem.

**Proč ne gRPC?** REST je jednodušší, debugging přes curl, žádná code-gen závislost. Performance difference je zanedbatelná pro tyto use-case (orchestrátor volá tools řádově 10-50x za request, ne tisíce).

### 10.4 K8s deployment

```yaml
# k8s/app_orchestrator.yaml (nový)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jervis-orchestrator
  namespace: jervis
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: orchestrator
          image: ghcr.io/jandamek/jervis-orchestrator:latest
          ports:
            - containerPort: 8090
          env:
            - name: KOTLIN_SERVER_URL
              value: "http://jervis-server:8080"
            - name: KNOWLEDGEBASE_URL
              value: "http://jervis-knowledgebase:8000"
            - name: AIDER_WS_URL
              value: "ws://jervis-aider:8080"
            - name: CODING_ENGINE_WS_URL
              value: "ws://jervis-coding-engine:8080"
            - name: JUNIE_WS_URL
              value: "ws://jervis-junie:8080"
            - name: OLLAMA_URL
              value: "http://192.168.100.117:11434"
            - name: POSTGRES_URL  # Pro LangGraph checkpointer
              value: "postgresql://jervis:pass@postgres:5432/orchestrator"
          resources:
            requests:
              memory: "512Mi"
              cpu: "500m"
            limits:
              memory: "2Gi"
              cpu: "2000m"
```

---

## 11. Model Strategy – P40 GPU, VRAM budget, hybridní routing

### 11.1 Stav hardware

| Parametr | Hodnota |
|----------|---------|
| GPU | NVIDIA Tesla P40 |
| VRAM | 24 GB GDDR5 |
| Bandwidth | 346 GB/s (3x pomalejší než RTX 4090) |
| FP16 | ~12 TFLOPS (žádné Tensor Cores) |
| INT8 | 47 TOPS |

### 11.2 Co běží na P40 současně

| Proces | Model | VRAM | Poznámka |
|--------|-------|------|----------|
| **Orchestrator agent** | qwen3-coder-tool:30b Q4_K_M | ~18.6 GB | Hlavní agent |
| **KB ingest (qualifier)** | qwen3-coder-tool:30b (qualifier instance) | ~18.6 GB | Běží na OLLAMA_QUALIFIER (port 11435) |
| **Embedding** | qwen3-embedding:8b | ~5 GB | Běží na OLLAMA_EMBEDDING (port 11436) |

**PROBLÉM:** 18.6 + 18.6 + 5 = **42.2 GB** – to se nevejde do 24 GB. Současný setup funguje díky tomu, že Ollama swapuje modely (unload/load). Ale to znamená:
- Přepnutí mezi qualifier a orchestrátorem trvá **10-30 sekund** (model load)
- Keep-alive `1h` = model zůstane v VRAM hodinu po posledním požadavku
- Dvě Ollama instance (primary + qualifier) musí čekat dokud ta druhá uvolní VRAM

### 11.3 VRAM budget – realistické scénáře

#### Scénář A: Qwen3-Coder-30B-A3B (MoE) – AKTUÁLNÍ

```
Qwen3-Coder-30B-A3B (Q4_K_M):    ~18.6 GB model weights
KV cache 48k (FP16):               ~4.5 GB
CUDA overhead:                     ~1.0 GB
────────────────────────────────────────────
CELKEM:                            ~24.1 GB  ← NA HRANĚ, spilluje do RAM
```

**Speed:** ~5-12 tok/s na P40 (bandwidth limited)
**Aktivní parametry:** pouze 3.3B z 30B (MoE – 128 expertů, 8 aktivních)
**Kvalita:** Excelentní pro coding (69.6% SWE-Bench), solidní reasoning

**Optimalizace KV cache:**
- `--ctk q8_0 --ctv q4_0` → KV cache 48k = ~1.7 GB místo 4.5 GB
- S touto optimalizací: 18.6 + 1.7 + 1.0 = **21.3 GB** – vejde se s 2.7 GB rezervou

#### Scénář B: Menší model pro orchestrátor, velký jen pro coding

```
Orchestrator: Qwen3-14B (Q4_K_M)  ~8.5 GB
+ KV cache 48k:                    ~3.0 GB
+ CUDA overhead:                   ~0.5 GB
────────────────────────────────────────────
CELKEM orchestrator:               ~12.0 GB

Zbývá pro embedding:               ~5.0 GB  → qwen3-embedding:8b se vejde
Zbývá pro coding (když neběží):    ~12.0 GB → nebude sdílet
```

**Pro:** Orchestrátor a embedding běží SOUČASNĚ. Coding model se loadne jen když je potřeba.
**Proti:** Qwen3-14B je výrazně slabší než 30B pro coding/reasoning.

#### Scénář C: Qwen3-30B-A3B pro vše, ale optimalizovaný (DOPORUČENÝ)

```
Qwen3-Coder-30B-A3B (Q4_K_M):     ~18.6 GB
+ KV cache 32k (Q8_0/Q4_0):        ~1.1 GB
+ CUDA overhead:                    ~0.5 GB
────────────────────────────────────────────
CELKEM:                             ~20.2 GB

Zbývá v VRAM:                       ~3.8 GB
```

**Klíč:** Snížit kontext na **32k** s kvantizovaným KV cache. Pro orchestrátor je 32k dostatečné – většina promptů je pod 16k. Při větších úlohách eskalovat na cloud model.

### 11.4 Doporučená konfigurace pro P40

```yaml
# Jediná Ollama instance (sloučit primary + qualifier)
ollama:
  models:
    orchestrator:
      model: qwen3-coder-tool-32k:30b   # 32k default
      quantization: Q4_K_M
      kv_cache: q8_0/q4_0               # Kvantizovaný KV cache
      keep_alive: 30m                    # Kratší keep-alive
      gpu_layers: all                    # Vše na GPU

    qualifier:                           # SDÍLÍ STEJNÝ MODEL
      model: qwen3-coder-tool-8k:30b    # Malý kontext pro klasifikaci
      keep_alive: 5m                     # Rychle uvolnit

    embedding:
      model: qwen3-embedding:8b
      gpu_layers: 0                      # CPU ONLY – uvolnit VRAM pro hlavní model
      concurrent_requests: 6

  # Alternativa: embedding na CPU, 30B model drží GPU
  num_parallel: 1                        # Jen 1 concurrent request
```

**Klíčové změny:**
1. **Sloučit 2 Ollama instance do 1** – qualifier a orchestrátor sdílejí stejný model, jen jiný context size
2. **Embedding na CPU** – qwen3-embedding:8b je dostatečně rychlý na CPU, uvolní ~5 GB VRAM
3. **KV cache kvantizace** – Q8_0 keys + Q4_0 values = 3x menší KV cache
4. **Kratší keep-alive** – 30 minut místo 1 hodiny, rychlejší model swap

---

## 12. Hybridní model routing – lokální + placené modely

### 12.1 Princip: "Přemýšlení" na cloud, "práce" lokálně

Agent pro většinu práce používá lokální Qwen3-30B. Ale když:
- Potřebuje **složitý reasoning** (architektura, complex debug)
- Lokální model **selže** 2x na stejném tasku
- Úloha vyžaduje **velký context** (> 32k tokenů)
- Uživatel explicitně požaduje **vyšší kvalitu**

...eskaluje na placený cloud model.

### 12.2 Model tiers

| Tier | Model | Provider | Použití | Cena (approx) |
|------|-------|----------|---------|----------------|
| **LOCAL_FAST** | qwen3-coder-30b-a3b Q4_K_M (8k) | Ollama | Klasifikace, jednoduché queries | $0 |
| **LOCAL_STANDARD** | qwen3-coder-30b-a3b Q4_K_M (32k) | Ollama | Orchestrace, plánování, research | $0 |
| **LOCAL_LARGE** | qwen3-coder-30b-a3b Q4_K_M (48k) | Ollama | Velké dokumenty (spill to RAM) | $0 |
| **CLOUD_REASONING** | Claude Sonnet 4.5 | Anthropic | Složitý reasoning, architectural decisions | ~$3/1M in + $15/1M out |
| **CLOUD_CODING** | Claude Sonnet 4.5 | Anthropic | Coding agent (Claude Code SDK) | ~$3/1M in + $15/1M out |
| **CLOUD_PREMIUM** | Claude Opus 4 | Anthropic | Kritické rozhodnutí, review | ~$15/1M in + $75/1M out |

### 12.3 Kdy eskalovat na cloud (EscalationPolicy)

```python
from pydantic import BaseModel
from enum import Enum

class ModelTier(str, Enum):
    LOCAL_FAST = "local_fast"
    LOCAL_STANDARD = "local_standard"
    LOCAL_LARGE = "local_large"
    CLOUD_REASONING = "cloud_reasoning"
    CLOUD_CODING = "cloud_coding"
    CLOUD_PREMIUM = "cloud_premium"

class EscalationPolicy:
    """Rozhoduje kdy eskalovat na placený model."""

    def select_tier(
        self,
        task_type: str,
        complexity: str,        # "simple" | "medium" | "complex" | "critical"
        context_tokens: int,
        local_failures: int,    # Kolikrát lokální model selhal na tomto tasku
        user_preference: str,   # "economy" | "balanced" | "quality"
    ) -> ModelTier:

        # Pravidlo 1: Uživatel explicitně chce kvalitu
        if user_preference == "quality":
            return ModelTier.CLOUD_REASONING

        # Pravidlo 2: Lokální model selhal 2x → eskalovat
        if local_failures >= 2:
            return ModelTier.CLOUD_REASONING

        # Pravidlo 3: Velký kontext → cloud (má 200k+ context)
        if context_tokens > 32_000:
            if user_preference == "economy":
                return ModelTier.LOCAL_LARGE  # Spill to RAM, ale zadarmo
            return ModelTier.CLOUD_REASONING

        # Pravidlo 4: Coding task → cloud coding agent
        if task_type == "code_change" and complexity in ("complex", "critical"):
            return ModelTier.CLOUD_CODING  # Claude Code SDK

        # Pravidlo 5: Architectural decision → cloud reasoning
        if task_type in ("architecture", "design_review") and complexity != "simple":
            return ModelTier.CLOUD_REASONING

        # Pravidlo 6: Kritické rozhodnutí → premium
        if complexity == "critical":
            return ModelTier.CLOUD_PREMIUM

        # Default: lokální model
        if context_tokens > 16_000:
            return ModelTier.LOCAL_STANDARD
        return ModelTier.LOCAL_FAST
```

### 12.4 litellm integrace

```python
import litellm

# Konfigurace providerů
TIER_CONFIG = {
    ModelTier.LOCAL_FAST: {
        "model": "ollama/qwen3-coder-tool-8k:30b",
        "api_base": "http://192.168.100.117:11434",
    },
    ModelTier.LOCAL_STANDARD: {
        "model": "ollama/qwen3-coder-tool-32k:30b",
        "api_base": "http://192.168.100.117:11434",
    },
    ModelTier.LOCAL_LARGE: {
        "model": "ollama/qwen3-coder-tool-48k:30b",
        "api_base": "http://192.168.100.117:11434",
    },
    ModelTier.CLOUD_REASONING: {
        "model": "anthropic/claude-sonnet-4-5-20250929",
    },
    ModelTier.CLOUD_CODING: {
        "model": "anthropic/claude-sonnet-4-5-20250929",
        # Pro coding: Claude Code SDK, ne přímé LLM volání
    },
    ModelTier.CLOUD_PREMIUM: {
        "model": "anthropic/claude-opus-4-6",
    },
}

async def call_llm(tier: ModelTier, messages: list, tools: list = None):
    """Unified LLM call přes litellm."""
    config = TIER_CONFIG[tier]

    response = await litellm.acompletion(
        model=config["model"],
        messages=messages,
        tools=tools,
        api_base=config.get("api_base"),
        stream=True,  # Vždy streamovat
    )

    return response
```

### 12.5 Cost tracking a budget

```python
class CostTracker:
    """Per-task a per-client cost tracking."""

    async def track_call(self, client_id: str, tier: ModelTier, usage: dict):
        cost = litellm.completion_cost(
            model=TIER_CONFIG[tier]["model"],
            prompt_tokens=usage["prompt_tokens"],
            completion_tokens=usage["completion_tokens"],
        )

        await self.db.save({
            "client_id": client_id,
            "tier": tier,
            "model": TIER_CONFIG[tier]["model"],
            "prompt_tokens": usage["prompt_tokens"],
            "completion_tokens": usage["completion_tokens"],
            "cost_usd": cost,
            "timestamp": datetime.utcnow(),
        })

    async def get_budget_remaining(self, client_id: str) -> float:
        """Kolik z měsíčního budgetu zbývá."""
        spent = await self.db.sum_cost_this_month(client_id)
        budget = await self.get_client_budget(client_id)
        return budget - spent

    async def check_budget(self, client_id: str, estimated_cost: float) -> bool:
        """Lze provést tuto operaci v rámci budgetu?"""
        remaining = await self.get_budget_remaining(client_id)
        if estimated_cost > remaining:
            return False  # → approval request
        return True
```

---

## 13. Unified Agent Interface – Claude Code jako 4. coding agent

### 13.1 Současní coding agenti

| Agent | Typ | Model | Silné stránky |
|-------|-----|-------|---------------|
| **Aider** | Open-source CLI | Lokální Qwen3 / Cloud Claude | Rychlý, 1-3 soubory |
| **OpenHands** | Open-source platform | Lokální Qwen3 / Cloud Claude | Komplexní refactoring |
| **Junie** | JetBrains (placený) | Cloud (vždy placený) | Premium kvalita |
| **Claude Code** 🆕 | Anthropic CLI/SDK | Cloud (vždy Anthropic) | Nejlepší reasoning, autonomní |

### 13.2 Přidání Claude Code jako service-claude-code

```
backend/
  service-claude-code/        ← NOVÝ Python microservice
    app/
      main.py                 # FastAPI + WebSocket
      agent.py                # Claude Agent SDK wrapper
      streaming.py            # Live output streaming
    Dockerfile
    requirements.txt
```

#### Implementace

```python
# service-claude-code/app/agent.py
from claude_agent_sdk import query, ClaudeAgentOptions

async def execute_coding_task(
    instructions: str,
    project_path: str,
    files: list[str] = None,
    verify_command: str = None,
) -> AsyncGenerator[dict, None]:
    """Execute coding task via Claude Code SDK with live streaming."""

    options = ClaudeAgentOptions(
        model="sonnet",  # claude-sonnet-4-5 (default, cost-effective)
        permission_mode="bypassPermissions",  # Headless mode
        allowed_tools=[
            "Read", "Write", "Edit", "Bash",
            "Grep", "Glob", "Task",  # Sub-agenti
        ],
        cwd=project_path,
        system_prompt=f"""You are working on the Jervis project.
        Focus on: {instructions}
        Files to modify: {', '.join(files or ['auto-detect'])}
        After changes, verify with: {verify_command or 'N/A'}
        """,
        max_turns=50,
    )

    # Stream events z Claude Code
    async for event in query(prompt=instructions, options=options):
        if event.type == "assistant":
            yield {
                "type": "progress",
                "output": event.content,
                "agent": "claude-code",
            }
        elif event.type == "tool_use":
            yield {
                "type": "tool_call",
                "tool": event.tool_name,
                "input": event.tool_input,
                "agent": "claude-code",
            }
        elif event.type == "tool_result":
            yield {
                "type": "tool_result",
                "output": str(event.content)[:500],
                "agent": "claude-code",
            }

    # Verifikace
    if verify_command:
        yield {"type": "verification", "command": verify_command}
        # Run verify...

    yield {"type": "done", "success": True, "agent": "claude-code"}
```

#### WebSocket endpoint pro streaming

```python
# service-claude-code/app/main.py
from fastapi import FastAPI, WebSocket
from .agent import execute_coding_task

app = FastAPI()

@app.websocket("/ws/execute")
async def ws_execute(ws: WebSocket):
    await ws.accept()
    request = await ws.receive_json()

    async for event in execute_coding_task(
        instructions=request["instructions"],
        project_path=request["project_path"],
        files=request.get("files"),
        verify_command=request.get("verify_command"),
    ):
        await ws.send_json(event)

    await ws.close()
```

### 13.3 Unified CodingAgent interface v Python orchestrátoru

```python
from abc import ABC, abstractmethod
from typing import AsyncGenerator

class CodingAgent(ABC):
    """Společný interface pro všechny coding agenty."""

    @abstractmethod
    async def execute(
        self,
        instructions: str,
        project_path: str,
        files: list[str] = None,
        verify_command: str = None,
    ) -> AsyncGenerator[CodingEvent, None]:
        """Execute coding task s live streamingem."""
        ...

    @property
    @abstractmethod
    def name(self) -> str: ...

    @property
    @abstractmethod
    def cost_tier(self) -> str: ...  # "free" | "paid" | "premium"

class AiderAgent(CodingAgent):
    """Aider – rychlý, 1-3 soubory, lokální model."""
    name = "aider"
    cost_tier = "free"  # Když běží s lokálním modelem

    async def execute(self, instructions, project_path, files=None, verify_command=None):
        async with websockets.connect(f"ws://{AIDER_URL}/ws/execute") as ws:
            await ws.send(json.dumps({...}))
            async for msg in ws:
                yield CodingEvent.from_json(msg)

class OpenHandsAgent(CodingAgent):
    """OpenHands – komplexní refactoring, autonomní."""
    name = "openhands"
    cost_tier = "free"

class JunieAgent(CodingAgent):
    """Junie – JetBrains premium, vždy placený."""
    name = "junie"
    cost_tier = "premium"

class ClaudeCodeAgent(CodingAgent):
    """Claude Code – Anthropic, nejlepší reasoning."""
    name = "claude-code"
    cost_tier = "paid"

    async def execute(self, instructions, project_path, files=None, verify_command=None):
        async with websockets.connect(f"ws://{CLAUDE_CODE_URL}/ws/execute") as ws:
            await ws.send(json.dumps({
                "instructions": instructions,
                "project_path": project_path,
                "files": files,
                "verify_command": verify_command,
            }))
            async for msg in ws:
                yield CodingEvent.from_json(msg)
```

### 13.4 Smart Agent Selector

```python
class SmartAgentSelector:
    """Automatický výběr coding agenta na základě úlohy."""

    def select(
        self,
        task_complexity: str,     # "trivial" | "simple" | "complex" | "critical"
        file_count: int,
        budget_remaining: float,  # USD
        user_preference: str,     # "economy" | "balanced" | "quality"
        previous_failures: dict,  # {agent_name: failure_count}
    ) -> list[CodingAgent]:
        """Vrátí seřazený seznam agentů k vyzkoušení (failover chain)."""

        if user_preference == "quality" or task_complexity == "critical":
            return [ClaudeCodeAgent(), JunieAgent(), OpenHandsAgent()]

        if task_complexity == "trivial" or (file_count <= 3):
            return [AiderAgent(), OpenHandsAgent(), ClaudeCodeAgent()]

        if task_complexity == "complex":
            if budget_remaining > 1.0:
                return [OpenHandsAgent(), ClaudeCodeAgent(), JunieAgent()]
            else:
                return [OpenHandsAgent(), AiderAgent()]

        # Default: balanced
        return [AiderAgent(), OpenHandsAgent(), ClaudeCodeAgent()]
```

### 13.5 K8s deployment – service-claude-code

```yaml
# k8s/app_claude_code.yaml (nový)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jervis-claude-code
  namespace: jervis
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: claude-code
          image: ghcr.io/jandamek/jervis-claude-code:latest
          ports:
            - containerPort: 3400
          env:
            - name: ANTHROPIC_API_KEY
              valueFrom:
                secretKeyRef:
                  name: jervis-secrets
                  key: anthropic-api-key
            - name: PROJECT_ROOT
              value: "/workspace"  # Mounted git repo
          volumeMounts:
            - name: workspace
              mountPath: /workspace
          resources:
            requests:
              memory: "256Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
      volumes:
        - name: workspace
          persistentVolumeClaim:
            claimName: jervis-workspace
```

### 13.6 Rozšíření CodingTools – orchestrátor routing

V Python orchestrátoru bude coding tool vypadat takto:

```python
@tool
async def execute_code_change(
    instructions: str,
    files: list[str] = None,
    verify_command: str = None,
    preferred_agent: str = "auto",  # "auto" | "aider" | "openhands" | "junie" | "claude-code"
) -> str:
    """Execute code change with automatic agent selection and failover.

    IMPORTANT: This is a RISKY action that requires user approval.
    The orchestrator will pause and ask for approval before executing.

    Args:
        instructions: What code changes to make
        files: Specific files to modify (helps select agent)
        verify_command: Command to verify changes (e.g., 'pytest')
        preferred_agent: Which agent to use, or 'auto' for smart selection
    """
    # 1. Select agent(s)
    if preferred_agent == "auto":
        agents = smart_selector.select(
            task_complexity=assess_complexity(instructions),
            file_count=len(files or []),
            budget_remaining=await cost_tracker.get_budget_remaining(client_id),
            user_preference=await preferences.get("coding_preference", "balanced"),
            previous_failures={},
        )
    else:
        agents = [AGENT_MAP[preferred_agent]]

    # 2. Execute with failover
    for agent in agents:
        try:
            result_chunks = []
            async for event in agent.execute(instructions, project_path, files, verify_command):
                # Stream event to UI
                await emit_coding_event(event)
                result_chunks.append(event)

            final = result_chunks[-1]
            if final.get("success"):
                return format_success(agent.name, result_chunks)
        except Exception as e:
            logger.warning(f"Agent {agent.name} failed: {e}, trying next...")
            continue

    return "All coding agents failed. Manual intervention required."
```

---

## 14. Agent Settings UI & K8s Dynamic Scaling

### 14.1 Nová kategorie v Settings: AGENTS

Aktuální `SettingsCategory` enum má 5 položek (GENERAL, CLIENTS, PROJECTS, CONNECTIONS, LOGS). Přidáme **AGENTS** – konfiguraci orchestrátoru, coding agentů, limitů a model routingu.

```kotlin
enum class SettingsCategory(...) {
    GENERAL("Obecné", "⚙️", "..."),
    CLIENTS("Klienti", "🏢", "..."),
    PROJECTS("Projekty", "📁", "..."),
    CONNECTIONS("Připojení", "🔌", "..."),
    AGENTS("Agenti", "🤖", "Konfigurace AI agentů, modelů a limitů."),  // ← NOVÝ
    LOGS("Logy", "📜", "..."),
}
```

### 14.2 Agent Settings – UI layout

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Settings > Agenti                                                        │
│                                                                          │
│ ┌── Coding Agents ──────────────────────────────────────────────────┐   │
│ │                                                                    │   │
│ │  Agent           Enabled  Max Instances  Status      Model        │   │
│ │  ─────────────   ──────   ─────────────  ──────      ─────        │   │
│ │  🟢 Aider        [✓]     [ 2 ▾]         Running (1)  Qwen3-30B   │   │
│ │  🟢 OpenHands    [✓]     [ 1 ▾]         Idle         Qwen3-30B   │   │
│ │  🟡 Junie        [✓]     [ 1 ▾]         Idle         Claude 3.5  │   │
│ │  🔵 Claude Code  [✓]     [ 1 ▾]         Idle         Sonnet 4.5  │   │
│ │                                                                    │   │
│ │  Celkový limit concurrent agentů: [ 3 ▾]                         │   │
│ │  ⚠️ Pozor: Aider + OpenHands sdílejí Ollama GPU (max 1 naráz)    │   │
│ │                                                                    │   │
│ └────────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│ ┌── Model Routing ──────────────────────────────────────────────────┐   │
│ │                                                                    │   │
│ │  Preference:  ○ Economy (jen lokální)                             │   │
│ │               ● Balanced (lokální + cloud pro složité)            │   │
│ │               ○ Quality (cloud preferovaný)                       │   │
│ │                                                                    │   │
│ │  Lokální model: qwen3-coder-tool:30b  (P40 24GB)    [Change ▾]   │   │
│ │  Cloud model:   Claude Sonnet 4.5     (Anthropic)   [Change ▾]   │   │
│ │  Premium model: Claude Opus 4         (Anthropic)   [Change ▾]   │   │
│ │                                                                    │   │
│ │  Max context (lokální): [ 32k ▾]  (4k│8k│16k│32k│48k│64k)       │   │
│ │  Eskalovat na cloud po: [ 2 ▾] selháních lokálního modelu        │   │
│ │                                                                    │   │
│ └────────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│ ┌── Budget & Cost ──────────────────────────────────────────────────┐   │
│ │                                                                    │   │
│ │  Měsíční budget:     [ $50.00    ]                                │   │
│ │  Spotřebováno:       $12.35 (24.7%)  ████░░░░░░                  │   │
│ │  Zbývá:              $37.65                                       │   │
│ │                                                                    │   │
│ │  Schvalovat nad:     [ $1.00  ] za jednotlivý request             │   │
│ │  Auto-reject nad:    [ $5.00  ] (background tasks)                │   │
│ │                                                                    │   │
│ │  Historie nákladů:   [Zobrazit ▾]                                │   │
│ │                                                                    │   │
│ └────────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│ ┌── Risky Actions ──────────────────────────────────────────────────┐   │
│ │                                                                    │   │
│ │  Vyžadovat schválení pro:                                         │   │
│ │  [✓] Code changes (Aider/OpenHands/Junie/Claude Code)            │   │
│ │  [✓] Jira ticket transitions                                     │   │
│ │  [✓] Odesílání emailů                                            │   │
│ │  [✓] Cloud model spend nad budget threshold                      │   │
│ │  [✓] Delete operace (soubory, graph nodes)                       │   │
│ │  [ ] Git push (aktuálně blokovaný)                               │   │
│ │  [ ] Scheduler vytvoření úloh                                    │   │
│ │                                                                    │   │
│ │  Background tasks timeout: [ 24h ▾] (auto-reject po timeout)     │   │
│ │                                                                    │   │
│ └────────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│ ┌── Orchestrator ───────────────────────────────────────────────────┐   │
│ │                                                                    │   │
│ │  Max goals per request:     [ 5 ▾]                                │   │
│ │  Max iterations per goal:   [ 30 ▾]                               │   │
│ │  Review po execution:       [✓] Pro complex tasks                 │   │
│ │  Conversation history:      [ 20 ▾] zpráv v kontextu             │   │
│ │  Language:                  ● Auto-detect  ○ Čeština  ○ English   │   │
│ │                                                                    │   │
│ └────────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│                                    [ Uložit ]  [ Zrušit ]               │
└─────────────────────────────────────────────────────────────────────────┘
```

### 14.3 Data model – AgentConfigDto

```kotlin
// shared/common-dto – nový soubor AgentConfigDto.kt

@Serializable
data class AgentConfigDto(
    // Coding agents
    val codingAgents: List<CodingAgentConfigDto>,
    val maxConcurrentCodingAgents: Int = 3,

    // Model routing
    val modelPreference: ModelPreference = ModelPreference.BALANCED,
    val localModel: String = "qwen3-coder-tool:30b",
    val cloudModel: String = "claude-sonnet-4-5-20250929",
    val premiumModel: String = "claude-opus-4-6",
    val maxLocalContext: Int = 32,  // v tisícich (k)
    val escalateAfterFailures: Int = 2,

    // Budget
    val monthlyBudgetUsd: Double = 50.0,
    val approvalThresholdUsd: Double = 1.0,
    val autoRejectThresholdUsd: Double = 5.0,

    // Risky actions
    val riskyActions: RiskyActionsConfig = RiskyActionsConfig(),

    // Orchestrator
    val maxGoalsPerRequest: Int = 5,
    val maxIterationsPerGoal: Int = 30,
    val reviewComplexTasks: Boolean = true,
    val conversationHistorySize: Int = 20,
    val language: LanguagePreference = LanguagePreference.AUTO,
)

@Serializable
data class CodingAgentConfigDto(
    val name: String,           // "aider" | "openhands" | "junie" | "claude-code"
    val enabled: Boolean = true,
    val maxInstances: Int = 1,
    val defaultModel: String?,  // Override pro tento agent
    val paidModel: String?,
    val priority: Int = 0,      // Nižší = vyšší priorita v auto-select
)

@Serializable
data class RiskyActionsConfig(
    val codeChanges: Boolean = true,
    val jiraTransitions: Boolean = true,
    val emailSend: Boolean = true,
    val cloudSpend: Boolean = true,
    val deleteOperations: Boolean = true,
    val gitPush: Boolean = false,
    val schedulerCreate: Boolean = false,
    val backgroundTimeout: String = "24h",
)

@Serializable
enum class ModelPreference { ECONOMY, BALANCED, QUALITY }

@Serializable
enum class LanguagePreference { AUTO, CS, EN }
```

### 14.4 Scope – kde se ukládá konfigurace

Využije se existující `PreferenceTools` pattern (scope hierarchy: GLOBAL → CLIENT → PROJECT):

| Setting | Scope | Důvod |
|---------|-------|-------|
| `codingAgents[*].enabled` | **GLOBAL** | Infra – agent buď běží nebo ne |
| `codingAgents[*].maxInstances` | **GLOBAL** | Infra – K8s limity |
| `maxConcurrentCodingAgents` | **GLOBAL** | Infra – GPU/CPU limity |
| `modelPreference` | **CLIENT** | Různí klienti mají různý budget |
| `localModel` | **GLOBAL** | Závisí na HW |
| `cloudModel` | **CLIENT** | Klient může preferovat jiný provider |
| `monthlyBudgetUsd` | **CLIENT** | Per-client billing |
| `approvalThresholdUsd` | **CLIENT** | Per-client risk tolerance |
| `riskyActions.*` | **PROJECT** | Různé projekty mají různé požadavky |
| `maxGoalsPerRequest` | **GLOBAL** | Systémový limit |
| `maxIterationsPerGoal` | **PROJECT** | Velké projekty mohou potřebovat víc |
| `conversationHistorySize` | **GLOBAL** | Závisí na context window |
| `language` | **CLIENT** | Per-klient jazykové preference |

### 14.5 K8s dynamic scaling – Deployments vs Jobs

#### Současný stav

Všechny coding služby běží jako **Deployment** s `replicas: 1`, stále čekají na práci. To plýtvá resources když nejsou potřeba.

#### Dva přístupy

##### Přístup A: Deployment + HPA (Horizontal Pod Autoscaler)

```yaml
# k8s/app_aider.yaml – rozšíření o HPA
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: jervis-aider-hpa
  namespace: jervis
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: jervis-aider
  minReplicas: 0           # Scale-to-zero když nic neběží
  maxReplicas: 3            # Max dle AgentConfigDto.maxInstances
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300   # 5 minut po idle → scale down
      policies:
        - type: Pods
          value: 1
          periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 0     # Okamžitý scale up
      policies:
        - type: Pods
          value: 2
          periodSeconds: 60
  metrics:
    - type: Object
      object:
        describedObject:
          apiVersion: v1
          kind: Service
          name: jervis-aider
        metric:
          name: active_coding_tasks     # Custom metrika z orchestrátoru
        target:
          type: Value
          value: "1"                    # 1 pod per aktivní task
```

**Pro:** Kubernetes-native, automatické, funguje s liveness/readiness probes
**Proti:** Scale-to-zero trvá (5 min stabilizace), cold start podu ~10-30s

##### Přístup B: Kubernetes Jobs (DOPORUČENÝ pro coding agenty)

```yaml
# Job se vytvoří dynamicky pro každý coding task
apiVersion: batch/v1
kind: Job
metadata:
  name: jervis-aider-task-abc123
  namespace: jervis
  labels:
    app: jervis-coding
    agent: aider
    task-id: abc123
    client-id: client-xyz
spec:
  backoffLimit: 1           # 1 retry
  activeDeadlineSeconds: 1800  # 30 min timeout
  ttlSecondsAfterFinished: 300  # Cleanup po 5 min
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: aider
          image: ghcr.io/jandamek/jervis-aider:latest
          env:
            - name: TASK_ID
              value: "abc123"
            - name: ORCHESTRATOR_CALLBACK_URL
              value: "http://jervis-orchestrator:8090/callback/abc123"
            - name: INSTRUCTIONS
              valueFrom:
                configMapKeyRef:
                  name: coding-task-abc123
                  key: instructions
          volumeMounts:
            - name: workspace
              mountPath: /workspace
      volumes:
        - name: workspace
          persistentVolumeClaim:
            claimName: jervis-workspace
```

**Pro:**
- Přesně N instancí = N concurrent tasks (žádná zbytečná alokace)
- Automatický cleanup (`ttlSecondsAfterFinished`)
- Přirozený timeout (`activeDeadlineSeconds`)
- Job failure = viditelný v `kubectl get jobs`
- Scale-to-zero nativně (žádný Job = žádné resources)

**Proti:**
- Cold start pro každý task (~10-30s)
- Potřeba K8s API přístupu z orchestrátoru

### 14.6 Hybridní přístup (DOPORUČENÝ)

| Služba | Typ | Důvod |
|--------|-----|-------|
| **Orchestrator** (Python) | Deployment (replicas: 1) | Stálý, zpracovává všechny requesty |
| **Aider** | Job (on-demand) | Krátké tasky (sekundy), časté |
| **OpenHands** | Job (on-demand) | Dlouhé tasky (minuty), méně časté |
| **Junie** | Job (on-demand) | Premium, jen když potřeba |
| **Claude Code** | Job (on-demand) | Cloud-based, jen když potřeba |
| **Knowledgebase** | Deployment (replicas: 1) | Stálý, obsluhuje RAG/search |
| **Server** (Kotlin) | Deployment (replicas: 1) | Stálý, API gateway |

### 14.7 Python orchestrátor – Job management

```python
# service-orchestrator/app/k8s/job_manager.py

from kubernetes import client, config
from kubernetes.client import V1Job, V1ObjectMeta, V1JobSpec, V1PodTemplateSpec

class CodingJobManager:
    """Dynamicky vytváří K8s Jobs pro coding agenty."""

    def __init__(self):
        config.load_incluster_config()  # V K8s clusteru
        self.batch_v1 = client.BatchV1Api()
        self.core_v1 = client.CoreV1Api()

    async def launch_coding_job(
        self,
        agent: str,             # "aider" | "openhands" | "junie" | "claude-code"
        task_id: str,
        instructions: str,
        project_path: str,
        max_instances: int,     # Z AgentConfigDto
    ) -> str:
        """Spustí K8s Job pro coding task. Vrací job_name."""

        # 1. Zkontrolovat limit concurrent instancí
        active_jobs = await self._count_active_jobs(agent)
        if active_jobs >= max_instances:
            raise TooManyInstancesError(
                f"Agent {agent} má {active_jobs}/{max_instances} aktivních instancí. "
                f"Čekejte nebo zvyšte limit v Settings > Agenti."
            )

        # 2. Vytvořit ConfigMap s instrukcemi
        config_map = client.V1ConfigMap(
            metadata=client.V1ObjectMeta(
                name=f"coding-task-{task_id}",
                namespace="jervis",
            ),
            data={
                "instructions": instructions,
                "project_path": project_path,
            },
        )
        self.core_v1.create_namespaced_config_map("jervis", config_map)

        # 3. Vytvořit Job
        job = self._build_job(agent, task_id)
        result = self.batch_v1.create_namespaced_job("jervis", job)

        return result.metadata.name

    async def _count_active_jobs(self, agent: str) -> int:
        """Kolik Jobů tohoto agenta aktuálně běží."""
        jobs = self.batch_v1.list_namespaced_job(
            "jervis",
            label_selector=f"app=jervis-coding,agent={agent}",
        )
        return sum(1 for j in jobs.items if j.status.active)

    async def get_job_status(self, job_name: str) -> dict:
        """Stav Jobu pro UI Running Processes panel."""
        job = self.batch_v1.read_namespaced_job(job_name, "jervis")
        return {
            "name": job_name,
            "active": job.status.active or 0,
            "succeeded": job.status.succeeded or 0,
            "failed": job.status.failed or 0,
            "start_time": job.status.start_time,
        }

    async def stream_job_logs(self, job_name: str):
        """Stream logy z running Jobu → SSE → UI."""
        pods = self.core_v1.list_namespaced_pod(
            "jervis",
            label_selector=f"job-name={job_name}",
        )
        if not pods.items:
            return

        pod_name = pods.items[0].metadata.name
        # Follow logs (streaming)
        async for line in self.core_v1.read_namespaced_pod_log(
            pod_name, "jervis",
            follow=True,
            _preload_content=False,
        ):
            yield line.decode("utf-8")

    def _build_job(self, agent: str, task_id: str) -> V1Job:
        """Sestaví K8s Job spec pro daného agenta."""
        AGENT_IMAGES = {
            "aider": "ghcr.io/jandamek/jervis-aider:latest",
            "openhands": "ghcr.io/jandamek/jervis-coding-engine:latest",
            "junie": "ghcr.io/jandamek/jervis-junie:latest",
            "claude-code": "ghcr.io/jandamek/jervis-claude-code:latest",
        }
        AGENT_TIMEOUTS = {
            "aider": 600,       # 10 min
            "openhands": 1800,  # 30 min
            "junie": 1200,      # 20 min
            "claude-code": 1800,# 30 min
        }

        return V1Job(
            metadata=V1ObjectMeta(
                name=f"jervis-{agent}-{task_id[:8]}",
                namespace="jervis",
                labels={
                    "app": "jervis-coding",
                    "agent": agent,
                    "task-id": task_id,
                },
            ),
            spec=V1JobSpec(
                backoff_limit=1,
                active_deadline_seconds=AGENT_TIMEOUTS[agent],
                ttl_seconds_after_finished=300,
                template=V1PodTemplateSpec(
                    spec=client.V1PodSpec(
                        restart_policy="Never",
                        containers=[
                            client.V1Container(
                                name=agent,
                                image=AGENT_IMAGES[agent],
                                env=[
                                    client.V1EnvVar(name="TASK_ID", value=task_id),
                                    client.V1EnvVar(
                                        name="ORCHESTRATOR_CALLBACK_URL",
                                        value=f"http://jervis-orchestrator:8090/callback/{task_id}",
                                    ),
                                ],
                                env_from=[
                                    client.V1EnvFromSource(
                                        config_map_ref=client.V1ConfigMapEnvSource(
                                            name=f"coding-task-{task_id}",
                                        ),
                                    ),
                                    client.V1EnvFromSource(
                                        secret_ref=client.V1SecretEnvSource(
                                            name="jervis-secrets",
                                        ),
                                    ),
                                ],
                                volume_mounts=[
                                    client.V1VolumeMount(
                                        name="workspace",
                                        mount_path="/workspace",
                                    ),
                                ],
                            ),
                        ],
                        volumes=[
                            client.V1Volume(
                                name="workspace",
                                persistent_volume_claim=client.V1PersistentVolumeClaimVolumeSource(
                                    claim_name="jervis-workspace",
                                ),
                            ),
                        ],
                    ),
                ),
            ),
        )
```

### 14.8 Jak UI zobrazí aktivní agenty

Running Processes panel (ze sekce 7.5) se rozšíří o K8s Job data:

```kotlin
data class ProcessInfo(
    // ... existující fieldy ...
    val k8sJobName: String?,    // "jervis-aider-abc12345"
    val k8sJobStatus: String?,  // "Running" | "Succeeded" | "Failed"
    val maxInstances: Int?,     // Z AgentConfigDto
    val activeInstances: Int?,  // Aktuální count
)
```

```
┌── Running Processes ─────────────────────────────────────────┐
│                                                               │
│ 🟢 Orchestrator: Goal 2/3 – JWT implementation               │
│                                                               │
│ 🟡 Aider (1/2 instancí):                                    │
│    └─ Task abc123: Modifying AuthService.kt...  [2m 15s]     │
│                                                               │
│ 🔵 Claude Code (1/1 instancí):                               │
│    └─ Task def456: Reviewing security config... [45s]         │
│                                                               │
│ ⚪ OpenHands (0/1 instancí): Idle                             │
│ ⚪ Junie (0/1 instancí): Idle                                 │
│                                                               │
│ 📊 Background: 3 tasks in queue, 1 qualifying                │
│ 💰 Budget: $12.35 / $50.00 (24.7%)                           │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

### 14.9 RPC endpoint – agent config CRUD

```kotlin
// shared/common-api – nový interface
@Rpc
interface IAgentConfigService {
    suspend fun getAgentConfig(clientId: String): AgentConfigDto
    suspend fun updateAgentConfig(clientId: String, config: AgentConfigDto)
    suspend fun getCostSummary(clientId: String): CostSummaryDto
    suspend fun getActiveAgents(): List<ActiveAgentDto>
}

@Serializable
data class CostSummaryDto(
    val monthlyBudgetUsd: Double,
    val spentThisMonthUsd: Double,
    val remainingUsd: Double,
    val costBreakdown: List<CostEntryDto>,  // Per-model, per-agent breakdown
)

@Serializable
data class CostEntryDto(
    val model: String,
    val agent: String?,
    val calls: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val costUsd: Double,
)

@Serializable
data class ActiveAgentDto(
    val agent: String,           // "aider" | "openhands" | "junie" | "claude-code"
    val activeInstances: Int,
    val maxInstances: Int,
    val tasks: List<ActiveTaskDto>,
)

@Serializable
data class ActiveTaskDto(
    val taskId: String,
    val status: String,          // "running" | "succeeded" | "failed"
    val startedAt: Long,
    val elapsedSeconds: Int,
    val currentAction: String?,
)
```

---

## 15. K8s Strategie – Job vs Deployment vs Hybrid (revidovaná)

### 15.1 Proč čisté K8s Jobs NEJSOU ideální pro coding agenty

Při bližší analýze jsou problémy:

| Problém | Detail |
|---------|--------|
| **kRPC WebSocket** | Služby komunikují přes persistent WebSocket (`ICodingClient.execute()`). Job nemá stabilní Service endpoint před spuštěním |
| **Sdílený PVC** | `jervis-data-pvc` (ReadWriteMany) – dva Aider Joby na stejném git repo = merge conflict |
| **Cold start** | Každý Job = pull image + start container + init = 15-45s. Pro Aider task co trvá 30s je to 50% overhead |
| **Callback pattern** | Musel by se přepsat ICodingClient z request/response na async callback – velký refactor |
| **Log streaming** | Job pody nemají stabilní název – komplikuje `kubectl logs -f` i programatický streaming |

### 15.2 Tři reálné strategie

#### Strategie A: Deployment s `replicas: 0` + dynamický scale (DOPORUČENÁ)

```
Normální stav:     replicas: 0  → žádný pod běží, nulová spotřeba
Task přijde:       orchestrátor → PATCH replicas: 1 → pod nastartuje
Task dokončen:     orchestrátor → PATCH replicas: 0 → pod se uklidí
Více tasků:        orchestrátor → PATCH replicas: N (do maxInstances)
```

**Implementace:**

```python
# service-orchestrator/app/k8s/agent_scaler.py

from kubernetes import client, config

class AgentScaler:
    """Scale coding agent Deployments 0↔N dle potřeby."""

    def __init__(self):
        config.load_incluster_config()
        self.apps_v1 = client.AppsV1Api()

    DEPLOYMENT_MAP = {
        "aider":       "jervis-aider",
        "openhands":   "jervis-coding-engine",
        "junie":       "jervis-junie",
        "claude-code": "jervis-claude-code",
    }

    async def ensure_running(self, agent: str, desired_replicas: int = 1):
        """Zajistí že agent má alespoň desired_replicas podů."""
        deployment_name = self.DEPLOYMENT_MAP[agent]
        current = self._get_replicas(deployment_name)

        if current < desired_replicas:
            self._scale(deployment_name, desired_replicas)
            await self._wait_ready(deployment_name, timeout=60)

    async def scale_down(self, agent: str):
        """Scale na 0 po dokončení všech tasků."""
        deployment_name = self.DEPLOYMENT_MAP[agent]
        active_tasks = await self._count_active_tasks(agent)

        if active_tasks == 0:
            self._scale(deployment_name, 0)

    async def scale_down_idle(self, idle_timeout_seconds: int = 300):
        """Periodicky: scale down agenty co jsou idle > timeout."""
        for agent, deployment in self.DEPLOYMENT_MAP.items():
            current = self._get_replicas(deployment)
            if current > 0:
                last_activity = await self._get_last_activity(agent)
                if last_activity and (now() - last_activity).seconds > idle_timeout_seconds:
                    self._scale(deployment, 0)

    def _scale(self, deployment_name: str, replicas: int):
        self.apps_v1.patch_namespaced_deployment_scale(
            deployment_name, "jervis",
            body={"spec": {"replicas": replicas}},
        )

    def _get_replicas(self, deployment_name: str) -> int:
        dep = self.apps_v1.read_namespaced_deployment(deployment_name, "jervis")
        return dep.spec.replicas or 0
```

**Výhody:**
- **Zachová kRPC WebSocket** – žádná změna komunikace, `ICodingClient.execute()` funguje beze změny
- **Zachová Service endpoint** – `jervis-aider:3100` existuje vždy, jen se čeká na ready pod
- **Scale-to-zero** – `replicas: 0` = nulové resources
- **Známý cold start** – image cached na node, start = 10-20s (ne pull)
- **Jednoduchá implementace** – jen `kubectl scale` / K8s API patch

**Nevýhody:**
- Startup stále 10-20s (ale to je přijatelné)
- Orchestrátor potřebuje K8s API přístup (ServiceAccount + RBAC)

#### Strategie B: Deployment `replicas: 1` + fronty (AKTUÁLNÍ)

Současný stav – služba běží stále, přijímá tasky sekvenčně.

**Pro:** Nulový cold start, jednoduchý.
**Proti:** 4 Deploymenty × 256MB RAM = 1 GB zbytečně alokovaných resources i když nic nedělají.

#### Strategie C: K8s Jobs pro izolované workspace tasky

Vhodné JEN pokud se přepíše komunikační pattern na async callback a každý Job dostane vlastní workspace (ne sdílený PVC). To je velký refactor pro malý benefit v aktuálním scale.

### 15.3 Doporučení: Strategie A s workspace izolací

```
┌─ Orchestrátor rozhodne: potřebuji Aider ─┐
│                                            │
│  1. Zkontroluj AgentConfigDto.maxInstances │
│  2. Zkontroluj kolik tasků již běží        │
│  3. Scale Deployment na potřebný počet     │
│  4. Počkej na ready pod (max 60s)          │
│  5. Pošli task přes kRPC WebSocket         │
│  6. Streamuj výsledky do UI                │
│  7. Po dokončení: scale down po idle       │
└────────────────────────────────────────────┘
```

### 15.4 Workspace model (sdílený disk, per-task branch)

**Aktuální stav:** Server připraví codebase na sdíleném PVC (`jervis-data-pvc`). Coding agenti si jen vytvoří branch a pracují na ní.

**Problém s multiple replicas:** Dva agenti na stejném repo checkout = git lock conflict (`index.lock`).

**Řešení: Git worktrees na sdíleném disku**

```
/opt/jervis/data/projects/my-project/             ← hlavní repo (připravený serverem)
/opt/jervis/data/projects/my-project/.worktrees/   ← worktree root
  task-abc123/                                     ← worktree pro task 1 (branch: task-abc123)
  task-def456/                                     ← worktree pro task 2 (branch: task-def456)
```

Git worktrees sdílejí `.git` objects ale mají vlastní index – dva agenti mohou pracovat SOUČASNĚ bez konfliktu.

**Flow:**
1. Orchestrátor: `git worktree add .worktrees/task-{id} -b task/{id}`
2. Coding agent dostane path: `.worktrees/task-{id}` (ne root repo)
3. Agent pracuje normálně (commit, edit) – izolovaný od ostatních
4. Po dokončení: orchestrátor mergnue branch (nebo vytvoří PR), `git worktree remove`

**Cleanup:** Worktree co visí > 2h bez aktivity → automatic remove (cron/orchestrátor)

### 15.5 Max instances – pravidla

```
┌──────────────────────────────────────────────────────────────┐
│ Settings > Agenti > Coding Agents                             │
│                                                               │
│  Globální limit concurrent coding tasků: [ 3 ▾]              │
│  ⚠️ Omezeno sdíleným PVC a git worktrees                     │
│                                                               │
│  Agent           Enabled  Max ▾   Pozn.                       │
│  ─────────────   ──────   ─────   ──────────────────────────  │
│  Aider           [✓]      [ 2 ]   Rychlý, lokální model      │
│  OpenHands       [✓]      [ 1 ]   Pomalý, těžký na resources  │
│  Junie           [✓]      [ 1 ]   Placený (Anthropic)         │
│  Claude Code     [✓]      [ 1 ]   Placený (Anthropic)         │
│                                                               │
│  ℹ️ Globální limit = max(aider) + max(openhands) + ... ale    │
│    skutečně poběží maximálně "globální limit" naráz.          │
│    Zbytek čeká ve frontě.                                     │
└──────────────────────────────────────────────────────────────┘
```

**Logika v orchestrátoru:**

```python
class CodingTaskQueue:
    """Fronta coding tasků s limity z AgentConfigDto."""

    async def submit(self, agent: str, task: CodingTask) -> str:
        config = await self.get_agent_config()

        # 1. Agent enabled?
        agent_config = config.get_agent(agent)
        if not agent_config.enabled:
            raise AgentDisabledError(f"{agent} je vypnutý v Settings")

        # 2. Globální limit
        total_running = await self.count_all_running()
        if total_running >= config.max_concurrent_coding_agents:
            return await self.enqueue(agent, task)  # Čeká ve frontě

        # 3. Per-agent limit
        agent_running = await self.count_running(agent)
        if agent_running >= agent_config.max_instances:
            return await self.enqueue(agent, task)  # Čeká ve frontě

        # 4. Scale up + spustit
        await self.scaler.ensure_running(agent, agent_running + 1)
        worktree = await self.create_worktree(task)
        return await self.execute(agent, task, worktree)

    async def on_task_complete(self, agent: str, task_id: str):
        """Po dokončení tasku: zpracuj frontu, scale down."""
        await self.cleanup_worktree(task_id)

        # Další task ve frontě?
        next_task = await self.dequeue(agent)
        if next_task:
            worktree = await self.create_worktree(next_task)
            await self.execute(agent, next_task, worktree)
        else:
            # Nic ve frontě → scale down po idle timeout
            await self.scaler.schedule_scale_down(agent, delay=300)
```

### 15.6 RBAC pro orchestrátor – K8s API přístup

```yaml
# k8s/orchestrator-rbac.yaml (nový)
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jervis-orchestrator
  namespace: jervis
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: jervis-orchestrator-role
  namespace: jervis
rules:
  # Scale deployments (coding agents)
  - apiGroups: ["apps"]
    resources: ["deployments/scale", "deployments"]
    verbs: ["get", "patch"]
  # Read pod status (pro Running Processes panel)
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list"]
  # Read pod logs (streaming do UI)
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: jervis-orchestrator-binding
  namespace: jervis
subjects:
  - kind: ServiceAccount
    name: jervis-orchestrator
    namespace: jervis
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: jervis-orchestrator-role
```

### 15.7 Aktualizovaný K8s přehled

| Služba | K8s typ | Replicas (idle) | Replicas (active) | Scaling |
|--------|---------|-----------------|-------------------|---------|
| **Kotlin Server** | Deployment | 1 | 1 | Statický |
| **Python Orchestrator** | Deployment | 1 | 1 | Statický |
| **Knowledgebase** | Deployment | 1 | 1 | Statický |
| **Aider** | Deployment | **0** | 1-2 | Dynamický (AgentScaler) |
| **OpenHands** | Deployment | **0** | 1 | Dynamický |
| **Junie** | Deployment | **0** | 1 | Dynamický |
| **Claude Code** | Deployment | **0** | 1 | Dynamický |
| **Joern** | Deployment | 1 | 1 | Statický |
| **Tika** | Deployment | 1 | 1 | Statický |
| **Whisper** | Deployment | 1 | 1 | Statický |
| **GitHub/GitLab/Atlassian** | Deployment | 1 | 1 | Statický |

**Resource savings vs aktuální stav:**
- Idle: 4 coding Deploymenty × ~256MB = **1 GB RAM ušetřeno** když nic neběží
- GPU: Coding agenti nepoužívají GPU (volají Ollama/Anthropic API) → RAM only

---

## 16. MCP Server pro Knowledge Base – přístup coding agentů ke KB {#16-mcp-server-pro-knowledge-base}

### 16.1 Motivace

Coding agenti (Aider, OpenHands, Junie, Claude Code) aktuálně pracují **izolovaně** – nemají přístup ke Knowledge Base, takže:
- Neznají architektonická rozhodnutí, coding conventions, ani historii projektu
- Nemohou se zeptat na kontext ("jak je implementován UserService?")
- Opakují chyby, které už KB dokumentuje
- Nemají přístup ke graph knowledge (entity relationships, component dependencies)

**Řešení:** MCP (Model Context Protocol) server jako univerzální adaptér mezi KB a coding agenty.

### 16.2 Proč MCP a ne přímé REST volání

| Aspekt | Přímé REST | MCP |
|--------|-----------|-----|
| **Claude Code** | Musí manuálně volat curl | Nativní podpora MCP z CLI |
| **Aider** | Nepodporuje custom tools | MCP přes custom commands |
| **OpenHands** | Možné přes custom actions | MCP přes tool interface |
| **Junie** | IDE plugin API | MCP přes integraci |
| **Standardizace** | Každý agent jinak | Jednotné rozhraní pro všechny |
| **Evoluce** | Breaking changes | Verzované schema |

Claude Code má **nativní MCP podporu** – stačí nakonfigurovat v `.claude/mcp.json` nebo CLAUDE.md.
Pro ostatní agenty se MCP server může použít přes wrapper/adapter.

### 16.3 Architektura

```
┌────────────────────────────────────────────────────┐
│                  Coding Agent                       │
│  (Aider / OpenHands / Junie / Claude Code)         │
│                                                     │
│  env: CLIENT_ID=xxx  PROJECT_ID=yyy                │
└────────────┬───────────────────────────────────────┘
             │ MCP protocol (stdio / SSE)
             ▼
┌────────────────────────────────────────────────────┐
│              KB MCP Server (Python)                 │
│                                                     │
│  Tools:                                             │
│    kb_search(query, scope?)                         │
│    kb_retrieve(query, max_results?, min_conf?)      │
│    kb_traverse(start_node, direction?, hops?)       │
│    kb_graph_search(query, node_type?)               │
│    kb_get_evidence(node_key)                        │
│    kb_store(content, kind, metadata?)       [write] │
│    kb_resolve_alias(alias)                          │
│                                                     │
│  Context injection:                                 │
│    clientId ← env CLIENT_ID (povinné)              │
│    projectId ← env PROJECT_ID (volitelné)          │
│                                                     │
│  Scope resolution:                                  │
│    null/global → client → project                   │
└────────────┬───────────────────────────────────────┘
             │ HTTP REST
             ▼
┌────────────────────────────────────────────────────┐
│         Knowledge Base Service (FastAPI)            │
│                                                     │
│  /retrieve        → hybrid RAG + graph             │
│  /retrieve/simple → RAG only                       │
│  /retrieve/hybrid → full control                   │
│  /traverse        → graph traversal                │
│  /ingest          → store knowledge                │
│  /graph/search    → node search                    │
│  /graph/node/{key}/evidence → supporting chunks    │
│  /alias/resolve   → entity resolution              │
│                                                     │
│  Weaviate (vector) + ArangoDB (graph)              │
└────────────────────────────────────────────────────┘
```

### 16.4 Multi-tenant data separation

KB služba **již implementuje** multi-tenant filtrování – MCP server ho pouze konzumuje:

#### Aktuální model v KB (rag_service.py):

```python
# Weaviate filter – vždy se aplikuje
filter_conditions = [
    # Global/null scope: clientId="" (sentinel pro obecné znalosti)
    # NEBO přesný match na clientId
    Filter.by_property("clientId").equal("") |
    Filter.by_property("clientId").equal(client_id),
]
if project_id:
    filter_conditions.append(
        Filter.by_property("projectId").equal("") |
        Filter.by_property("projectId").equal(project_id)
    )
```

#### Aktuální model v KB (graph_service.py):

```python
# ArangoDB AQL filter
FILTER doc.clientId == "" OR doc.clientId == @clientId
FILTER @projectId == null OR doc.projectId == "" OR doc.projectId == @projectId
```

#### Search hierarchy (klíčový invariant):

```
Scope 1: clientId="" AND projectId=""     → GLOBÁLNÍ znalosti (best practices, frameworks)
Scope 2: clientId="acme" AND projectId="" → KLIENT znalosti (coding style, architecture decisions)
Scope 3: clientId="acme" AND projectId="web-app" → PROJEKT znalosti (implementation details)

Dotaz s clientId="acme", projectId="web-app" vrátí:
  ✅ Scope 1 (global)  +  ✅ Scope 2 (client)  +  ✅ Scope 3 (project)

Dotaz s clientId="acme", projectId=null vrátí:
  ✅ Scope 1 (global)  +  ✅ Scope 2 (client)  +  ❌ Scope 3 (nikoliv project-specific)

INVARIANT: projectId vyžaduje clientId (projectId bez clientId nedává smysl)
```

#### Jak MCP server předává kontext:

```python
# MCP server – každý tool call automaticky injectuje scope
class KBMcpServer:
    def __init__(self):
        self.client_id = os.environ.get("CLIENT_ID", "")
        self.project_id = os.environ.get("PROJECT_ID")  # None pokud nenastaveno
        self.kb_base_url = os.environ.get("KB_URL", "http://jervis-knowledgebase:8100")

    async def kb_search(self, query: str, scope: str = "auto") -> list:
        """
        scope = "auto"    → použije CLIENT_ID + PROJECT_ID z env
        scope = "global"  → hledá jen globální (clientId="", projectId="")
        scope = "client"  → hledá jen client-level (bez project)
        scope = "project" → plný scope (default)
        """
        client_id, project_id = self._resolve_scope(scope)

        response = await httpx.post(f"{self.kb_base_url}/retrieve", json={
            "query": query,
            "clientId": client_id,
            "projectId": project_id,
            "maxResults": 10,
            "minConfidence": 0.6
        })
        return response.json()
```

### 16.5 MCP Tool definice

#### Read tools (bezpečné, vždy povolené):

| Tool | Popis | Parametry | KB endpoint |
|------|-------|-----------|-------------|
| `kb_search` | Hybrid search (RAG + graph) | `query`, `scope?`, `max_results?` | POST /retrieve |
| `kb_search_simple` | Rychlý RAG-only search | `query`, `max_results?` | POST /retrieve/simple |
| `kb_traverse` | Graph traversal od uzlu | `start_node`, `direction?`, `max_hops?` | POST /traverse |
| `kb_graph_search` | Hledání uzlů v grafu | `query`, `node_type?`, `limit?` | GET /graph/search |
| `kb_get_evidence` | Podpůrné chunky pro uzel | `node_key` | GET /graph/node/{key}/evidence |
| `kb_resolve_alias` | Rozlišení aliasu entity | `alias` | GET /alias/resolve |

#### Write tools (vyžadují approval pokud je zapnutý):

| Tool | Popis | Parametry | KB endpoint |
|------|-------|-----------|-------------|
| `kb_store` | Uložení nové znalosti | `content`, `kind`, `source_urn?`, `metadata?` | POST /ingest |
| `kb_store_finding` | Uložení nálezu z code review | `content`, `subject`, `source_urn` | POST /ingest/full |

### 16.6 Implementace MCP serveru

```python
# backend/service-kb-mcp/server.py
from mcp.server import Server, Tool
from mcp.server.stdio import stdio_server
import httpx
import os
import json

app = Server("jervis-kb")

# Tenant context z environment variables
CLIENT_ID = os.environ.get("CLIENT_ID", "")
PROJECT_ID = os.environ.get("PROJECT_ID")
KB_URL = os.environ.get("KB_URL", "http://jervis-knowledgebase:8100")

def _resolve_scope(scope: str = "auto") -> tuple[str, str | None]:
    """Resolve tenant scope based on parameter or env defaults."""
    if scope == "global":
        return "", None
    elif scope == "client":
        return CLIENT_ID, None
    else:  # "auto" or "project"
        return CLIENT_ID, PROJECT_ID

@app.tool()
async def kb_search(
    query: str,
    scope: str = "auto",
    max_results: int = 10,
    min_confidence: float = 0.6
) -> str:
    """Search the Knowledge Base for relevant information.

    Combines vector search (RAG) with knowledge graph expansion.
    Results are scoped to the current client and project.

    Args:
        query: Natural language search query
        scope: "auto" (client+project), "global", "client" (no project filter)
        max_results: Maximum number of results (default 10)
        min_confidence: Minimum confidence threshold (0.0-1.0)
    """
    client_id, project_id = _resolve_scope(scope)
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(f"{KB_URL}/retrieve", json={
            "query": query,
            "clientId": client_id,
            "projectId": project_id,
            "maxResults": max_results,
            "minConfidence": min_confidence,
            "expandGraph": True
        })
        data = resp.json()
        # Format for LLM consumption
        results = []
        for item in data.get("items", []):
            results.append(f"[{item.get('confidence', 0):.2f}] {item.get('sourceUrn', '?')}: {item.get('content', '')[:500]}")
        return "\n---\n".join(results) if results else "No results found."

@app.tool()
async def kb_search_simple(query: str, max_results: int = 5) -> str:
    """Quick RAG-only search without graph expansion. Faster but less comprehensive."""
    client_id, project_id = _resolve_scope("auto")
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.post(f"{KB_URL}/retrieve/simple", json={
            "query": query,
            "clientId": client_id,
            "projectId": project_id,
            "maxResults": max_results
        })
        data = resp.json()
        results = []
        for item in data.get("items", []):
            results.append(f"{item.get('sourceUrn', '?')}: {item.get('content', '')[:500]}")
        return "\n---\n".join(results) if results else "No results found."

@app.tool()
async def kb_traverse(
    start_node: str,
    direction: str = "outbound",
    max_hops: int = 2
) -> str:
    """Traverse the knowledge graph starting from a node.

    Args:
        start_node: Node key or label to start traversal from
        direction: "outbound", "inbound", or "any"
        max_hops: Maximum traversal depth (1-3 recommended)
    """
    client_id, project_id = _resolve_scope("auto")
    async with httpx.AsyncClient(timeout=20) as client:
        resp = await client.post(f"{KB_URL}/traverse", json={
            "startNodeKey": start_node,
            "direction": direction,
            "maxDepth": max_hops,
            "clientId": client_id,
            "projectId": project_id
        })
        nodes = resp.json()
        if not nodes:
            return f"No graph nodes found for '{start_node}'."
        lines = []
        for node in nodes:
            lines.append(f"[{node.get('type', '?')}] {node.get('label', '?')} (key={node.get('key', '?')})")
            if node.get('properties'):
                for k, v in node['properties'].items():
                    lines.append(f"  {k}: {v}")
        return "\n".join(lines)

@app.tool()
async def kb_graph_search(
    query: str,
    node_type: str = None,
    limit: int = 20
) -> str:
    """Search for nodes in the knowledge graph by label."""
    client_id, project_id = _resolve_scope("auto")
    params = {"query": query, "clientId": client_id, "limit": limit}
    if project_id:
        params["projectId"] = project_id
    if node_type:
        params["nodeType"] = node_type
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.get(f"{KB_URL}/graph/search", params=params)
        nodes = resp.json()
        if not nodes:
            return f"No graph nodes matching '{query}'."
        return "\n".join(
            f"[{n.get('type','?')}] {n.get('label','?')} (key={n.get('key','?')})"
            for n in nodes
        )

@app.tool()
async def kb_get_evidence(node_key: str) -> str:
    """Get RAG chunks that support a specific knowledge graph node."""
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.get(
            f"{KB_URL}/graph/node/{node_key}/evidence",
            params={"clientId": CLIENT_ID}
        )
        data = resp.json()
        chunks = data.get("chunks", [])
        if not chunks:
            return f"No evidence found for node '{node_key}'."
        return "\n---\n".join(
            f"{c.get('sourceUrn', '?')}: {c.get('content', '')[:500]}"
            for c in chunks
        )

@app.tool()
async def kb_resolve_alias(alias: str) -> str:
    """Resolve an entity alias to its canonical key.

    Use this when you encounter different names for possibly the same entity.
    Example: 'UserSvc' → 'UserService', 'auth module' → 'authentication-service'
    """
    async with httpx.AsyncClient(timeout=10) as client:
        resp = await client.get(
            f"{KB_URL}/alias/resolve",
            params={"alias": alias, "clientId": CLIENT_ID}
        )
        data = resp.json()
        return f"'{data.get('alias')}' → canonical: '{data.get('canonical')}'"

@app.tool()
async def kb_store(
    content: str,
    kind: str = "finding",
    source_urn: str = "agent://coding-agent",
    metadata: str = "{}"
) -> str:
    """Store new knowledge in the Knowledge Base.

    Use sparingly – only for genuinely useful findings discovered during coding.

    Args:
        content: The knowledge content to store
        kind: Type of knowledge ("finding", "decision", "pattern", "bug", "convention")
        source_urn: Source identifier (auto-set to agent URI)
        metadata: Additional metadata as JSON string
    """
    client_id, project_id = _resolve_scope("auto")
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(f"{KB_URL}/ingest", json={
            "clientId": client_id,
            "projectId": project_id,
            "sourceUrn": source_urn,
            "kind": kind,
            "content": content,
            "metadata": json.loads(metadata) if metadata else {}
        })
        data = resp.json()
        return f"Stored: {data.get('status', 'ok')} (chunks: {data.get('chunksCreated', '?')})"

# Entry point
async def main():
    async with stdio_server() as (read_stream, write_stream):
        await app.run(read_stream, write_stream)

if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
```

### 16.7 Integrace s coding agenty

#### Claude Code (nativní MCP):

```json
// .claude/mcp.json – generován serverem pro každý workspace
{
  "mcpServers": {
    "jervis-kb": {
      "command": "python",
      "args": ["/opt/jervis/mcp/kb-server.py"],
      "env": {
        "CLIENT_ID": "{{clientId}}",
        "PROJECT_ID": "{{projectId}}",
        "KB_URL": "http://jervis-knowledgebase:8100"
      }
    }
  }
}
```

Claude Code automaticky detekuje MCP tools a může je volat v průběhu práce.

#### Aider (přes custom commands / .aider.conf.yml):

```yaml
# .aider.conf.yml – generován serverem
# Aider nemá nativní MCP, ale lze použít:
# 1) --read flag pro kontext soubory (server pre-fetches KB results)
# 2) Custom lint/test command pro KB queries
# 3) Wrapper script přes model-metadata

# Preferovaný přístup: Server pre-fetches KB kontext a přidá do instructions
```

Pro Aider je nejpraktičtější **pre-fetch přístup**: orchestrator dotáže KB a relevantní kontext vloží přímo do `CodingRequest.instructions`.

#### OpenHands (přes custom actions):

OpenHands podporuje custom actions – MCP server se dá volat přes HTTP wrapper.

```python
# OpenHands custom action (registrovaná v workspace)
import subprocess
def search_kb(query: str) -> str:
    result = subprocess.run(
        ["python", "/opt/jervis/mcp/kb-client.py", "search", query],
        capture_output=True, text=True
    )
    return result.stdout
```

#### Junie (JetBrains IDE integration):

Junie pracuje v IDE kontextu – MCP tools se dají mapovat jako IDE plugins nebo external tools.

### 16.8 Hybrid přístup: Pre-fetch + Runtime MCP

Protože ne všichni agenti mají nativní MCP podporu, doporučuji **dvou-vrstvý přístup**:

```
Layer 1: PRE-FETCH (všichni agenti)
  Orchestrator před spuštěním coding agenta:
  1. Dotáže KB na relevantní kontext pro daný task
  2. Vloží výsledky do CodingRequest.instructions jako "Context from Knowledge Base:"
  3. Agent má kontext k dispozici od začátku

Layer 2: RUNTIME MCP (Claude Code, budoucí agenti)
  Pro agenty s MCP podporou:
  1. MCP server běží jako sidecar/stdio process
  2. Agent může dynamicky dotazovat KB během práce
  3. Může uložit nové poznatky zpět do KB
```

```
┌─────────────┐       ┌───────────────┐      ┌────────────┐
│ Orchestrator │──(1)──│ KB Service    │      │ KB MCP     │
│ (Python)     │       │ (FastAPI)     │      │ Server     │
│              │       │               │      │ (stdio)    │
│ pre-fetch KB │       │ /retrieve     │      │            │
│ context      │       │ /traverse     │      │ runtime    │
└──────┬───────┘       └───────────────┘      │ queries    │
       │                       ▲               └─────┬──────┘
       │ CodingRequest         │ HTTP                │ MCP
       │ .instructions         │                     │
       │ includes KB context   │                     │
       ▼                       │                     │
┌──────────────────────────────┴─────────────────────┘
│              Coding Agent Container                  │
│                                                      │
│  instructions = "... Context from KB: ..."           │
│  + MCP tools pro runtime dotazy (Claude Code)        │
└──────────────────────────────────────────────────────┘
```

### 16.9 Write access – bezpečnostní model

Zápis do KB od coding agentů musí být kontrolovaný:

| Akce | Povolení | Podmínka |
|------|----------|----------|
| `kb_search` | Vždy | – |
| `kb_traverse` | Vždy | – |
| `kb_store(kind="finding")` | Auto | Pouze do aktuálního project scope |
| `kb_store(kind="decision")` | Vyžaduje approval | Architektonická rozhodnutí musí potvrdit uživatel |
| `kb_store(kind="convention")` | Vyžaduje approval | Nové konvence mění budoucí chování |
| Zápis do cizího clientId/projectId | **ZAKÁZÁNO** | Hard block – MCP server nemá jiný scope |

**Implementace:**
- MCP server **nemůže** zapsat do jiného scope než má v env – fyzická izolace
- Write tools s `kind="decision"` nebo `kind="convention"` vrátí status `PENDING_APPROVAL`
- Orchestrator pak vytvoří approval request v UI (viz sekce 8)

### 16.10 Deployment

```
Varianta A: Sidecar v každém coding agent podu
  + Nízká latence (localhost)
  + Izolace scope per pod
  - Duplikovaný kód ve 4 podech

Varianta B: Standalone MCP server jako stdio process ← DOPORUČENO
  + MCP server je jen Python skript (~200 řádků)
  + Spouští se per-task s env vars pro scope
  + Claude Code nativně volá přes stdio
  + Pro ostatní agenty: orchestrator pre-fetches

Varianta C: Centrální MCP server jako K8s Deployment
  + Jeden deployment
  - Musí řešit multi-tenant routing per request
  - Komplikovanější autentizace
```

**Doporučení:** Varianta B – MCP skript se bundluje do Docker image každého agenta, spouští se per-task s CLIENT_ID a PROJECT_ID jako env variables. Pro Claude Code jako nativní MCP, pro ostatní jako pre-fetch.

### 16.11 Příprava dat – co by mělo být v KB pro coding agenty

| Kategorie | Příklad | Scope |
|-----------|---------|-------|
| **Coding conventions** | "Kotlin: use sealed interface, not sealed class" | Client |
| **Architecture decisions** | "We use CQRS pattern for order service" | Project |
| **API contracts** | "UserService.getUser() returns nullable" | Project |
| **Bug patterns** | "ConcurrentModificationException in cache layer" | Project |
| **Framework rules** | "Compose: never use mutableStateOf in loops" | Global |
| **Project structure** | "Module boundaries: domain cannot import infra" | Project |
| **Previous review findings** | "Always validate input at controller layer" | Client |

---

## 17. Claude CLI Agent – analýza aktuální implementace a vylepšení {#17-claude-cli-agent}

### 17.1 Aktuální implementace (po pull master)

Claude Code je nyní **4. coding agent** v Jervis, implementovaný jako:

```
backend/service-claude/
├── Dockerfile                          # Java 21 + Node.js 20 + @anthropic-ai/claude-code
├── build.gradle.kts
└── src/main/kotlin/com/jervis/claude/
    ├── ClaudeApplication.kt            # Ktor + kRPC server na portu 3400
    └── service/ClaudeServiceImpl.kt    # ICodingClient implementace → claude --print CLI
```

**Klíčové charakteristiky:**

| Aspekt | Detail |
|--------|--------|
| **Interface** | `ICodingClient` (sdílený se všemi coding agenty) |
| **Komunikace** | kRPC WebSocket (port 3400) |
| **CLI volání** | `claude --print --dangerously-skip-permissions <instructions>` |
| **Auth** | Setup token (Max/Pro) > API key > env vars |
| **Timeout** | `maxIterations × 5min`, max 45 min |
| **Workspace** | `/opt/jervis/data` (shared PVC) |
| **Docker** | Java 21 JRE + Node.js 20 + `npm install -g @anthropic-ai/claude-code` |
| **K8s** | Deployment replicas:1, Service port 3400 |

### 17.2 Integrace v CodingTools

```kotlin
// CodingTools.kt – nový tool executeClaude()
@Tool
suspend fun executeClaude(
    instructions: String,
    files: List<String> = emptyList(),
    verifyCommand: String? = null,
): String

// execute() s AUTO strategií – Claude je nový default pro komplexní tasky:
// files.isEmpty() || files.size > 3 → executeClaude() (dříve OpenHands)
```

Claude Code je nyní v `execute(strategy="AUTO")` **preferovaný pro komplexní tasky** (mnoho souborů nebo nespecifikované soubory).

### 17.3 Identifikované problémy a slabiny

#### P0 – Kritické

**P0.1 – `--print` mode omezuje agentní chování**
```kotlin
// ClaudeServiceImpl.kt:66-67
add("claude")
add("--print")
add("--dangerously-skip-permissions")
```
`--print` mode je **single-turn** – Claude dostane prompt, odpoví a skončí. Nezíská možnost:
- Iterovat přes chyby (compile error → fix → retry)
- Použít tools (file search, grep, test execution)
- Self-correct na základě test results

**Doporučení:** Přejít na `claude --json` nebo Claude Agent SDK (Python) pro plné agentní chování s tool use.

#### P0.2 – Žádný streaming výstupu
```kotlin
// ClaudeServiceImpl.kt:152-158
val readerThread = Thread {
    reader.forEachLine { line ->
        output.appendLine(line)  // Sbírá se do StringBuilderu, nikam se nestreamuje
    }
}
```
Výstup se sbírá do paměti a vrací se až po dokončení. Uživatel nevidí průběh.

**Doporučení:** Napojit reader na SSE/WebSocket stream (viz sekce 7).

#### P0.3 – Files se předávají v instructions, ne jako context
```kotlin
// ClaudeServiceImpl.kt:69-71
if (req.files.isNotEmpty()) {
    val filesContext = req.files.joinToString("\n") { "File: $it" }
    add("$filesContext\n\n${req.instructions}")
}
```
Soubory se předávají jako text v instructions. Claude Code CLI podporuje `--allowedTools`, `--context` a jiné parametry pro strukturovanější předání kontextu.

#### P1 – Vysoká priorita

**P1.1 – Chybí MCP konfigurace pro KB**
Agent nemá přístup ke Knowledge Base (viz sekce 16). Claude Code nativně podporuje MCP – stačí přidat `.claude/mcp.json` do workspace.

**P1.2 – Chybí CLAUDE.md pro workspace kontext**
Claude Code automaticky čte `CLAUDE.md` z workspace root. Orchestrator by měl generovat projekt-specifický CLAUDE.md s:
- Coding conventions (z KB)
- Architecture decisions
- Allowed/forbidden patterns
- Test commands

**P1.3 – Jednoduchý error handling**
```kotlin
// ClaudeServiceImpl.kt:188-194
private fun extractErrorSummary(output: String): String {
    val lines = output.lines().filter { it.isNotBlank() }.takeLast(3)
    ...
}
```
Pouze poslední 3 řádky. Chybí:
- Parsování strukturovaného výstupu (JSON mode)
- Rozlišení typů chyb (auth failure vs timeout vs code error)
- Retry logika pro transient failures

**P1.4 – Chybí model selection**
```kotlin
// Aktuálně: žádný --model parametr → CLI default
```
Nelze ovlivnit jaký model Claude Code použije. V configmap je `claude-sonnet-4-20250514`, ale to se nikam nepředává.

### 17.4 Vylepšení – short term (kompatibilní s aktuální architekturou)

```
1. Přidat --model parametr:
   add("--model"); add(req.model ?: System.getenv("CLAUDE_MODEL") ?: "claude-sonnet-4-5-20250929")

2. Přidat --output-format json pro parsovatelný výstup:
   add("--output-format"); add("json")

3. Generovat .claude/mcp.json pro KB přístup (viz sekce 16):
   - Před spuštěním claude CLI zapsat MCP config do workspace

4. Generovat CLAUDE.md s projekt kontextem:
   - Coding conventions z KB
   - Architecture rules
   - Test/build commands

5. Streaming přes --json + stream parsing:
   - Každý řádek JSON output → parsovat → posílat přes kRPC stream
```

### 17.5 Vylepšení – long term (Python rewrite)

V kontextu migrace orchestratoru na Python (sekce 4, 9) se nabízí přepsat i Claude agenta:

```python
# Varianta A: Claude Agent SDK (Python) – DOPORUČENO
from claude_code import Agent, Permission

async def execute_claude(request: CodingRequest) -> CodingResult:
    agent = Agent(
        model=request.model or "claude-sonnet-4-5-20250929",
        working_directory=f"/opt/jervis/data/{request.workspace}",
        permissions=[Permission.READ, Permission.WRITE, Permission.EXECUTE],
        mcp_servers={
            "jervis-kb": {
                "command": "python",
                "args": ["/opt/jervis/mcp/kb-server.py"],
                "env": {
                    "CLIENT_ID": request.client_id,
                    "PROJECT_ID": request.project_id,
                }
            }
        },
        max_turns=request.max_iterations * 5,
    )

    # Streaming execution
    async for event in agent.stream(request.instructions):
        if event.type == "text":
            yield StreamEvent(type="output", content=event.content)
        elif event.type == "tool_use":
            yield StreamEvent(type="tool", name=event.tool, input=event.input)
        elif event.type == "result":
            yield StreamEvent(type="complete", content=event.result)
```

```python
# Varianta B: claude CLI s --json output + stream parsing
import subprocess
import json

async def execute_claude_cli(request: CodingRequest):
    process = await asyncio.create_subprocess_exec(
        "claude", "--json", "--dangerously-skip-permissions",
        "--model", request.model or "claude-sonnet-4-5-20250929",
        request.instructions,
        cwd=f"/opt/jervis/data/{request.workspace}",
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env={
            **os.environ,
            "CLIENT_ID": request.client_id,
            "PROJECT_ID": request.project_id,
        }
    )

    async for line in process.stdout:
        event = json.loads(line)
        yield StreamEvent.from_claude_json(event)
```

### 17.6 Porovnání coding agentů (aktualizované)

| Aspekt | Aider | OpenHands | Claude Code | Junie |
|--------|-------|-----------|-------------|-------|
| **Typ** | CLI tool | Autonomous agent | Agentic CLI | IDE agent |
| **Best for** | 1-3 soubory, quick fixes | Multi-file refactoring | Reasoning, architecture | Premium, complex |
| **Cena** | Ollama (free) / API | Ollama (free) / API | Anthropic API (paid) | JetBrains (paid) |
| **Speed** | Rychlý (seconds) | Pomalý (minutes) | Střední (30s-5min) | Rychlý |
| **MCP support** | Ne (pre-fetch only) | Custom actions | **Nativní** | Plugin API |
| **Streaming** | Ano (CLI output) | Ano (WebSocket) | Ano (--json) | Ano (IDE) |
| **Self-correction** | Omezená | Ano | **Ano (plně agentní)** | Ano |
| **Max iterations** | 3 | 10 | 10 (× 5 min = 50 min) | 5 |
| **Auth** | API key | API key | Setup token / API key | API key |
| **K8s port** | 3100 | 3200 | 3400 | 3300 |

### 17.7 Doporučená strategie výběru agenta (aktualizovaná)

```
Task complexity assessment:
  ├─ SIMPLE (1-3 files, clear instructions)
  │    → Aider (fast, free with Ollama)
  │
  ├─ MEDIUM (3-10 files, some dependencies)
  │    → Claude Code (good reasoning, moderate cost)
  │
  ├─ COMPLEX (10+ files, architectural changes)
  │    → OpenHands (thorough exploration)
  │    → fallback: Claude Code
  │
  ├─ REASONING-HEAVY (design decisions, patterns)
  │    → Claude Code (best reasoning)
  │
  └─ CRITICAL / LAST RESORT
       → Junie (premium, expensive)

Escalation path:
  Aider fail → Claude Code → OpenHands → Junie
```

---

## 18. Cílová architektura – Python orchestrator, K8s Jobs, univerzální KB přístup {#18-cílová-architektura}

Tato sekce shrnuje **finální cílový stav** po migraci. Klíčové principy:
1. **Python orchestrator** jako hlavní mozek (LangGraph)
2. **Coding agenti jako K8s Jobs** – ephemeral, na vyžádání
3. **MCP nativně pro Claude Code**, pre-fetch pro ostatní
4. **Sdílený disk** (PVC) s git worktrees pro izolaci

### 18.1 Celkový pohled

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Kotlin Server                               │
│  (Spring Boot – API gateway, auth, kRPC hub, UI WebSocket)         │
│                                                                     │
│  Zodpovědnosti:                                                     │
│    - REST/kRPC API pro UI                                           │
│    - Správa uživatelů, projektů, klientů                           │
│    - Task management (vytvoření, stav, approval)                   │
│    - SSE bridge: přeposílá streaming z orchestratoru do UI          │
│    - Spouštění coding agent K8s Jobs                               │
│    - KB kontext pre-fetch pro agenty bez MCP                       │
└────────┬──────────────────────────────────────┬─────────────────────┘
         │ gRPC / REST                          │ K8s API
         ▼                                      ▼
┌─────────────────────┐              ┌─────────────────────────────┐
│  Python Orchestrator │              │  K8s Jobs (coding agenti)   │
│  (LangGraph)         │              │                             │
│                      │              │  Job: jervis-aider-{id}     │
│  - StateGraph        │              │  Job: jervis-openhands-{id} │
│  - Sub-agenti        │              │  Job: jervis-claude-{id}    │
│  - Tool execution    │              │  Job: jervis-junie-{id}     │
│  - Streaming SSE     │              │                             │
│  - Checkpoint        │              │  Každý Job:                 │
│  - Approval flow     │              │    - Shared PVC mount       │
│                      │              │    - Git worktree            │
│  Deployment (stálý)  │              │    - env: CLIENT_ID,        │
└─────────┬────────────┘              │          PROJECT_ID,        │
          │                           │          TASK_ID            │
          │ HTTP REST                 │    - TTL: auto-cleanup      │
          ▼                           └──────────────┬──────────────┘
┌─────────────────────┐                              │
│  Knowledge Base      │                              │
│  (FastAPI)           │◄─────────────────────────────┘
│                      │  HTTP (pre-fetch i runtime MCP)
│  Weaviate + ArangoDB │
│  Deployment (stálý)  │
└──────────────────────┘
```

### 18.2 Proč K8s Jobs místo Deploymentů

Sekce 15 navrhovala Deployment s `replicas: 0` + AgentScaler. Po přehodnocení je **K8s Job lepší volba**:

| Aspekt | Deployment replicas:0 | K8s Job |
|--------|----------------------|---------|
| **Životní cyklus** | Scale up → idle → scale down | Spustí se → dokončí → smaže se |
| **Izolace** | Shared pod, musí řešit concurrency | Každý task = vlastní pod |
| **Env variables** | Musí se dynamicky měnit | Nastaví se při vytvoření |
| **Cleanup** | Manuální scale-down logika | `ttlSecondsAfterFinished` |
| **Monitoring** | Musí sledovat idle stav | Job status = task status |
| **kRPC** | Vyžaduje persistent WebSocket | **Nepotřebuje** – viz 18.3 |
| **Paralelismus** | Omezený počtem replik | N jobů = N paralelních tasků |
| **Resource control** | Statické limity | Per-job limity |

**Klíčový rozdíl:** Přechodem na Python orchestrator odpadá potřeba kRPC WebSocket. Orchestrator komunikuje s coding agenty jinak.

### 18.3 Komunikace: Jak Python orchestrator spouští coding agenty

Aktuálně Kotlin server volá coding agenty přes **kRPC WebSocket** (`ICodingClient.execute()`). To vyžaduje stálé spojení = Deployment.

V nové architektuře Python orchestrator **nevolá agenty přes RPC**. Místo toho:

```
Python Orchestrator                    K8s Job (coding agent)
─────────────────                     ─────────────────────
1. Server má workspace připravený:
   - codebase na shared PVC
   - orchestrator zapíše instrukce
   - zapíše .jervis/kb-context.md
   - zapíše .claude/mcp.json (Claude)
   - zapíše CLAUDE.md (Claude)

2. Vytvoří K8s Job:                   3. Job se spustí:
   kubectl create job                    - cd do workspace
   --env TASK_ID=xxx                     - vytvoří branch (task/{id})
   --env CLIENT_ID=yyy                   - spustí CLI (aider/claude/...)
   --env PROJECT_ID=zzz                  - pracuje na existující codebase
   --env WORKSPACE=/data/project         - (Claude: MCP pro KB za běhu)
                                         - agent sám rozhodne o commit
                                         - zapíše result.json
                                         - **agent se ukončí sám**

4. Sleduje průběh:                    5. Job skončí:
   + streamuje log:                      - přirozený exit agenta
     kubectl logs -f                     - exit code 0 = úspěch
   + čte result.json po skončení         - activeDeadlineSeconds
                                           jako safety timeout
                                         - TTL auto-cleanup podu
```

**Klíčové principy:**
- Workspace je **připravený serverem** – codebase už na disku existuje
- Agent pracuje přímo v existujícím adresáři, vytvoří si branch
- **Commit je rozhodnutí agenta**, ne entrypointu – někteří agenti (Aider, Claude) commitují sami
- **Ukončení řídí agent** – když dodělá, skončí (exit 0). `activeDeadlineSeconds` je jen pojistka
- Výsledek = stav branche + result.json na shared PVC

### 18.4 Workspace setup – co orchestrator připraví před Job spuštěním

Codebase je **již připravena serverem** na shared PVC. Orchestrator pouze doplní
instrukce, KB kontext a agent-specifickou konfiguraci do existujícího workspace.

```python
# Python orchestrator – workspace_manager.py

import json
from pathlib import Path

DATA_ROOT = Path("/opt/jervis/data")

async def prepare_workspace(
    task_id: str,
    client_id: str,
    project_id: str | None,
    project_path: str,          # e.g. "clients/acme/web-app" – již existuje na PVC
    instructions: str,
    files: list[str],
    agent_type: str,            # "aider" | "openhands" | "claude" | "junie"
    kb_context: str | None,     # pre-fetched KB kontext
) -> Path:
    """Doplní instrukce a kontext do existujícího workspace."""

    workspace = DATA_ROOT / project_path
    # Workspace musí existovat – server ho připravil
    assert workspace.exists(), f"Workspace {workspace} not found on PVC"

    # 1. Zapsat instrukce a metadata pro agenta
    jervis_dir = workspace / ".jervis"
    jervis_dir.mkdir(exist_ok=True)

    (jervis_dir / "instructions.md").write_text(instructions)
    (jervis_dir / "task.json").write_text(json.dumps({
        "taskId": task_id,
        "clientId": client_id,
        "projectId": project_id,
        "agentType": agent_type,
        "files": files,
    }))

    # 2. KB kontext – PRE-FETCH (funguje pro VŠECHNY agenty)
    if kb_context:
        (jervis_dir / "kb-context.md").write_text(kb_context)

    # 3. Agent-specifická konfigurace
    if agent_type == "claude":
        _setup_claude_workspace(workspace, client_id, project_id, kb_context)
    elif agent_type == "aider":
        _setup_aider_workspace(workspace, files, kb_context)

    return workspace


def _setup_claude_workspace(
    workspace: Path,
    client_id: str,
    project_id: str | None,
    kb_context: str | None,
):
    """Claude Code: MCP config + CLAUDE.md"""

    # MCP pro runtime KB přístup
    claude_dir = workspace / ".claude"
    claude_dir.mkdir(exist_ok=True)

    mcp_config = {
        "mcpServers": {
            "jervis-kb": {
                "command": "python",
                "args": ["/opt/jervis/mcp/kb-server.py"],
                "env": {
                    "CLIENT_ID": client_id,
                    "PROJECT_ID": project_id or "",
                    "KB_URL": "http://jervis-knowledgebase:8100"
                }
            }
        }
    }
    (claude_dir / "mcp.json").write_text(json.dumps(mcp_config, indent=2))

    # CLAUDE.md s projekt kontextem
    claude_md_parts = [
        "# Project Context (auto-generated by Jervis)",
        "",
        "## Instructions",
        f"Read `.jervis/instructions.md` for your task.",
        "",
        "## Knowledge Base",
        "You have access to the `jervis-kb` MCP server with these tools:",
        "- `kb_search(query)` – hybrid search (RAG + graph)",
        "- `kb_traverse(start_node)` – graph traversal",
        "- `kb_graph_search(query)` – search graph nodes",
        "- `kb_store(content, kind)` – store findings (use sparingly)",
        "",
        "Use KB to look up coding conventions, architecture decisions,",
        "and previous findings before making changes.",
    ]

    if kb_context:
        claude_md_parts.extend([
            "",
            "## Pre-fetched KB Context",
            kb_context,
        ])

    (workspace / "CLAUDE.md").write_text("\n".join(claude_md_parts))


def _setup_aider_workspace(
    workspace: Path,
    files: list[str],
    kb_context: str | None,
):
    """Aider: .aider.conf.yml + kontext soubory"""

    config_lines = ["yes: true"]  # --yes mode

    # Aider --read flag pro read-only kontext soubory
    if kb_context:
        kb_file = workspace / ".jervis" / "kb-context.md"
        config_lines.append(f"read: [{kb_file}]")

    (workspace / ".aider.conf.yml").write_text("\n".join(config_lines))
```

### 18.5 K8s Job šablona

```yaml
# Template pro coding agent Job – generuje Python orchestrator
apiVersion: batch/v1
kind: Job
metadata:
  name: jervis-{{agentType}}-{{taskId}}
  namespace: jervis
  labels:
    app: jervis-coding-agent
    agent-type: "{{agentType}}"    # aider | openhands | claude | junie
    task-id: "{{taskId}}"
    client-id: "{{clientId}}"
spec:
  ttlSecondsAfterFinished: 300     # Auto-cleanup podu po 5 minutách
  backoffLimit: 0                   # Bez retry – orchestrator řeší escalation
  activeDeadlineSeconds: 2700       # Safety timeout 45 min (agent by měl skončit dřív)
  template:
    metadata:
      labels:
        app: jervis-coding-agent
        agent-type: "{{agentType}}"
        task-id: "{{taskId}}"
    spec:
      restartPolicy: Never
      containers:
        - name: agent
          image: "registry.damek-soft.eu/jandamek/jervis-{{agentType}}:latest"
          # Entrypoint se mění – místo kRPC serveru spustí agenta rovnou
          command: ["/opt/jervis/entrypoint-job.sh"]
          env:
            - name: TASK_ID
              value: "{{taskId}}"
            - name: CLIENT_ID
              value: "{{clientId}}"
            - name: PROJECT_ID
              value: "{{projectId}}"
            - name: WORKSPACE
              value: "/opt/jervis/data/{{projectPath}}"   # Existující codebase
            - name: AGENT_TYPE
              value: "{{agentType}}"
            # Auth – z K8s secrets
            - name: ANTHROPIC_API_KEY
              valueFrom:
                secretKeyRef:
                  name: jervis-secrets
                  key: ANTHROPIC_API_KEY
                  optional: true
            - name: CLAUDE_CODE_OAUTH_TOKEN
              valueFrom:
                secretKeyRef:
                  name: jervis-secrets
                  key: CLAUDE_CODE_OAUTH_TOKEN
                  optional: true
          resources:
            requests:
              memory: "256Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
          volumeMounts:
            - name: jervis-data
              mountPath: /opt/jervis/data
      volumes:
        - name: jervis-data
          persistentVolumeClaim:
            claimName: jervis-data-pvc
```

### 18.6 Řízení agenta – centrální orchestrator, ne autonomní agent

#### Klíčový princip: Orchestrator je mozek, agent je ruka

```
❌ ŠPATNĚ – autonomní agent:
  orchestrator → spustí agenta → agent sám rozhoduje co dělat
  → agent commituje, pushuje, vytváří branch
  → orchestrator se dozví až po skončení

✅ SPRÁVNĚ – centrálně řízený agent:
  orchestrator → zadá agentovi krok (coding / analýza / plán)
  → agent provede a vrátí výsledek
  → orchestrator vyhodnotí výsledek
  → orchestrator rozhodne o dalším kroku
  → git operace dělá ORCHESTRATOR (ne coding agent)
```

Orchestrator je **mozek** – rozhoduje co se bude dělat, v jakém pořadí, jestli
commitovat, kdy pushovat, a jestli potřebuje schválení uživatele.

Agent je **ruka** – vykonává konkrétní kroky. Ale orchestrator ho může použít
i pro **přemýšlení**:

```
Orchestrator využívá agenty pro:

  ANALÝZA:   "Analyzuj tento kód, najdi problémy"
             → agent vrátí analýzu, orchestrator rozhodne co s tím

  PLÁNOVÁNÍ: "Navrhni jak implementovat tuto feature"
             → agent vrátí plán kroků, orchestrator ho schválí/upraví

  REVIEW:    "Zkontroluj tento diff, je to ok?"
             → agent vrátí review, orchestrator rozhodne jestli merge

  CODING:    "Uprav soubor X takto"
             → agent udělá změny, orchestrator zkontroluje a commitne

  VÝZKUM:    "Jak funguje modul Y? Jaké má závislosti?"
             → agent prozkoumá, orchestrator použije info pro plán
```

Klíčové je, že **výstup agenta jde vždy zpět do orchestratoru**, který
rozhoduje o dalším kroku. Agent nikdy neprovádí nevratné akce (git push,
deploy, smazání) bez rozhodnutí orchestratoru a povolení uživatele.

#### Proč agent nesmí řídit sám:

| Problém autonomního agenta | Řešení centrálním řízením |
|---------------------------|--------------------------|
| Agent commitne špatný kód | Orchestrator zkontroluje diff PŘED commitem |
| Agent pushne bez povolení | Push dělá orchestrator po approval |
| Agent zvolí špatnou strategii | Orchestrator řídí plán kroků |
| Různí agenti mají různé chování | Jednotný workflow pro všechny |
| Pravidla klienta/projektu se ignorují | Orchestrator zná pravidla a vynucuje je |
| Nelze přerušit uprostřed | Orchestrator může pozastavit mezi kroky |

#### Pravidla klienta a projektu:

Orchestrator načte pravidla z nastavení klienta/projektu a řídí se jimi:

```python
# Příklad pravidel (z DB – nastavení klienta/projektu)
class ProjectRules:
    branch_naming: str          # "task/{taskId}" nebo "feature/{description}"
    commit_prefix: str          # "task({taskId}):" nebo "[JIRA-123]"
    require_review: bool        # Vyžadovat ReviewerAgent před commitem?
    require_tests: bool         # Spustit testy před commitem?
    require_approval_commit: bool   # Schválení uživatelem před commitem?
    require_approval_push: bool     # Schválení uživatelem před pushem?
    allowed_branches: list[str]     # Kam smí pushovat (["task/*", "fix/*"])
    forbidden_files: list[str]      # Soubory které nesmí měnit (["*.env", "secrets/*"])
    max_changed_files: int          # Max počet změněných souborů v jednom kroku
    auto_push: bool                 # Automaticky pushovat po approval? (default: false)
```

### 18.7 Orchestrator workflow – krok po kroku

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Python Orchestrator (LangGraph)                   │
│                                                                     │
│  StateGraph:                                                        │
│                                                                     │
│  [PLAN] → [EXECUTE_STEP] → [EVALUATE] → [GIT_OPERATIONS] → [NEXT] │
│              │                  │              │                     │
│              │ spustí Job       │ kontrola     │ branch/commit/push  │
│              │ (coding agent)   │ diff+testy   │ (orchestrator!)     │
│              │                  │              │                     │
│              │              approval?      approval?                 │
│              │              (review)       (commit/push)             │
│              ▼                  ▼              ▼                     │
│         K8s Job            ReviewerAgent   User Approval            │
│         (agent)            (sub-agent)     (UI / chat)              │
└─────────────────────────────────────────────────────────────────────┘
```

```python
# Python orchestrator – orchestrated_coding.py

class CodingWorkflow:
    """Centrálně řízený coding workflow – agent je jen vykonavatel."""

    def __init__(self, rules: ProjectRules, kb_client, k8s_runner):
        self.rules = rules
        self.kb = kb_client
        self.k8s = k8s_runner

    async def execute_task(self, task: CodingTask) -> TaskResult:
        """Hlavní flow – orchestrator řídí každý krok."""

        # 1. PLAN – rozděl task na kroky
        steps = await self.plan_steps(task)

        # 2. PREPARE – vytvoř branch (orchestrator, ne agent!)
        branch = self.rules.branch_naming.format(taskId=task.id)
        await self.git_create_branch(task.workspace, branch)

        results = []
        for step in steps:
            # 3. EXECUTE – agent provede JEN tento krok
            agent_result = await self.execute_step(task, step)

            # 4. EVALUATE – orchestrator zkontroluje výsledek
            evaluation = await self.evaluate_result(task, step, agent_result)

            if not evaluation.acceptable:
                # Retry s jiným agentem nebo eskaluj
                agent_result = await self.handle_failure(task, step, evaluation)

            results.append(agent_result)

        # 5. GIT – orchestrator řídí git operace
        await self.git_operations(task, branch, results)

        return TaskResult(steps=results, branch=branch)

    async def execute_step(self, task: CodingTask, step: CodingStep):
        """Spustí coding agenta na JEDEN konkrétní krok."""

        # Pre-fetch KB kontext pro tento krok
        kb_context = await self.kb.prefetch(step.description, task.client_id, task.project_id)

        # Připrav workspace
        await self.prepare_workspace(task, step, kb_context)

        # Spusť agenta jako K8s Job
        # Agent dostane KONKRÉTNÍ instrukce – ne celý task!
        return await self.k8s.run_coding_agent(
            task_id=f"{task.id}-step-{step.index}",
            agent_type=step.agent_type,
            workspace_path=task.workspace,
            instructions=step.instructions,     # Jen tento krok!
            on_log_line=self.stream_to_ui,
        )

    async def evaluate_result(self, task, step, result):
        """Orchestrator vyhodnotí co agent udělal."""

        # Zjisti co se změnilo
        diff = await self.git_diff(task.workspace)

        checks = []

        # Kontrola zakázaných souborů
        changed_files = self.parse_changed_files(diff)
        forbidden = [f for f in changed_files if self.matches_forbidden(f)]
        if forbidden:
            checks.append(f"BLOCKED: Changed forbidden files: {forbidden}")

        # Kontrola počtu souborů
        if len(changed_files) > self.rules.max_changed_files:
            checks.append(f"WARNING: Changed {len(changed_files)} files (max {self.rules.max_changed_files})")

        # Review pokud pravidla vyžadují
        if self.rules.require_review:
            review = await self.run_reviewer(diff, step.description)
            checks.append(f"REVIEW: {review.verdict} – {review.summary}")

        # Testy pokud pravidla vyžadují
        if self.rules.require_tests:
            test_result = await self.run_tests(task.workspace)
            checks.append(f"TESTS: {'PASS' if test_result.passed else 'FAIL'}")

        return Evaluation(
            acceptable=all(not c.startswith("BLOCKED") for c in checks),
            checks=checks,
            diff=diff,
        )

    async def git_operations(self, task, branch, results):
        """Orchestrator řídí VŠECHNY git operace."""

        diff = await self.git_diff(task.workspace)
        if not diff:
            return  # Žádné změny

        # COMMIT – orchestrator rozhodne
        if self.rules.require_approval_commit:
            approved = await self.request_approval(
                task, action="commit",
                description=f"Commit {len(results)} changes to branch {branch}",
                diff=diff,
            )
            if not approved:
                return  # Uživatel zamítl

        commit_msg = self.format_commit_message(task, results)
        await self.git_commit(task.workspace, commit_msg)

        # PUSH – orchestrator rozhodne
        if self.rules.auto_push or self.rules.require_approval_push:
            if self.rules.require_approval_push:
                approved = await self.request_approval(
                    task, action="push",
                    description=f"Push branch {branch} to remote",
                )
                if not approved:
                    return  # Uživatel zamítl – commit zůstane lokální

            await self.git_push(task.workspace, branch)

    def format_commit_message(self, task, results):
        """Commit message podle pravidel projektu."""
        prefix = self.rules.commit_prefix.format(taskId=task.id)
        summary = results[-1].summary if results else "automated changes"
        return f"{prefix} {summary}"
```

### 18.8 Entrypoint – agent je tupý vykonavatel

Agent dostane **konkrétní krok**, provede ho a skončí. Žádné git operace,
žádné rozhodování o strategii. Git řídí orchestrator.

```bash
#!/bin/bash
# /opt/jervis/entrypoint-job.sh
# Agent je VYKONAVATEL – provede zadaný krok a skončí
# Git operace, commit, push → to řídí ORCHESTRATOR
set -euo pipefail

WORKSPACE="${WORKSPACE:?WORKSPACE env is required}"
TASK_ID="${TASK_ID:?TASK_ID env is required}"
AGENT_TYPE="${AGENT_TYPE:?AGENT_TYPE env is required}"
RESULT_FILE="$WORKSPACE/.jervis/result.json"

cd "$WORKSPACE"

INSTRUCTIONS=$(cat .jervis/instructions.md)
TASK_JSON=$(cat .jervis/task.json)
FILES=$(echo "$TASK_JSON" | python3 -c "import sys,json; print(' '.join(json.load(sys.stdin).get('files',[])))")

write_result() {
    local success=$1 summary=$2
    cat > "$RESULT_FILE" <<RESULT_EOF
{
  "taskId": "$TASK_ID",
  "success": $success,
  "summary": "$(echo "$summary" | sed 's/"/\\"/g' | tr '\n' ' ')",
  "agentType": "$AGENT_TYPE",
  "timestamp": "$(date -Iseconds)"
}
RESULT_EOF
}

case "$AGENT_TYPE" in
  aider)
    # --no-auto-commits: Aider NESMÍ commitovat – to řídí orchestrator
    CMD="aider --yes --no-auto-commits --message \"$INSTRUCTIONS\""
    if [ -n "$FILES" ]; then CMD="$CMD $FILES"; fi
    if [ -f ".jervis/kb-context.md" ]; then CMD="$CMD --read .jervis/kb-context.md"; fi
    ;;
  openhands)
    CMD="python3 -m openhands.core.main --task \"$INSTRUCTIONS\" --max-iterations 10"
    ;;
  claude)
    # Claude Code: MCP pro KB, ale git operace ZAKÁZANÉ
    CMD="claude --dangerously-skip-permissions --output-format json"
    if [ -n "${CLAUDE_MODEL:-}" ]; then CMD="$CMD --model $CLAUDE_MODEL"; fi
    CMD="$CMD \"$INSTRUCTIONS\""
    ;;
  junie)
    CMD="junie \"$INSTRUCTIONS\""
    ;;
  *)
    write_result false "Unknown agent type: $AGENT_TYPE"
    exit 1
    ;;
esac

echo "=== JERVIS AGENT START: $AGENT_TYPE / $TASK_ID ==="

if eval "$CMD" 2>&1; then
    write_result true "Agent completed successfully."
else
    EXIT_CODE=$?
    write_result false "Agent exited with code $EXIT_CODE"
    exit $EXIT_CODE
fi

echo "=== JERVIS AGENT DONE: $AGENT_TYPE / $TASK_ID ==="
```

#### Blokování git operací u agentů:

| Agent | Jak zablokovat git | Poznámka |
|-------|-------------------|----------|
| **Aider** | `--no-auto-commits` | Aider má flag přímo na to |
| **Claude Code** | `CLAUDE.md` s pravidly: "NEVER run git commands" | + hooks/deny-list |
| **OpenHands** | Stávající `SecurityException` na git commands | Již implementováno v `CodingEngineServiceImpl.kt` |
| **Junie** | Konfigurace Junie CLI | Omezení přes nastavení |

Pro Claude Code orchestrator zapíše do `CLAUDE.md`:

```markdown
# STRICT RULES – auto-generated by Jervis

## FORBIDDEN ACTIONS
- NEVER run git commands (commit, push, branch, checkout, merge, rebase)
- NEVER modify files outside your task scope
- Git operations are managed by the orchestrator, not by you

## YOUR ROLE
- You are an executor. Follow the instructions exactly.
- Make the requested code changes and exit.
- Do NOT make additional changes beyond what is requested.
```

**Životní cyklus (centrálně řízený):**
```
Orchestrator:
  1. Načte pravidla klienta/projektu z DB
  2. Naplánuje kroky (PlannerAgent)
  3. Vytvoří branch (orchestrator, ne agent!)
     │
     ├─ Pro každý krok:
     │   4. Zapíše instrukce do .jervis/instructions.md
     │   5. Spustí K8s Job (coding agent)
     │   6. Agent provede KROK a skončí (žádný git!)
     │   7. Orchestrator zkontroluje diff
     │   8. Pokud require_review → ReviewerAgent
     │   9. Pokud fail → retry s jiným agentem
     │
  10. Orchestrator udělá git add + commit
      → pokud require_approval_commit → čeká na uživatele
  11. Orchestrator udělá git push
      → pokud require_approval_push → čeká na uživatele
  12. Cleanup, reportuje výsledek do UI
```

async def run_coding_agent(
    task_id: str,
    agent_type: str,
    client_id: str,
    project_id: str | None,
    workspace_path: str,
    on_log_line: callable = None,     # streaming callback
) -> dict:
    """Spustí coding agent jako K8s Job a sleduje průběh."""

    # 1. Kontrola limitu souběžných agentů
    running = _count_running_jobs(agent_type)
    if running >= MAX_CONCURRENT.get(agent_type, 1):
        raise AgentCapacityError(
            f"{agent_type} has {running}/{MAX_CONCURRENT[agent_type]} running jobs. "
            f"Wait or increase limit in Settings."
        )

    # 2. Vytvoř Job
    job_name = f"jervis-{agent_type}-{task_id[:8]}"
    job = _build_job_manifest(
        job_name=job_name,
        agent_type=agent_type,
        task_id=task_id,
        client_id=client_id,
        project_id=project_id or "",
        workspace_path=workspace_path,
    )

    batch_v1.create_namespaced_job(namespace="jervis", body=job)

    # 3. Sleduj logy (streaming do UI)
    log_task = asyncio.create_task(
        _stream_job_logs(job_name, on_log_line)
    )

    # 4. Čekej na dokončení
    result = await _wait_for_job(job_name, timeout_seconds=2700)

    log_task.cancel()

    # 5. Přečti result.json z workspace
    result_file = Path(workspace_path) / ".jervis" / "result.json"
    if result_file.exists():
        return json.loads(result_file.read_text())
    else:
        return {
            "taskId": task_id,
            "success": result["succeeded"],
            "summary": f"Job finished with status: {result['status']}",
            "agentType": agent_type,
        }


def _count_running_jobs(agent_type: str) -> int:
    """Spočítá aktivní Jobs pro daný typ agenta."""
    jobs = batch_v1.list_namespaced_job(
        namespace="jervis",
        label_selector=f"agent-type={agent_type},app=jervis-coding-agent"
    )
    return sum(
        1 for j in jobs.items
        if j.status.active and j.status.active > 0
    )


async def _stream_job_logs(job_name: str, callback: callable):
    """Streamuje logy z Job podu do UI přes callback."""
    # Počkat až pod existuje
    for _ in range(60):
        pods = core_v1.list_namespaced_pod(
            namespace="jervis",
            label_selector=f"job-name={job_name}"
        )
        if pods.items:
            pod_name = pods.items[0].metadata.name
            # Počkat na Running stav
            if pods.items[0].status.phase in ("Running", "Succeeded", "Failed"):
                break
        await asyncio.sleep(2)
    else:
        return  # Pod se nespustil

    # Stream logy
    w = watch.Watch()
    try:
        for line in w.stream(
            core_v1.read_namespaced_pod_log,
            name=pod_name,
            namespace="jervis",
            follow=True,
        ):
            if callback:
                await callback(line)
    except Exception:
        pass  # Pod skončil


async def _wait_for_job(job_name: str, timeout_seconds: int = 2700) -> dict:
    """Čeká na dokončení K8s Job."""
    deadline = asyncio.get_event_loop().time() + timeout_seconds

    while asyncio.get_event_loop().time() < deadline:
        job = batch_v1.read_namespaced_job(name=job_name, namespace="jervis")

        if job.status.succeeded and job.status.succeeded > 0:
            return {"status": "succeeded", "succeeded": True}

        if job.status.failed and job.status.failed > 0:
            return {"status": "failed", "succeeded": False}

        await asyncio.sleep(5)

    return {"status": "timeout", "succeeded": False}


def _build_job_manifest(
    job_name: str,
    agent_type: str,
    task_id: str,
    client_id: str,
    project_id: str,
    workspace_path: str,
) -> client.V1Job:
    """Sestaví K8s Job manifest."""
    return client.V1Job(
        metadata=client.V1ObjectMeta(
            name=job_name,
            namespace="jervis",
            labels={
                "app": "jervis-coding-agent",
                "agent-type": agent_type,
                "task-id": task_id,
            },
        ),
        spec=client.V1JobSpec(
            ttl_seconds_after_finished=300,
            backoff_limit=0,
            active_deadline_seconds=2700,
            template=client.V1PodTemplateSpec(
                metadata=client.V1ObjectMeta(labels={
                    "app": "jervis-coding-agent",
                    "agent-type": agent_type,
                    "task-id": task_id,
                }),
                spec=client.V1PodSpec(
                    restart_policy="Never",
                    containers=[
                        client.V1Container(
                            name="agent",
                            image=f"registry.damek-soft.eu/jandamek/jervis-{agent_type}:latest",
                            command=["/opt/jervis/entrypoint-job.sh"],
                            env=[
                                client.V1EnvVar(name="TASK_ID", value=task_id),
                                client.V1EnvVar(name="CLIENT_ID", value=client_id),
                                client.V1EnvVar(name="PROJECT_ID", value=project_id),
                                client.V1EnvVar(name="WORKSPACE", value=workspace_path),
                                client.V1EnvVar(name="AGENT_TYPE", value=agent_type),
                                client.V1EnvVar(
                                    name="ANTHROPIC_API_KEY",
                                    value_from=client.V1EnvVarSource(
                                        secret_key_ref=client.V1SecretKeySelector(
                                            name="jervis-secrets",
                                            key="ANTHROPIC_API_KEY",
                                            optional=True,
                                        )
                                    ),
                                ),
                            ],
                            resources=client.V1ResourceRequirements(
                                requests={"memory": "256Mi", "cpu": "250m"},
                                limits={"memory": "1Gi", "cpu": "1000m"},
                            ),
                            volume_mounts=[
                                client.V1VolumeMount(
                                    name="jervis-data",
                                    mount_path="/opt/jervis/data",
                                )
                            ],
                        )
                    ],
                    volumes=[
                        client.V1Volume(
                            name="jervis-data",
                            persistent_volume_claim=client.V1PersistentVolumeClaimVolumeSource(
                                claim_name="jervis-data-pvc",
                            ),
                        )
                    ],
                ),
            ),
        ),
    )
```

### 18.8 KB přístup – univerzální pro všechny agenty

```
┌───────────────────────────────────────────────────────────────────┐
│                  KB Access Strategy per Agent                      │
├──────────┬────────────────────────┬───────────────────────────────┤
│ Agent    │ Layer 1: PRE-FETCH     │ Layer 2: RUNTIME MCP          │
│          │ (orchestrator dělá     │ (agent sám volá KB            │
│          │  před Job spuštěním)   │  za běhu)                     │
├──────────┼────────────────────────┼───────────────────────────────┤
│ Claude   │ ✅ .jervis/kb-context  │ ✅ .claude/mcp.json           │
│ Code     │    + CLAUDE.md         │    nativní MCP podpora        │
│          │                        │    → plný runtime přístup     │
├──────────┼────────────────────────┼───────────────────────────────┤
│ Aider    │ ✅ .jervis/kb-context  │ ❌ nemá tool interface        │
│          │    + --read flag       │    Workaround: kb-context     │
│          │    v .aider.conf.yml   │    jako read-only soubor      │
├──────────┼────────────────────────┼───────────────────────────────┤
│ OpenHands│ ✅ .jervis/kb-context  │ ⚠️ možné přes custom action  │
│          │    v instrukcích       │    ale komplexní setup         │
│          │                        │    → pre-fetch stačí          │
├──────────┼────────────────────────┼───────────────────────────────┤
│ Junie    │ ✅ .jervis/kb-context  │ ❌ uzavřený binary            │
│          │    v instrukcích       │    → pre-fetch only           │
└──────────┴────────────────────────┴───────────────────────────────┘
```

#### Pre-fetch flow (všichni agenti):

```python
# Python orchestrator – kb_prefetch.py

async def prefetch_kb_context(
    task_description: str,
    client_id: str,
    project_id: str | None,
    files: list[str],
) -> str:
    """Dotáže KB a vrátí kontext relevantní pro task."""

    kb_url = "http://jervis-knowledgebase:8100"
    sections = []

    async with httpx.AsyncClient(timeout=15) as http:

        # 1. Hledej relevantní znalosti pro task
        resp = await http.post(f"{kb_url}/retrieve", json={
            "query": task_description,
            "clientId": client_id,
            "projectId": project_id,
            "maxResults": 5,
            "minConfidence": 0.7,
            "expandGraph": True,
        })
        results = resp.json().get("items", [])
        if results:
            sections.append("## Relevant Knowledge")
            for item in results:
                source = item.get("sourceUrn", "?")
                content = item.get("content", "")[:300]
                sections.append(f"- **{source}**: {content}")

        # 2. Hledej coding conventions pro klienta
        resp = await http.post(f"{kb_url}/retrieve/simple", json={
            "query": "coding conventions style guide rules",
            "clientId": client_id,
            "projectId": "",  # Client-level only
            "maxResults": 3,
        })
        conventions = resp.json().get("items", [])
        if conventions:
            sections.append("\n## Coding Conventions")
            for item in conventions:
                sections.append(f"- {item.get('content', '')[:200]}")

        # 3. Hledej architecture decisions pro projekt
        if project_id:
            resp = await http.post(f"{kb_url}/retrieve/simple", json={
                "query": "architecture decisions design patterns",
                "clientId": client_id,
                "projectId": project_id,
                "maxResults": 3,
            })
            arch = resp.json().get("items", [])
            if arch:
                sections.append("\n## Architecture Decisions")
                for item in arch:
                    sections.append(f"- {item.get('content', '')[:200]}")

        # 4. Pokud máme konkrétní soubory, hledej related znalosti
        for file_path in files[:3]:  # Max 3 souborů
            resp = await http.post(f"{kb_url}/retrieve/simple", json={
                "query": f"file {file_path} implementation notes",
                "clientId": client_id,
                "projectId": project_id,
                "maxResults": 2,
            })
            file_results = resp.json().get("items", [])
            if file_results:
                sections.append(f"\n## Notes for `{file_path}`")
                for item in file_results:
                    sections.append(f"- {item.get('content', '')[:200]}")

    return "\n".join(sections) if sections else ""
```

### 18.9 Kompletní flow – od tasku po výsledek

```
1. Uživatel vytvoří task v UI (nebo orchestrator rozhodne o coding tasku)
   │
2. Python orchestrator rozhodne o agentovi (sekce 17.7):
   │  SIMPLE → Aider, MEDIUM → Claude, COMPLEX → OpenHands, CRITICAL → Junie
   │
3. Orchestrator pre-fetchne KB kontext:
   │  kb_prefetch.py → kb-context.md
   │
4. Orchestrator doplní workspace (codebase už je na PVC):
   │  workspace_manager.py:
   │  ├── .jervis/instructions.md    (vždy)
   │  ├── .jervis/task.json          (vždy)
   │  ├── .jervis/kb-context.md      (pokud KB vrátila výsledky)
   │  ├── .claude/mcp.json           (Claude only – runtime KB)
   │  ├── CLAUDE.md                   (Claude only – projekt kontext)
   │  └── .aider.conf.yml            (Aider only – read KB soubor)
   │
5. Orchestrator vytvoří K8s Job:
   │  job_runner.py → K8s API create job
   │
6. Pro každý krok plánu:
   │  a) Orchestrator zapíše instrukce pro TENTO krok
   │  b) Spustí K8s Job (coding agent)
   │  │   ├── Agent provede KOD změny (žádný git!)
   │  │   ├── Claude: MCP tools pro KB za běhu
   │  │   ├── Aider: --no-auto-commits + --read kb-context.md
   │  │   └── Agent skončí → result.json
   │  c) Orchestrator zkontroluje diff
   │  d) Pokud require_review → ReviewerAgent
   │  e) Pokud fail → retry s jiným agentem
   │
7. Orchestrator sleduje průběh každého Job:
   │  ├── kubectl logs -f → SSE → Kotlin server → UI chat
   │  └── activeDeadlineSeconds jako safety timeout
   │
8. Po dokončení všech kroků – GIT operace (orchestrator!):
   │  ├── git add + commit (podle pravidel projektu)
   │  │   └── pokud require_approval_commit → čeká na uživatele
   │  ├── git push
   │  │   └── pokud require_approval_push → čeká na uživatele
   │  └── cleanup .jervis/ soubory
   │
9. Orchestrator reportuje výsledek:
   │  ├── Kotlin server → UI (summary, diff, status)
   │  └── Pokud fail → escalation (jiný agent / user approval)
   │
10. (Volitelně) ReviewerAgent zkontroluje finální diff
    └── Approval / merge / další iterace
```

### 18.10 Migrace Docker images – dual-mode support

Během přechodu budou Docker images podporovat **dva režimy**:

```dockerfile
# Příklad: service-claude/Dockerfile (dual-mode)
FROM eclipse-temurin:21-jre-jammy
# ... install Node.js, Claude CLI, Python, MCP server ...

# Oba entrypointy v image
COPY entrypoint-rpc.sh /opt/jervis/entrypoint-rpc.sh    # Stávající kRPC server
COPY entrypoint-job.sh /opt/jervis/entrypoint-job.sh    # Nový Job mode
COPY mcp/kb-server.py  /opt/jervis/mcp/kb-server.py     # KB MCP server

# Default = RPC (zpětná kompatibilita)
ENTRYPOINT ["/opt/jervis/entrypoint-rpc.sh"]
# Job mode: přepíše se command v K8s Job spec
```

**Migrační plán:**
1. **Fáze 1**: Přidat `entrypoint-job.sh` + MCP server do všech images (obojí funguje)
2. **Fáze 2**: Python orchestrator spouští Jobs, Kotlin server stále má Deploymenty jako fallback
3. **Fáze 3**: Odstranit kRPC Deploymenty, jen Jobs

### 18.11 Revize K8s přehledu (finální stav)

| Služba | K8s typ | Stav | Poznámka |
|--------|---------|------|----------|
| **Kotlin Server** | Deployment | Stálý (1 replika) | API gateway, UI WebSocket, K8s Job launcher |
| **Python Orchestrator** | Deployment | Stálý (1 replika) | LangGraph, streaming SSE |
| **Knowledge Base** | Deployment | Stálý (1 replika) | FastAPI, Weaviate, ArangoDB |
| **Aider** | **Job** | On-demand | Max 3 souběžné (nastavitelné) |
| **OpenHands** | **Job** | On-demand | Max 2 souběžné |
| **Claude Code** | **Job** | On-demand | Max 2 souběžné, MCP pro KB |
| **Junie** | **Job** | On-demand | Max 1 souběžný (drahý) |
| **Joern** | Deployment | Stálý | Code analysis |
| **Tika** | Deployment | Stálý | Document parsing |
| **Whisper** | Deployment | Stálý | Speech-to-text |
| **GitHub/GitLab/Atlassian** | Deployment | Stálý | Provider API clients |

**Resource model:**
- Stálé služby: ~4 GB RAM (server + orchestrator + KB + support)
- Coding Jobs: 256MB-1GB per Job, 0 v idle
- GPU: Jen pro Ollama (P40) – coding agenti nepoužívají GPU
