# Knowledge Base - Implementace a architektura

**Datum:** 2026-02-01
**Status:** Production-ready
**Kritičnost:** 🔴 KRITICKÁ INFRASTRUKTURA

---

## Přehled

Knowledge Base je **nejvýznamnější komponenta** celé Jervis aplikace. Agent nemůže fungovat bez kvalitně strukturovaných dat a vztahů. Tato služba zajišťuje:

1. **Ingestion** - ukládání znalostí do RAG (Weaviate) + Graph (ArangoDB)
2. **Normalization** - kanonizace node keys a aliasů
3. **Relationship extraction** - automatická detekce vztahů mezi entitami
4. **Graph construction** - vytváření strukturovaného grafu znalostí
5. **Retrieval** - hybridní vyhledávání (RAG + Graph traversal)

---

## Architektura

### Dvojitý storage model

```
┌─────────────────────────────────────────────────────────┐
│                    KNOWLEDGE BASE                        │
├─────────────────────────┬───────────────────────────────┤
│        RAG Store        │        Graph Store            │
│      (Weaviate)         │       (ArangoDB)              │
├─────────────────────────┼───────────────────────────────┤
│ • Vektorové embeddingy  │ • Vrcholy (entities)          │
│ • Sémantické vyhledání  │ • Hrany (relationships)       │
│ • Chunk storage         │ • Strukturovaná navigace      │
│ • Metadata              │ • Traversal queries           │
└─────────────────────────┴───────────────────────────────┘
           ↓                           ↓
    ┌───────────────────────────────────────┐
    │   Bidirectional linking:              │
    │   - Chunks → Graph nodes (graphRefs)  │
    │   - Graph nodes → Chunks (ragChunks)  │
    │   - Edges → Chunks (evidenceChunkIds) │
    └───────────────────────────────────────┘
```

### Flow: Ingest → Storage

```kotlin
IngestRequest
   ↓
1. Chunking (simple paragraph split)
   ↓
2. Extraction
   - extractNodes()        // Pattern: "type:id"
   - extractRelationships() // Formats: "from|edge|to", "from->edge->to", "from -[edge]-> to"
   ↓
3. Normalization
   - normalizeGraphRefs()   // Canonical form
   - resolveCanonicalGraphRef() // Alias resolution via MongoDB registry
   ↓
4. RAG Storage (Weaviate)
   - Embedding via EmbeddingGateway
   - Metadata: sourceUrn, clientId, kind, graphRefs, graphAreas
   ↓
5. Graph Storage (ArangoDB)
   - buildGraphPayload()    // Parse relationships, expand short-hand refs
   - persistGraph()         // Upsert nodes + edges with evidence
   ↓
IngestResult (success, summary, ingestedNodes[])
```

---

## Relationship Extraction

### Podporované formáty

Agent může posílat relationships v několika formátech:

#### 1. Pipe format (doporučeno)
```
from|edgeType|to
```
Příklad: `jira:TASK-123|MENTIONS|user:john`

#### 2. Arrow format
```
from->edgeType->to
```
Příklad: `file:Service.kt->MODIFIED_BY->commit:abc123`

#### 3. Bracket format (ArangoDB-like)
```
from -[edgeType]-> to
```
Příklad: `class:UserService -[CALLS]-> method:authenticate`

#### 4. Metadata block (embeded v content)
```markdown
relationships: [
  "jira:TASK-123|MENTIONS|user:john",
  "jira:TASK-123|AFFECTS|file:Service.kt",
  "commit:abc123|FIXES|jira:TASK-123"
]
```

### Short-hand expansion

Hlavní node může být zkráceně referencován v relationships:

```kotlin
mainNodeKey = "jira:TASK-123"
relationships = [
  "TASK-123|MENTIONS|user:john",  // Rozšíří se na: jira:TASK-123|MENTIONS|user:john
  "file:Service.kt|RELATED_TO|TASK-123"  // Rozšíří se na: file:Service.kt|RELATED_TO|jira:TASK-123
]
```

