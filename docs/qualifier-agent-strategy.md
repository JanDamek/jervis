# Jervis Qualification Agent - Map-Reduce Strategy

## 📋 Přehled

Qualification Agent transformuje nestrukturovaný vstup (Email, Jira, Confluence) na strukturovaná data pomocí **5-fázového Map-Reduce procesu**.

---

## 🔄 Workflow (5 Fází)

### **Fáze 1: SPLIT (Sémantické dělení)**

**Cíl:** Rozdělit dlouhý dokument na logické, atomické části (chunks).

**Proces:**
1. Agent dostane surový vstup
2. Identifikuje sémantické hranice (odstavce, sekce)
3. Zavolá `setProcessingPlan(chunks: List<String>)`

**Prompt:** `PROMPT_SEMANTIC_CHUNKER`
- Identity: Semantic Segmentation Expert
- Output: Tool call ONLY

---

### **Fáze 2: MAP (Zpracování částí)**

**Cíl:** Extrahovat znalosti z každého chunku nezávisle.

**Proces (Loop):**
```
FOR každý chunk:
  1. Agent dostane chunk ze state toolu
  2. Analyzuje obsah → extrahuje entity a vztahy
  3. Zavolá storeKnowledge(content, graphStructure, mainNodeKey)
  4. Tool vrátí: ✓ ChunkId: abc123, MainNode: topic::xyz
  5. State tool zaznamená: recordChunkResult(chunkId, mainNodeKey)
  6. Pokračuj na další chunk
```

**Prompt:** `PROMPT_ANALYZE_CHUNK`
- Identity: Knowledge Extraction Specialist
- Output: Tool call ONLY
- Context: "Focus ONLY on current chunk"

**Klíčové body:**
- Každý chunk vytvoří vlastní RAG obsah (vektor embedding)
- Každý chunk vytvoří vlastní graf (uzly + hrany)
- `mainNodeKey` identifikuje primární téma chunku

---

### **Fáze 3: REDUCE (Syntéza)**

**Cíl:** Spojit všechny chunky do jednoho celku.

**Proces:**
1. Agent dostane seznam všech `(chunkId, mainNodeKey)` párů
2. Vytvoří **Master Node**: `document::[correlationId]`
3. Propojí master node se všemi chunk nodes:
   - `document::123 -[CONTAINS]-> topic::auth`
   - `document::123 -[CONTAINS]-> topic::api`
4. Vytvoří globální shrnutí celého dokumentu
5. Zavolá `storeKnowledge` s master node

**Prompt:** `PROMPT_SUMMARIZER`
- Identity: Knowledge Graph Architect
- Output: Tool call ONLY
- Input: List of chunk IDs and main nodes

---

### **Fáze 4: TASK CREATION (Založení tasků)**

**Cíl:** Vytvořit follow-up tasky na základě obsahu.

**Proces:**
```
IF dokument obsahuje:
  - Safe URL → delegateLinkProcessing(url)
  - Budoucí událost → scheduleTask(content, scheduledDateTime, taskName)
  - User action → createUserTask(title, description, priority, dueDate)

Může zavolat VÍCE nástrojů najednou (paralelně)
```

**Prompt:** `PROMPT_TASK_CREATION`
- Identity: Task Delegation Expert
- Output: Tool calls OR nothing (pokud nejsou potřeba tasky)
- Safety: NEVER process action links (unsubscribe, confirm, delete)

**Příklady:**
- Email s meetingem zítra → `scheduleTask`
- Email s důležitým rozhodnutím → `createUserTask`
- Email s odkazem na dokumentaci → `delegateLinkProcessing`

---

### **Fáze 5: ROUTING (Dokončení)**

**Cíl:** Rozhodnout, co dál s tímto taskem.

**Možnosti:**
- **`DONE`**: Vše indexováno, žádná další akce není potřeba
  - Použij pro: Informační emaily, scheduled events, user tasks
- **`LIFT_UP`**: Vyžaduje pokročilou analýzu GPU agentem
  - Použij pro: Coding, bug fixing, technická analýza

**Prompt:** `PROMPT_ROUTING`
- Identity: Final Routing Decision Maker
- Output: Tool call `routeTask("DONE" | "LIFT_UP")`

**Následné akce:**
- `DONE` → Smaže PendingTask z fronty
- `LIFT_UP` → Přesune task do `DISPATCHED_GPU` fronty

---

## 🛠️ Technické detaily

