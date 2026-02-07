# Orchestrator Agent – Kompletní analýza a plán vylepšení

**Datum:** 2026-02-07
**Autor:** Automatizovaná analýza
**Rozsah:** OrchestratorAgent, GoalExecutor, 11 sub-agentů, tools, models, koog integrace

---

## Obsah

1. [Současná architektura](#1-současná-architektura)
2. [Identifikované slabé místa](#2-identifikované-slabé-místa)
3. [Analýza Koog frameworku – limity a omezení](#3-analýza-koog-frameworku)
4. [Python microservice varianta – analýza](#4-python-microservice-varianta)
5. [Plán vylepšení – 3 varianty](#5-plán-vylepšení)
6. [Doporučená varianta a roadmapa](#6-doporučená-varianta-a-roadmapa)

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