---

## Graph Payload Structure

### Příklad transformace

**Input:**
```kotlin
mainNodeKey = "jira:TASK-123"
relationships = [
  "TASK-123|MENTIONS|user:john",
  "TASK-123|AFFECTS|file:Service.kt",
  "user:john|ASSIGNED_TO|TASK-123"
]
```

**Output (GraphPayload):**
```kotlin
GraphPayload(
  allNodes = [
    "jira:task-123",      // Normalizováno (lowercase namespace)
    "user:john",
    "file:service.kt"
  ],
  rawTriples = [
    Triple("jira:task-123", "mentions", "user:john"),
    Triple("jira:task-123", "affects", "file:service.kt"),
    Triple("user:john", "assigned_to", "jira:task-123")
  ]
)
```

**Persistent v ArangoDB:**

Nodes:
```javascript
// c{clientId}_nodes collection
{
  "_key": "jira::task-123",
  "type": "jira_issue",
  "ragChunks": ["chunk-uuid-1", "chunk-uuid-2"]
}
{
  "_key": "user::john",
  "type": "user",
  "ragChunks": ["chunk-uuid-3"]
}
{
  "_key": "file::service.kt",
  "type": "file",
  "ragChunks": ["chunk-uuid-4", "chunk-uuid-5"]
}
```

Edges:
```javascript
// c{clientId}_edges collection
{
  "_key": "mentions::jira::task-123->user::john",
  "edgeType": "mentions",
  "_from": "c{clientId}_nodes/jira::task-123",
  "_to": "c{clientId}_nodes/user::john",
  "evidenceChunkIds": ["chunk-uuid-1"]  // ← Důkaz, že relationship existuje
}
{
  "_key": "affects::jira::task-123->file::service.kt",
  "edgeType": "affects",
  "_from": "c{clientId}_nodes/jira::task-123",
  "_to": "c{clientId}_nodes/file::service.kt",
  "evidenceChunkIds": ["chunk-uuid-1"]
}
```

---

## Evidence-based relationships

**KRITICKÁ VLASTNOST:** Každá hrana MUSÍ mít evidenci (chunk ID).

### Proč?

1. **Traceability** - Můžeme zpětně dohledat, odkud vztah pochází
2. **Verification** - Agent může zkontrolovat, zda relationship stále platí
3. **Confidence** - Více chunks = vyšší důvěra v relationship
4. **Explainability** - Můžeme ukázat uživateli konkrétní text, který vztah podporuje

### Příklad

```kotlin
// Agent indexuje Jira ticket
content = """
# TASK-123: Fix login bug

**Assignee:** John Doe

The issue affects UserService.kt authentication flow.
We need to modify the login() method.

relationships: [
  "TASK-123|ASSIGNED_TO|user:john",
  "TASK-123|AFFECTS|file:UserService.kt",
  "TASK-123|AFFECTS|method:UserService.login"
]
"""

// Po ingestu v ArangoDB:
edge {
  edgeType = "affects",
  from = "jira:task-123",
  to = "file:userservice.kt",
  evidenceChunkIds = ["chunk-uuid-abc"]  // ← Odkaz na chunk s tímto textem
}

// Agent později může:
val edge = graphDB.getEdge("jira:task-123", "affects", "file:userservice.kt")
val evidence = ragStore.getChunk(edge.evidenceChunkIds[0])
// → Vrátí: "The issue affects UserService.kt authentication flow."
```

---

## Normalization & Canonicalization

### Problém: Variabilní pojmenování

Agent může stejnou entitu referencovat různě:
- `user:John`, `user:john`, `User:John`
- `jira:TASK-123`, `JIRA:task-123`
- `order:order_530798957`, `order:530798957`

### Řešení: Multi-stage normalization

#### Stage 1: Format normalization (stable)
```kotlin
normalizeSingleGraphRef("User:John  Smith") → "user:john  smith"
// Pravidla:
// - Namespace (před ':') → lowercase
// - Whitespace → single space
// - Special chars → '_'
```