### **Type-Safe Data Flow (Data Classes)**

```kotlin
// Phase 1 output: Chunk plan
data class ChunkPlan(
  val chunks: List<String>,
  val originalContent: String,
  val correlationId: String,
)

// Phase 2 state: Processing loop
data class ProcessingState(
  val chunks: List<String>,
  val processedResults: List<ChunkResult>,
  val currentIndex: Int,
  val originalContent: String,
  val correlationId: String,
) {
  fun hasMoreChunks(): Boolean = currentIndex < chunks.size
  fun nextChunk(): String? = chunks.getOrNull(currentIndex)
  fun withResult(result: ChunkResult): ProcessingState
}

// Phase 3 input: Synthesis context
data class SynthesisContext(
  val processedResults: List<ChunkResult>,
  val correlationId: String,
  val originalContent: String,
)

// Phase 5 input: Routing context
data class RoutingContext(
  val originalContent: String,
  val correlationId: String,
  val synthesisComplete: Boolean,
)
```

### **Subgraph Architecture**

```
MAIN GRAPH:
  Start → CreatePlan(ChunkPlan) → InitProcessing(ProcessingState) →
  [PROCESSING SUBGRAPH] → CreateSynthesis(SynthesisContext) →
  [SYNTHESIS SUBGRAPH] → [TASK CREATION SUBGRAPH] →
  CreateRouting(RoutingContext) → [ROUTING SUBGRAPH] → Finish

PROCESSING SUBGRAPH (Map Loop):
  Start → StoreState →
    ├─ [hasMore] → PrepareChunk → SendChunk → ExecuteStore → SendResult → RecordResult → StoreState (loop)
    └─ [!hasMore] → Finish
```

### **Tool Registry**

Agent má přístup k:
- `GraphRagTools`: `storeKnowledge(content, graphStructure, mainNodeKey)`
- `TaskTools`: `routeTask()`, `scheduleTask()`, `createUserTask()`, `delegateLinkProcessing()`

**Note:** State management je nyní řešeno pomocí type-safe data classes, ne pomocí state tool.

---

## 📊 Data Flow

```
INPUT: String (raw document)
  ↓
[PHASE 1: SPLIT]
  → ChunkPlan(chunks, originalContent, correlationId)
  ↓
[PHASE 2: MAP]
  → ProcessingState (loop through chunks)
    → FOR EACH chunk: storeKnowledge() → RAG + Graph
    → Update ProcessingState with ChunkResult
  → Final ProcessingState (all chunks processed)
  ↓
[PHASE 3: REDUCE]
  → SynthesisContext(processedResults, correlationId, originalContent)
  → storeKnowledge() → Master document node + CONTAINS edges
  → String (synthesis complete)
  ↓
[PHASE 4: TASK CREATION]
  → Analyze originalContent
  → Optional: scheduleTask | createUserTask | delegateLinkProcessing
  → String (tasks created or none)
  ↓
[PHASE 5: ROUTING]
  → RoutingContext(originalContent, correlationId, synthesisComplete)
  → routeTask("DONE" | "LIFT_UP")
  → String (routing complete)
```

**Type Safety:** Každá fáze má explicitní input/output typy, což eliminuje runtime chyby.

---

## 🎯 Klíčové vlastnosti

1. **Map-Reduce Pattern**: Fáze 2 (MAP) zpracovává chunky nezávisle, Fáze 3 (REDUCE) je spojí
2. **Type-Safe Data Flow**: Data classes pro každou fázi (ChunkPlan, ProcessingState, SynthesisContext, RoutingContext)
3. **Subgraph Loop**: Automatická iterace přes všechny části s type-safe state management
4. **No Global State**: Žádné state tools - data flow přes node types
5. **Fail-Safe**: Regex parsing tool results + fallback na "unknown"
6. **5 Distinct Phases**: Jasné oddělení odpovědností (Split → Map → Reduce → Tasks → Route)

---

## 🚀 Použití

```kotlin
val agent = koogQualifierAgent.create(pendingTask)
val result = agent.run(pendingTask.content)
// result: QualifierResult(completed = true)
```

---

## 📝 Poznámky

- **Model**: `qwen3-coder-tool:30b` (Ollama, CPU)
- **Max iterations**: Konfigurovatelné v `KoogProperties`
- **Logging**: Každá fáze má vlastní log statements
- **Error handling**: Try-catch v `run()` + detailed error logging
