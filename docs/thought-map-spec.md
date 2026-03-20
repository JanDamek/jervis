# Thought Map — Specification

**Status:** Implemented (2026-03-20)
**Purpose:** Navigation layer over KB graph — replaces flat search with spreading activation

---

## Table of Contents

1. [Problem Statement](#problem-statement)
2. [Architecture](#architecture)
3. [ArangoDB Schema](#arangodb-schema)
4. [Ingest Pipeline](#ingest-pipeline)
5. [Spreading Activation](#spreading-activation)
6. [Context Assembly](#context-assembly)
7. [Orchestrator Integration](#orchestrator-integration)
8. [Maintenance](#maintenance)
9. [Cold Start](#cold-start)
10. [Client Isolation](#client-isolation)
11. [Metrics](#metrics)
12. [Component Changes](#component-changes)

---

## Problem Statement

Two isolated worlds connected only by flat search:

```
WORLD 1: Orchestrator (MongoDB + RAM)     WORLD 2: KB (ArangoDB + Weaviate)
┌─────────────────────────────────┐    ┌──────────────────────────────────┐
│ Affairs (conversation topic)    │    │ KnowledgeNodes (entity, file,   │
│ Memory Graph (REQUEST, TASK)    │    │   class, method, commit, email) │
│ Thinking Graph (per-task)       │    │ KnowledgeEdges (calls, extends, │
│ LQM (hot cache)                 │    │   mentions, fixes, references)  │
│ Context switch detection        │    │ RAG chunks (Weaviate)           │
│ Consolidation                   │    │ Joern CPG (calls, extends)      │
└─────────────────────────────────┘    └──────────────────────────────────┘
          ↕ search query only                ↕ flat results only
```

Orchestrator cannot traverse the graph. It sees flat results, not structure. No edge navigation, no activation spreading, no contextual depth.

---

## Architecture

Thought Map adds a **navigation layer** inside ArangoDB, on top of existing KB graph:

```
┌─────────────────────────────────────────────────────────┐
│              ThoughtNodes + ThoughtEdges                 │
│  [Auth problem]──causes──[Deploy blocked]               │
│       │                        │                        │
│  ThoughtAnchors           ThoughtAnchors                │
│       │                        │                        │
│  ─────┼────────────────────────┼────────────────────────│
│       ▼                        ▼                        │
│  KnowledgeNodes + KnowledgeEdges                        │
│  [AuthService.kt]──calls──[OAuth2Client.kt]             │
│  [Spring Security]──mentions──[GitLab MR #42]           │
│  [Meeting notes]──references──[Jira AUTH-123]           │
│                                                         │
│  + Joern CPG edges (calls, extends, uses_type)          │
└─────────────────────────────────────────────────────────┘
```

**One graph, three layers, bidirectional traversal.**

Not a new service. Extension of KB service (Python) with new ArangoDB collections and KB API endpoints.

---

## ArangoDB Schema

Global collections with multi-tenant filtering via `clientId`/`projectId`/`groupId` fields (same pattern as `KnowledgeNodes`/`KnowledgeEdges`).

### ThoughtNodes (Document Collection)

```json
{
  "_key": "thought_<uuid>",
  "type": "topic | decision | problem | insight | dependency | state",
  "label": "Auth problem on GitLab",
  "summary": "OAuth2 flow fails after Spring Security 6.2 upgrade...",

  "clientId": "<clientId>",
  "projectId": "<projectId>",
  "groupId": "<groupId>",

  "activationScore": 0.72,
  "lastActivatedAt": "2026-03-20T14:30:00Z",
  "activationCount": 14,

  "embedding": [0.12, -0.34, ...],

  "sourceType": "chat | email | meeting | task | code_change",
  "sourceRef": "affair_id / task_id / email_id",

  "createdAt": "2026-03-15T10:00:00Z",
  "updatedAt": "2026-03-20T14:30:00Z"
}
```

**Embedding**: 384-dim float32, stored directly in ArangoDB. Brute-force cosine on ≤10k nodes is sub-10ms — no need for Weaviate or ANN index at this scale. Same embedding model as KB (consistency, single inference call).

**If scale exceeds 50k nodes** → add `arangoSearch` ANN index.

### ThoughtEdges (Edge Collection)

```json
{
  "_from": "ThoughtNodes/thought_abc",
  "_to": "ThoughtNodes/thought_xyz",

  "edgeType": "causes | blocks | extends | contradicts | same_domain | temporal_sequence | depends_on",
  "weight": 0.85,
  "lastTraversedAt": "2026-03-20T14:30:00Z",
  "traversalCount": 7
}
```

### ThoughtAnchors (Edge Collection)

```json
{
  "_from": "ThoughtNodes/thought_abc",
  "_to": "KnowledgeNodes/file::src_auth_service_kt",

  "anchorType": "references_code | references_entity | references_document | derived_from_email | derived_from_meeting",
  "weight": 0.9
}
```

**Direction**: `_from` = ThoughtNode, `_to` = KnowledgeNode. Both traversal directions used:
- **OUTBOUND** (Thought → Knowledge): Context assembly — "what does this thought reference?"
- **INBOUND** (Knowledge → Thought): Code change trigger — "which thoughts relate to this file?"
- **Combined**: `FOR v IN 1..3 OUTBOUND thought ThoughtAnchors, KnowledgeEdges` — traverse from thought through KB into Joern CPG edges

### Named Graph

```
thought_graph:
  ThoughtNodes → ThoughtEdges → ThoughtNodes
  ThoughtNodes → ThoughtAnchors → KnowledgeNodes
```

Combined with existing `knowledge_graph` for cross-layer traversal. Client isolation via `clientId` field filtering in AQL queries.

### Memory Footprint

| Collection | Count | Size |
|---|---|---|
| ThoughtNodes | 10k | ~20MB |
| ThoughtEdges | 50k | ~25MB |
| ThoughtAnchors | 10k | ~5MB |
| Embeddings (384-dim) | 10k | ~15MB |
| **Total** | | **~65MB** |

Negligible for ArangoDB. Auto-cached in RAM.

---

## Ingest Pipeline

Extension of existing KB ingest pipeline. No new service, no new data flow.

### Existing Flow (unchanged)

```
Server (ContinuousIndexer) → KB API (/ingest)
  ├── RAG chunks → Weaviate (embedding + text)
  └── Entities → KnowledgeNodes/Edges (ArangoDB)
```

### Extended Flow

```
Server (ContinuousIndexer) → KB API (/ingest)
  ├── RAG chunks → Weaviate                          (existing)
  ├── Entities → KnowledgeNodes/Edges                 (existing)
  └── Thoughts → ThoughtNodes + ThoughtAnchors/Edges  (NEW)
```

### Match-First Strategy

Graph grows logarithmically, not linearly:

1. **Extract** thoughts from content (extended extraction prompt in `graph_service.py`)
2. **Search** existing ThoughtNodes by cosine similarity (threshold ≥ 0.85)
3. **Match found** → reinforce: update `activationScore`, enrich `summary`, add new ThoughtAnchors
4. **No match** → create new ThoughtNode + ThoughtAnchors + ThoughtEdges to related thoughts

### Extended Extraction Prompt

Current prompt in `graph_service.py`:
```
"Extract entities and relationships from this text"
→ nodes: [{type: "file", label: "AuthService.kt"}, ...]
→ edges: [{from: "AuthService", to: "OAuth2Client", relation: "calls"}]
```

Extended prompt:
```
"Extract entities AND high-level insights/decisions/problems"
→ nodes: [...]      ← same as today
→ edges: [...]      ← same as today
→ thoughts: [       ← NEW
    {type: "problem", label: "Auth fails after upgrade",
     summary: "OAuth2 flow fails after Spring Security 6.2...",
     related_entities: ["AuthService.kt", "Spring Security"]}
  ]
```

Same LLM call (LOCAL_COMPACT on GPU2), extended output schema.

### Joern CPG Re-export

Triggered by `GitContinuousIndexer` when source files change in indexing cycle:

1. Detect changed `.kt`, `.java`, `.py`, `.ts` files in new commits
2. Trigger Joern K8s Job (Phase 2) — re-export `cpg-export.json`
3. KB `ingest_cpg_export()` updates ArangoDB edges
4. Remove stale Joern edges for deleted/renamed files
5. ThoughtAnchors pointing to affected KnowledgeNodes trigger thought re-evaluation

Extension of existing `GitContinuousIndexer` logic, not a new system.

---

## Spreading Activation

Replaces flat `kb_search` in orchestrator. Hard switch, no fallback.

### Step 1: Entry Points (embedding match)

```python
entry_thoughts = await find_nearest_thoughts(
    embedding=embed(user_message),
    client_id=client_id,
    top_k=5
)
```

Brute-force cosine in ArangoDB:

```aql
FOR t IN ThoughtNodes
  LET sim = COSINE_SIMILARITY(t.embedding, @queryEmbedding)
  SORT sim DESC
  LIMIT @topK
  RETURN { node: t, similarity: sim }
```

### Step 2: Spreading Activation (AQL traversal)

```aql
FOR startNode IN @entryNodes
  FOR v, e, p IN 1..3 OUTBOUND startNode
    ThoughtEdges, ThoughtAnchors

    LET pathWeight = PRODUCT(
      FOR edge IN p.edges RETURN edge.weight
    )

    FILTER pathWeight >= @floor  // 0.1 — absolute minimum
    SORT pathWeight DESC
    LIMIT @maxResults            // 20 — always bounded

    RETURN DISTINCT {
      vertex: v,
      pathWeight: pathWeight,
      depth: LENGTH(p.edges),
      collection: PARSE_IDENTIFIER(v._id).collection
    }
```

### Step 3: Adaptive Threshold

No static threshold. Always top-N with floor:

```python
async def traverse_thoughts(entry_nodes, max_results=20, floor=0.1):
    raw = await aql_spreading_activation(entry_nodes, min_weight=floor, max_depth=3)
    return sorted(raw, key=lambda x: x["pathWeight"], reverse=True)[:max_results]
```

- `max_results=20` — controlled output count regardless of graph density
- `floor=0.1` — filters noise
- Dense graph → top-20 have high weights (precise)
- Sparse graph → fewer results but all above floor (no empty results)

### Step 4: Hebbian Reinforcement (post-response)

Atomic AQL update, no read-modify-write race:

```aql
FOR t IN @activatedThoughts
  UPDATE t WITH {
    activationScore: OLD.activationScore * 1.1 + 0.05,
    lastActivatedAt: DATE_NOW(),
    activationCount: OLD.activationCount + 1
  } IN ThoughtNodes
```

Edge reinforcement:

```aql
FOR e IN @traversedEdges
  UPDATE e WITH {
    weight: MIN(OLD.weight * 1.05 + 0.02, 1.0),
    lastTraversedAt: DATE_NOW(),
    traversalCount: OLD.traversalCount + 1
  } IN ThoughtEdges
```

---

## Context Assembly

Token-budgeted context from activated nodes.

### Token Budget

| Source | Tokens | Priority |
|---|---|---|
| Thought summaries | 2000 | By pathWeight DESC |
| Anchored knowledge | 3000 | By anchor weight DESC |
| RAG chunks | 3000 | By relevance score |
| System context | 1000 | Affairs, session state |
| **Total** | **~9000** | |

### Assembly Logic

```python
activated = sort_by_weight(thought_nodes + knowledge_nodes)

context_parts = []
for node in activated:
    if node.collection == "ThoughtNodes":
        context_parts.append(node.summary)
        anchored = await get_anchored_knowledge(node._id)
        context_parts.extend(anchored)
    elif node.collection == "KnowledgeNodes":
        chunks = await get_rag_chunks(node.ragChunks)
        context_parts.extend(chunks)
```

### LLM Receives Structured Context

```
SYSTEM: You are the orchestrator for project X.

ACTIVE CONTEXT (from Thought Map):
- [Auth problem] (activation: 0.92): OAuth2 flow fails after upgrade...
  → Code: AuthService.kt:45 (method validateToken)
  → Related: Spring Security upgrade (MR #42, merged 15.3.)
  → Blocks: Deploy to staging

- [CI/CD pipeline] (activation: 0.6): Pipeline timeout on build stage...
  → Code: .gitlab-ci.yml
  → Email: Jan wrote 18.3. it's related to Docker image size

PARKED CONTEXTS:
- [eBay order] (0.2) — waiting for tracking
- [DB refactoring] (0.15) — paused
```

---

## Orchestrator Integration

### Complete Flow

```
1. MESSAGE (chat / email / slack / meeting transcript)
       │
       ▼
2. THOUGHT MAP TRAVERSAL (ArangoDB)
   → "Where am I in the map? What's related?"
   → Spreading activation → activated nodes
       │
       ▼
3. CONTEXT ASSEMBLY
   → Thought summaries + anchored KB data + Joern code graph
   → Token-budgeted, prioritized by activation weight
       │
       ▼
4. LLM CALL (orchestrator)
   → "Understand context, decide what to do next"
   → Output: decision + instructions for coding agent
       │
       ▼
5. DISPATCH
   ├── Coding agent (Claude SDK) → precise instructions + code context
   ├── User response
   └── Update Thought Map (new nodes, edge reinforcement)
       │
       ▼
6. POST-PROCESSING
   → New ThoughtNodes from result
   → New ThoughtAnchors to KB entities
   → Hebbian reinforcement of traversed edges
   → New ThoughtEdges if LLM discovered new relationships
```

LLM in step 4 is a **compute engine** — it receives graph-context and decides where to go. It doesn't need to remember anything — everything is in the graph.

### Post-Response Update

After LLM responds:

1. **Extract** new entities/relations from response
2. **Reinforce** activated ThoughtNodes (Hebbian)
3. **Create** new ThoughtEdges if LLM discovered connections ("this is related to...")
4. **Create** new ThoughtAnchors if LLM mentioned specific files/entities
5. **Create** new ThoughtNodes if LLM identified new problems/decisions/insights

---

## Maintenance

### Two Regimes

#### Light Maintenance (safe during chat)

**Trigger**: GPU idle + not run in last 1 hour

- **Decay**: `activationScore *= 0.995` for all client's nodes
- **Merge**: Nodes with cosine similarity > 0.92 → LLM summarize + redirect all edges to merged node

#### Heavy Maintenance (quiet hours only)

**Trigger**: Nightly window 01:00–06:00 + not run today. Also triggered if GPU has no work during the day.

Chat usage window: ~06:00–01:00 (rarely to 02:00).

- **Archive dead**: `activationScore < 0.05` AND `30+ days` → summary ingested to RAG (Weaviate) as permanent memory, ThoughtNode deleted (edges cascade)
- **Louvain community detection** → identify clusters
- **Hierarchy build**: Create meta-thoughts for each community via LLM summarization

### Scheduler

```python
class ThoughtMaintenanceScheduler:
    QUIET_HOURS = (1, 6)  # 01:00–06:00

    async def check_and_run(self, client_id: str):
        if await gpu_is_idle() and not self._ran_recently(client_id, hours=1):
            await self.run_light(client_id)

        if self._in_quiet_hours() and not self._ran_today(client_id):
            await self.run_heavy(client_id)

    async def run_light(self, client_id):
        await decay_activations(client_id, factor=0.995)
        await merge_similar(client_id, threshold=0.92)

    async def run_heavy(self, client_id):
        await self.run_light(client_id)
        await archive_dead_thoughts(client_id, threshold=0.05, days=30)
        await detect_and_build_hierarchy(client_id)
```

### Thought Hierarchy (auto-generated)

```
[DevOps] (meta-thought, auto-generated)
  ├── [CI/CD pipeline problems]
  │     ├── [Docker build timeout]
  │     └── [GitLab runner OOM]
  ├── [Kubernetes deploy]
  │     ├── [Staging environment]
  │     └── [Production rollback 12.3.]
  └── [Monitoring]
        └── [Prometheus alerting]
```

Meta-thoughts give the orchestrator a **zoom-out** view. Generated via Louvain community detection + LLM summarization.

---

## Cold Start

For existing client with full KB but empty Thought Map.

### Bootstrap Procedure (one-time)

```python
async def bootstrap_thought_map(client_id: str):
    # 1. Top 100 entities by degree (most connected KB nodes)
    top_entities = await aql("""
        FOR n IN KnowledgeNodes
        FILTER n.clientId == @clientId
        LET degree = LENGTH(
            FOR e IN KnowledgeEdges
            FILTER (e._from == n._id OR e._to == n._id)
               AND e.clientId == @clientId
            RETURN 1
        )
        SORT degree DESC
        LIMIT 100
        RETURN { node: n, degree: degree }
    """)

    # 2. LLM: cluster entities into thematic groups → ThoughtNodes
    thoughts = await llm_cluster_entities(top_entities)

    # 3. Create ThoughtNodes + ThoughtAnchors
    for thought in thoughts:
        node = await create_thought_node(thought)
        for entity in thought.related_entities:
            await create_thought_anchor(node, entity)

    # 4. Infer ThoughtEdges from co-occurrence
    await infer_thought_edges(client_id)
```

Runs once, takes minutes. Graph then grows organically through indexing.

---

## Client Isolation

### Standard Operation (strict isolation)

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│  Client A   │  │  Client B   │  │  Client C   │
│ ThoughtNodes│  │ ThoughtNodes│  │ ThoughtNodes│
│  isolated   │  │  isolated   │  │  isolated   │
└─────────────┘  └─────────────┘  └─────────────┘
```

Global collections with `clientId` field filtering in every AQL query. No cross-client edges ever exist.

### Admin/Owner Cross-Client Access

```python
# Standard — always client-filtered via AQL WHERE clause
async def traverse_thoughts(client_id: str, ...):
    # All AQL queries include FILTER v.clientId == @clientId

# Admin — cross-client read-only
async def traverse_thoughts_admin(
    client_ids: list[str] | None = None,  # None = all
    owner_token: str = ...,               # verify admin identity
):
    # AQL with FILTER v.clientId IN @clientIds (or no filter for all)
    # Read-only — never creates cross-client edges
```

---

## Metrics

### Per LLM Call

```python
metrics = {
    "thoughts_activated": 12,
    "anchors_resolved": 8,
    "context_tokens_used": 7200,
    "traversal_depth_avg": 1.8,
    "traversal_time_ms": 3,
}
```

### Periodic (per maintenance cycle)

- Graph density: edges/nodes ratio
- Orphan rate: nodes without edges
- Activation distribution: histogram
- Merge rate: nodes merged per cycle
- Archive rate: nodes archived per cycle

---

## Component Changes

| Component | Change |
|---|---|
| **Server (Kotlin)** | No change — still sends data to KB via REST |
| **KB service (Python)** | Extend `graph_service.py`: new collections, extended extraction prompt, maintenance endpoints |
| **KB API** | New endpoints: `/thoughts/traverse` (spreading activation), `/thoughts/maintain` (decay + merge + archive) |
| **Orchestrator** | Replace flat `kb_search` with `/thoughts/traverse`. Hard switch, no fallback, no deprecated paths |
| **ArangoDB** | 3 new global collections (ThoughtNodes, ThoughtEdges, ThoughtAnchors) + indexes |
| **GPU** | No change — GPU2 sufficient |
| **Joern** | Re-export on code change in `GitContinuousIndexer` (extension of existing Phase 2) |

### New KB API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/thoughts/traverse` | POST | Spreading activation from entry points |
| `/thoughts/maintain` | POST | Trigger maintenance (light or heavy) |
| `/thoughts/bootstrap` | POST | Cold start — seed from existing KB |
| `/thoughts/stats` | GET | Metrics and graph statistics |

### GPU Load (GPU2)

- Entity/relation extraction: LOCAL_COMPACT LLM (~100-200 tokens per message) — same as today
- Embedding: one call per new thought — trivial
- Spreading activation: pure ArangoDB AQL — no GPU
- Consolidation/merge: periodic LLM summarization — light load
- Community detection (Louvain): CPU algorithm — no GPU