#### Stage 2: Canonicalization (semantic)
```kotlin
canonicalizeGraphRef("order:order_530798957") → "order:530798957"
// Pravidla:
// - Remove redundant namespace prefix in value
// - order:order_X → order:X
// - product:product_lego → product:lego
```

#### Stage 3: Alias resolution (per-client registry)
```kotlin
// MongoDB: graph_entity_registry collection
{
  clientId: "client-abc",
  aliasKey: "user:john",
  canonicalKey: "user:john.doe@example.com",
  area: "user",
  seenCount: 42,
  lastSeenAt: "2026-02-01T10:00:00Z"
}

// Při další ingestu:
resolveCanonicalGraphRef("user:john") → "user:john.doe@example.com"
// ✅ Všechny aliasy ukazují na stejný canonical key
```

### Cache strategie

```kotlin
// In-memory cache (ConcurrentHashMap)
graphRefCache["client-abc|user:john"] = "user:john.doe@example.com"

// Mutex per cache key (prevence race conditions)
graphRefLocks["client-abc|user:john"] = Mutex()
```

---

## Retrieval: Hybrid Search

### 1. RAG-first search
```kotlin
val embedding = embeddingGateway.callEmbedding(query)
val results = weaviateVectorStore.search(
  query = VectorQuery(embedding, filters = VectorFilters(clientId, projectId))
)
```

### 2. Graph expansion
```kotlin
// Seed nodes z chunk metadata
val seedNodes = results.flatMap { it.metadata["graphRefs"] }

// Traversal (2 hops)
seedNodes.forEach { seed ->
  graphDBService.traverse(clientId, seed, TraversalSpec(maxDepth = 2))
}
```

### 3. Evidence pack assembly
```kotlin
EvidencePack(
  items = [
    EvidenceItem(source = "RAG", content = "...", confidence = 0.92),
    EvidenceItem(source = "Graph", content = "...", confidence = 0.85)
  ],
  summary = "Found 5 RAG results and 12 related graph nodes."
)
```

---

## Integrace s Qualifier Agent

### Agent workflow

```kotlin
// 1. Agent zpracovává Jira ticket
QualifierAgent.process(pendingTask) {

  // 2. Extrahuje strukturu
  val extraction = extractJira(content)

  // 3. Indexuje do Knowledge Base
  knowledgeService.ingest(IngestRequest(
    clientId = task.clientId,
    sourceUrn = SourceUrn.jira(connectionId, issueKey),
    kind = "JIRA",
    content = buildString {
      append("# ${extraction.key}: ${extraction.summary}\n\n")
      append("**Status:** ${extraction.status}\n")
      append("**Assignee:** ${extraction.assignee}\n\n")
      append(extraction.description)

      // ← KLÍČOVÉ: Embedded relationships
      append("\n\nrelationships: [\n")
      append("  \"${extraction.key}|ASSIGNED_TO|user:${extraction.assignee}\",\n")
      append("  \"${extraction.key}|REPORTED_BY|user:${extraction.reporter}\",\n")
      if (extraction.epic != null) {
        append("  \"${extraction.key}|PART_OF|jira:${extraction.epic}\",\n")
      }
      append("]\n")
    }
  ))

  // 4. Verifikace
  val retrieved = knowledgeService.retrieve(RetrievalRequest(
    query = extraction.key,
    clientId = task.clientId
  ))

  // ✅ Zkontrolovat, že relationships jsou traversable
}
```

---

## Monitoring & Debugging

### Logování

```kotlin
logger.info { "INGEST: clientId=${request.clientId}, sourceUrn=${request.sourceUrn}, kind=${request.kind}" }
logger.info { "Ingest: split into ${chunks.size} chunks" }
logger.info { "STORE_KNOWLEDGE: success=${result.success}, nodes=${result.ingestedNodes.size}" }
logger.warn { "Failed to upsert node $key: ${result.warnings}" }
```

