# Knowledge Base - Implementation and Architecture

**Status:** Production Documentation (2026-02-12)
**Purpose:** Knowledge base implementation, graph design, and architecture

---

## Table of Contents

1. [Knowledge Base Architecture](#knowledge-base-architecture)
2. [Graph Design](#graph-design)
3. [Knowledge Base Implementation](#knowledge-base-implementation)
4. [Continuous Indexers](#continuous-indexers)
5. [RAG Integration](#rag-integration)
6. [Knowledge Base Best Practices](#knowledge-base-best-practices)

---

## Knowledge Base Architecture

### Overview

Knowledge Base is the most critical component of Jervis. Agent cannot function without quality structured data and relationships.

### Dual Storage Model

```
┌─────────────────────────────────────────────────────────┐
│                    KNOWLEDGE BASE                        │
├─────────────────────────┬───────────────────────────────┤
│        RAG Store        │        Graph Store            │
│      (Weaviate)         │       (ArangoDB)              │
├─────────────────────────┼───────────────────────────────┤
│ • Vector embeddings     │ • Vertices (entities)         │
│ • Semantic search       │ • Edges (relationships)       │
│ • Chunk storage         │ • Structured navigation      │
│ • Metadata              │ • Traversal queries          │
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

## Graph Design

### ArangoDB Schema - "Two-Collection" Approach

For each client, Jervis creates **3 ArangoDB objects**:

1. **`c{clientId}_nodes`** - Document Collection
   - Single collection for all node types (Users, Jira tickets, files, commits, Confluence pages...)
   - Heterogenous graph = maximum flexibility for AI agent
   - Each document has **`type`** attribute for entity discrimination

2. **`c{clientId}_edges`** - Edge Collection
   - All edges between nodes
   - **`edgeType`** attribute defines relationship type

3. **`c{clientId}_graph`** - Named Graph
   - ArangoDB Named Graph for optimized traversal queries
   - Definition: `c{clientId}_nodes` → `c{clientId}_edges` → `c{clientId}_nodes`
   - Enables AQL syntax: `FOR v IN 1..3 ANY startNode GRAPH 'c123_graph'`

### Node Structure

```json
{
  "_key": "jira::JERV-123",
  "type": "jira_issue",           // MANDATORY type discriminator
  "ragChunks": ["chunk_uuid_1"],  // Optional - Weaviate chunk IDs
  // ... arbitrary properties specific to entity type
  "summary": "Fix login bug",
  "status": "In Progress"
}
```

### Edge Structure

```json
{
  "_key": "mentions::jira::JERV-123->file::Service.kt",
  "edgeType": "mentions",         // MANDATORY relationship type
  "_from": "c123_nodes/jira::JERV-123",
  "_to": "c123_nodes/file::Service.kt"
}
```

### Node Types (Examples)

#### CODE - Code Structure
```kotlin
// File
nodeKey: "file::src/main/kotlin/com/jervis/Service.kt"
type: "file"
props: { path: String, extension: String, language: String, linesOfCode: Int, lastModified: Instant }

// Class
nodeKey: "class::com.jervis.service.UserService"
type: "class"
props: { name: String, qualifiedName: String, isInterface: Boolean, visibility: String, filePath: String }

// Method (from tree-sitter, enriched by Joern CPG)
nodeKey: "method__{classQualifiedName}__{methodName}__branch__{branch}__{projectId}"
type: "method"
props: { label: String, className: String, filePath: String, branchName: String, signature: String? }
// Note: signature is populated by Joern CPG analysis (Phase 2), not tree-sitter
```

#### VCS - Version Control
```kotlin
// Repository (one per connected repository)
nodeKey: "repository__{org}_{repo}"
type: "repository"
props: { label: String, defaultBranch: String, techStack: String?, clientId: String, projectId: String }

// Git Branch (scoped to projectId — same name can exist in different projects)
nodeKey: "branch__{branchName}__{projectId}"
type: "branch"
props: { branchName: String, isDefault: Boolean, status: String, lastCommitHash: String?, fileCount: Int,
         clientId: String, projectId: String }

// Git File (scoped to branch+project — different content per branch)
nodeKey: "file__{path}__branch__{branchName}__{projectId}"
type: "file"
props: { path: String, extension: String, language: String?, branchName: String,
         clientId: String, projectId: String }

// Git Class (scoped to branch+project, extracted by tree-sitter)
nodeKey: "class__{className}__branch__{branchName}__{projectId}"
type: "class"
props: { qualifiedName: String?, filePath: String, branchName: String, visibility: String?,
         clientId: String, projectId: String }

// Git Commit
nodeKey: "commit::{commitHash}"
type: "commit"
props: { hash: String, message: String, authorName: String, timestamp: Instant, branchName: String? }
```

> **Key length guard**: ArangoDB `_key` max 254 bytes. Keys exceeding 200 chars get
> a SHA-256 suffix via `_safe_arango_key()` helper in `graph_service.py`.

#### ISSUE TRACKING - Jira, GitHub Issues
```kotlin
// Jira Ticket
nodeKey: "jira::{projectKey}-{issueNumber}"  // e.g., "jira::JERV-123"
type: "jira_issue"
props: { key: String, summary: String, description: String?, issueType: String, status: String, priority: String }

// Jira Comment
nodeKey: "jira_comment::{issueKey}-{commentId}"
type: "jira_comment"
props: { author: String, body: String, createdAt: Instant, updatedAt: Instant? }
```

#### DOCUMENTATION - Confluence, Wiki
```kotlin
// Confluence Page
nodeKey: "confluence::{spaceKey}::{pageId}"
type: "confluence_page"
props: { title: String, spaceKey: String, version: Int, createdAt: Instant, updatedAt: Instant, authorName: String }
```

#### COMMUNICATION - Email, Slack, Teams
```kotlin
// Email
nodeKey: "email::{messageId}"
type: "email"
props: { subject: String, from: String, to: List<String>, sentAt: Instant, receivedAt: Instant, hasAttachments: Boolean }
```

### Edge Types (Examples)

#### CODE Relationships
```kotlin
// Structure relationships
file --[contains]--> class
class --[contains]--> method
class --[extends]--> class
method --[calls]--> method
```

#### VCS Relationships
```kotlin
// Repository structure (structural ingest — POST /ingest/git-structure)
repository --[has_branch]--> branch
branch --[contains_file]--> file
branch --[has_commit]--> commit
file --[contains]--> class
class --[has_method]--> method     // tree-sitter extracted
file --[imports]--> class           // tree-sitter import analysis

// Git commit relationships (commit-level ingest)
commit --[modifies]--> file
commit --[creates]--> file
commit --[parent]--> commit

// Joern CPG edges (POST /ingest/cpg — deep analysis)
method --[calls]--> method          // call graph from Joern
class --[extends]--> class          // inheritance from Joern
class --[uses_type]--> class        // field/param type references from Joern
```

#### ISSUE TRACKING Relationships
```kotlin
// Jira relationships
jira_issue --[blocks]--> jira_issue
jira_issue --[assigned_to]--> user
jira_issue --[mentions_file]--> file
commit --[fixes]--> jira_issue
```

#### DOCUMENTATION Relationships
```kotlin
// Confluence relationships
confluence_page --[documents]--> class
confluence_page --[references]--> jira_issue
```

---

## Knowledge Base Implementation (Python Service)

The Knowledge Base is implemented as a Python service (`service-knowledgebase`) to leverage the rich ecosystem of AI and Data tools.

### Tech Stack
*   **Language**: Python 3.11+
*   **Framework**: FastAPI
*   **RAG**: LangChain, Weaviate Client v4
*   **Graph**: Python-Arango
*   **LLM**: Ollama (Qwen 2.5, Qwen 3-VL)

### Features
1.  **Web Crawling**: `POST /crawl` endpoint to index documentation from URLs.
2.  **File Ingestion**: `POST /ingest/file` supports PDF, DOCX, etc. using Tika.
3.  **Code Analysis**: `POST /analyze/code` integrates with Joern service (ad-hoc queries).
4.  **Image Understanding**: Automatically detects images and uses `qwen-3-vl` to generate descriptions.
5.  **Scoped Search**: Filters results by Client, Project, and Group.
6.  **Structural Code Ingest**: `POST /ingest/git-structure` — tree-sitter extracts classes, methods, imports from source code.
7.  **Deep Code Analysis**: `POST /ingest/cpg` — Joern CPG export adds semantic edges (calls, extends, uses_type).

### Multi-tenant Scoping (with Project Groups)

KB data is scoped hierarchically:

| Scope | `clientId` | `projectId` | `groupId` | Visibility |
|-------|-----------|-------------|-----------|------------|
| Global | `""` | `""` | `""` | Visible everywhere |
| Client | `"X"` | `""` | `""` | Visible to client X |
| Group | `"X"` | `""` | `"G"` | Visible to all projects in group G |
| Project | `"X"` | `"Y"` | `"G"` | Visible only to project Y |

**Group cross-visibility**: When retrieving data for a project that belongs to a group,
the filter includes: `(projectId == "" OR projectId == myProject OR groupId == myGroup)`.
This means all projects in the same group share KB data (RAG chunks and graph nodes).

Both Weaviate (RAG) and ArangoDB (Graph) store `groupId` alongside `clientId`/`projectId`.

**Re-grouping**: When a project moves between groups, its own data retains its `projectId` and
gains immediate visibility in the new group via the updated `groupId` filter.

### Integration
*   **Tika Service**: Used for text extraction (OCR).
*   **Joern Service**: Used for deep code analysis (ad-hoc queries + CPG export).
*   **Tree-sitter**: Lightweight AST parser for structural code extraction (classes, methods, imports). Bundled in KB service via `tree-sitter-languages` package.

### Relationship Extraction

#### Supported Formats

**1. Pipe format (recommended)**
```
from|edgeType|to
```
Example: `jira:TASK-123|MENTIONS|user:john`

**2. Arrow format**
```
from->edgeType->to
```
Example: `file:Service.kt->MODIFIED_BY->commit:abc123`

**3. Bracket format (ArangoDB-like)**
```
from -[edgeType]-> to
```
Example: `class:UserService -[CALLS]-> method:authenticate`

**4. Metadata block (embedded in content)**
```markdown
relationships: [
  "jira:TASK-123|MENTIONS|user:john",
  "jira:TASK-123|AFFECTS|file:Service.kt",
  "commit:abc123|FIXES|jira:TASK-123"
]
```

#### Short-hand Expansion

Main node can be referenced abbreviated in relationships:
```kotlin
mainNodeKey = "jira:TASK-123"
relationships = [
  "TASK-123|MENTIONS|user:john",  // Expands to: jira:TASK-123|MENTIONS|user:john
  "file:Service.kt|RELATED_TO|TASK-123"  // Expands to: file:Service.kt|RELATED_TO|jira:TASK-123
]
```

### Evidence-based Relationships

**CRITICAL FEATURE:** Every edge MUST have evidence (chunk ID).

#### Why?

1. **Traceability** - Can trace back where relationship came from
2. **Verification** - Agent can verify if relationship still valid
3. **Confidence** - More chunks = higher confidence in relationship
4. **Explainability** - Can show user specific text that supports relationship

#### Example

```kotlin
// Agent indexes Jira ticket
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

// After ingest in ArangoDB:
edge {
  edgeType = "affects",
  from = "jira:task-123",
  to = "file:userservice.kt",
  evidenceChunkIds = ["chunk-uuid-abc"]  // ← Reference to chunk with this text
}
```

### Normalization & Canonicalization

#### Problem: Variable naming

Agent can reference same entity differently:
- `user:John`, `user:john`, `User:John`
- `jira:TASK-123`, `JIRA:task-123`
- `order:order_530798957`, `order:530798957`

#### Solution: Multi-stage normalization

**Stage 1: Format normalization (stable)**
```kotlin
normalizeSingleGraphRef("User:John  Smith") → "user:john  smith"
// Rules:
// - Namespace (before ':') → lowercase
// - Whitespace → single space
// - Special chars → '_'
```

**Stage 2: Canonicalization (semantic)**
```kotlin
canonicalizeGraphRef("order:order_530798957") → "order:530798957"
// Rules:
// - Remove redundant namespace prefix in value
// - order:order_X → order:X
// - product:product_lego → product:lego
```

**Stage 3: Alias resolution (per-client registry)**
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

// On subsequent ingest:
resolveCanonicalGraphRef("user:john") → "user:john.doe@example.com"
// ✅ All aliases point to same canonical key
```

### Cache Strategy

```kotlin
// In-memory cache (ConcurrentHashMap)
graphRefCache["client-abc|user:john"] = "user:john.doe@example.com"

// Mutex per cache key (prevents race conditions)
graphRefLocks["client-abc|user:john"] = Mutex()
```

---

## Continuous Indexers

### EmailContinuousIndexer

- **Purpose:** Creates DATA_PROCESSING task from emails + processes links
- **Process:**
  1. Reads NEW emails from MongoDB
  2. Extracts links using LinkExtractor
  3. Creates DATA_PROCESSING task for email (without downloading links)
  4. For each link creates SEPARATE LINK_PROCESSING task

### JiraContinuousIndexer

- **Purpose:** Creates DATA_PROCESSING task from Jira issues
- **Process:**
  1. Reads NEW Jira issues from MongoDB
  2. Creates DATA_PROCESSING task
  3. Qualifier agent handles indexing and link processing

### ConfluenceContinuousIndexer

- **Purpose:** Creates DATA_PROCESSING task from Confluence pages
- **Process:** Similar to Jira indexer

### GitContinuousIndexer

- **Purpose:** Creates rich KB content from Git commits with three-phase indexing
- **Process (initial branch index):**
  1. **Branch Overview** — repository tech stack, file tree, README, recent changes → `GIT_PROCESSING` task
  2. **Structural Ingest** — tree-sitter extracts classes, methods, imports from source files → `POST /ingest/git-structure` → ArangoDB graph nodes (repo→branch→file→class→method)
  3. **CPG Deep Analysis** — Joern generates Code Property Graph → `POST /ingest/cpg` → semantic edges (calls, extends, uses_type)
- **Process (incremental commits):**
  1. Commit diff + metadata → `GIT_PROCESSING` task → Qualifier indexes to KB

#### Tree-sitter Pipeline (Phase 1 — fast, ~5s per 100 files)

Kotlin sends file contents to KB service → KB invokes tree-sitter → extracts classes, methods, imports.

```
GitContinuousIndexer.createStructuralIndexTask()
  → readSourceFileContents() — reads top 150 files (max 50KB each, 5MB total)
  → POST /ingest/git-structure with fileContents
  → KB service code_parser.parse_file() for each file
  → Creates: method nodes + has_method edges + imports edges
```

Supported languages: Kotlin, Java, Python, TypeScript/JavaScript, Go, Rust.

#### Joern CPG Pipeline (Phase 2 — deep, ~60-120s per project)

After structural nodes exist, dispatches Joern K8s Job for semantic analysis.

```
GitContinuousIndexer.createCpgAnalysisTask()
  → POST /ingest/cpg (clientId, projectId, branch, workspacePath)
  → KB service dispatches JoernClient.run_cpg_export()
    → K8s Job: joern → importCode → cpg-export-query.sc → .jervis/cpg-export.json
  → KB reads pruned CPG JSON
  → graph_service.ingest_cpg_export() creates edges:
    - calls: method → method (call graph)
    - extends: class → class (inheritance)
    - uses_type: class → class (type references)
```

**CPG Pruning Strategy:** Only actionable edges are imported. Skipped: AST nodes, CFG/CDG edges, reaching-def edges, ARGUMENT/RECEIVER details (too granular for agent use).

**Failure handling:** CPG analysis failure is non-fatal — the structural graph from tree-sitter remains fully usable.

### MeetingContinuousIndexer

- **Purpose:** Transcribes uploaded meeting recordings and creates MEETING_PROCESSING tasks
- **Two-stage pipeline:**
  1. **Transcription:** Polls for UPLOADED meetings → runs Whisper (K8s Job in-cluster, subprocess locally) → TRANSCRIBED
  2. **KB Indexing:** Polls for TRANSCRIBED meetings → builds markdown content (title, date, duration, type, full transcript with timestamps) → creates MEETING_PROCESSING task → INDEXED
- **State machine:** RECORDING → UPLOADED → TRANSCRIBING → TRANSCRIBED → INDEXED (or FAILED at any step)
- **sourceUrn format:** `meeting::id:{meetingId},title:{title}`

---

## RAG Integration

### 1. RAG-first Search

```kotlin
val embedding = embeddingGateway.callEmbedding(query)
val results = weaviateVectorStore.search(
  query = VectorQuery(embedding, filters = VectorFilters(clientId, projectId))
)
```

### 2. Graph Expansion

```kotlin
// Seed nodes from chunk metadata
val seedNodes = results.flatMap { it.metadata["graphRefs"] }

// Traversal (2 hops)
seedNodes.forEach { seed →
  graphDBService.traverse(clientId, seed, TraversalSpec(maxDepth = 2))
}
```

### 3. Evidence Pack Assembly

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

## Qualification Queue Priority & Retry

### Queue Priority / Reordering

Tasks in `READY_FOR_QUALIFICATION` state are processed in order: `queuePosition ASC NULLS LAST, createdAt ASC`.
This means manually prioritized items (those with a `queuePosition` set) are processed first, while others fall back to FIFO by creation time.

**RPC endpoints:**
- `reorderKbQueueItem(taskId, newPosition)` -- set explicit queue position
- `prioritizeKbQueueItem(taskId)` -- move to position 1 (front of queue)

The UI shows up/down arrows and a prioritize button on items in the "Čeká na KB" pipeline section.

### Exponential Retry for Operational Errors

When Ollama is busy or unreachable, qualification retries infinitely with DB-based exponential backoff:

```
5s → 10s → 20s → 40s → 80s → 160s → 300s (cap, retries forever at 5min)
```

**Configuration** (`QualifierProperties`):
- `initialBackoffMs = 5000` (5 seconds)
- `maxBackoffMs = 300000` (5 minutes)

**Retriable errors** (infinite retry, never marks ERROR):
- Timeout, connection, socket, network, prematurely closed
- Ollama busy, queue full, too many requests
- HTTP 429 (Too Many Requests), HTTP 503 (Service Unavailable)

**Non-retriable errors** (permanent ERROR state):
- Actual indexing/parsing errors from KB microservice

**Key invariants:**
- Items stay `READY_FOR_QUALIFICATION` with a future `nextQualificationRetryAt` during backoff (not marked FAILED)
- Queue releases items only on restart or crash (stale task recovery in `BackgroundEngine.resetStaleTasks()`)
- `qualificationRetries` counter tracks retry attempts (displayed in UI as "Opakuje (Nx)")

### SourceUrn Provider Dispatch

BugTracker and Wiki items use provider-specific SourceUrn factories:

| Provider | BugTracker URN | Wiki URN |
|----------|---------------|----------|
| GITHUB | `SourceUrn.githubIssue()` | — |
| GITLAB | `SourceUrn.gitlabIssue()` | — |
| JIRA / other | `SourceUrn.jira()` | — |
| CONFLUENCE / other | — | `SourceUrn.confluence()` |

This ensures correct source type display in the indexing queue UI (e.g., GitHub Issues connections show "GitHub" not "Jira").

---

## Knowledge Base Best Practices

### Key Practices

1. **Two-stage processing:** CPU qualification + GPU execution
2. **Bidirectional knowledge:** RAG (semantic) + Graph (structured)
3. **Evidence-based relationships:** Every edge has supporting evidence
4. **Multi-tenancy:** Per-client isolation in all storage layers
5. **Project group cross-visibility:** Projects in same group share KB data
6. **Fail-fast design:** Errors propagate, no silent failures
7. **Type safety:** Explicit input/output types throughout
8. **Infinite retry for operational errors:** Ollama busy/timeout → exponential backoff, never marks ERROR
9. **Queue priority:** Manual reordering of qualification queue items via `queuePosition`

### Benefits

1. **Cost efficiency:** GPU models only when necessary
2. **Scalability:** Parallel CPU qualification, GPU execution on idle
3. **Explainability:** Evidence links for all relationships
4. **Flexibility:** Schema-less graph for new entity types
5. **Performance:** Hybrid search combining semantic + structured
6. **User priority:** Preemption ensures immediate response
7. **Resilience:** Infinite retry with backoff ensures no items lost during Ollama overload