### Metriky

```kotlin
data class IngestResult(
  val success: Boolean,
  val summary: String,  // "Ingested 3 chunks from jira:TASK-123"
  val ingestedNodes: List<String>,  // ["jira:task-123", "user:john", ...]
  val error: String? = null
)
```

### Problémové situace

#### 1. Missing relationships
**Symptom:** Graph node existuje, ale žádné edges
**Příčina:** Agent neposlal relationships v content
**Fix:** Zkontrolovat prompt Qualifier Agenta

#### 2. Duplicate nodes
**Symptom:** `jira:TASK-123` a `jira:task-123` jako separátní nodes
**Příčina:** Chybná normalizace
**Fix:** Zkontrolovat `normalizeSingleGraphRef()`

#### 3. Broken edges
**Symptom:** Edge `_from` nebo `_to` neexistuje
**Příčina:** Node nebyl vytvořen před edge
**Fix:** `persistGraph()` nejdřív vytváří všechny nodes, pak edges

---

## Best Practices

### 1. Vždy posílejte relationships
```kotlin
// ❌ BAD
knowledgeService.ingest(IngestRequest(
  content = "TASK-123 assigned to John"
))

// ✅ GOOD
knowledgeService.ingest(IngestRequest(
  content = """
    TASK-123 assigned to John

    relationships: ["jira:TASK-123|ASSIGNED_TO|user:john"]
  """
))
```

### 2. Používejte canonical node keys
```kotlin
// ❌ BAD - různé varianty
"User:John", "user:john", "john"

// ✅ GOOD - canonical
"user:john.doe@example.com"
```

### 3. Přidávejte evidence k edges
```kotlin
// ❌ BAD
graphDB.upsertEdge(edge = GraphEdge(
  edgeType = "mentions",
  fromKey = "jira:TASK-123",
  toKey = "user:john",
  evidenceChunkIds = emptyList()  // ← Žádný důkaz!
))

// ✅ GOOD
graphDB.upsertEdge(edge = GraphEdge(
  edgeType = "mentions",
  fromKey = "jira:TASK-123",
  toKey = "user:john",
  evidenceChunkIds = listOf(chunkId)  // ← Odkaz na RAG chunk
))
```

### 4. Verifikujte po indexaci
```kotlin
// Po ingestu vždy verifikuj
val retrieved = knowledgeService.retrieve(RetrievalRequest(
  query = mainNodeKey,
  clientId = clientId
))

if (retrieved.items.isEmpty()) {
  logger.error { "INDEXING FAILED: Nothing retrieved for $mainNodeKey" }
}
```

---

## Budoucí vylepšení

### 1. Relationship confidence scores
```kotlin
data class GraphEdge(
  // ...
  val confidence: Double = 1.0,  // 0.0 - 1.0
  val extractedBy: String = "agent",  // "agent", "joern", "regex"
)
```

### 2. Temporal relationships
```kotlin
edge {
  edgeType = "ASSIGNED_TO",
  metadata = mapOf(
    "validFrom" to "2026-01-01T00:00:00Z",
    "validTo" to "2026-01-15T00:00:00Z"
  )
}
```

### 3. Semantic edge suggestions
```kotlin
// Navrhni missing relationships pomocí RAG semantic similarity
val suggestions = knowledgeService.suggestRelationships(
  nodeKey = "jira:TASK-123",
  context = retrieved.items
)
// → ["TASK-123|RELATED_TO|TASK-456 (confidence: 0.85)"]
```

---

## Závěr

Knowledge Base je **páteř celého Jervis systému**. Bez správně strukturovaných dat nemůže agent:
- Pochopit kontext úkolů
- Navigovat mezi souvislostmi (tickets → code → commits)
- Ověřit své rozhodnutí proti historickým datům
- Poskytnout vysvětlení svých akcí

**Investice do kvality Knowledge Base = Investice do kvality celého agenta.**
