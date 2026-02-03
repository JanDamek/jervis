# Implementation Notes - Technical Design Decisions

**Last updated:** 2026-02-02  
**Status:** Technical Reference  
**Purpose:** Technical decisions, problem solving, and architecture rationale

---

## Table of Contents

1. [Sealed Classes & MongoDB](#sealed-classes--mongodb)
2. [Knowledge Base Architecture](#knowledge-base-architecture)
3. [Smart Model Selector](#smart-model-selector)
4. [Polling & Indexing Design](#polling--indexing-design)
5. [Vision Processing Pipeline](#vision-processing-pipeline)
6. [Integration Notes](#integration-notes)
7. [OAuth2 Implementation & Connection Testing](#oauth2-implementation--connection-testing)

---

## Sealed Classes & MongoDB

### Problem 1: BeanInstantiationException

#### Error Message

```
org.springframework.beans.BeanInstantiationException: Failed to instantiate 
[com.jervis.entity.jira.BugTrackerIssueIndexDocument]: Is it an abstract class?
```

#### Root Cause

Spring Data MongoDB cannot deserialize sealed classes without `_class` discriminator field. When documents exist in MongoDB without this field, deserialization fails.

#### Solution

Run the MongoDB migration script to add `_class` field:

```bash
./scripts/mongodb/run-migration.sh mongodb://localhost:27017/jervis
```

Or manually:

```bash
mongosh mongodb://localhost:27017/jervis scripts/mongodb/fix-all-sealed-classes.js
```

**After migration:**
1. Restart Jervis server
2. Verify error is gone in logs

### Problem 2: DuplicateKeyException on _id

#### Error Message

```
org.springframework.dao.DuplicateKeyException: Write operation error on MongoDB server.
Write error: WriteError{code=11000, message='E11000 duplicate key error...}
```

#### Root Cause

This error occurs when:

1. **Missing `_class` field causes findExisting() to fail**
   - Document exists in MongoDB but can't be deserialized
   - `findExisting()` returns `null` even though document exists
   - Code tries to INSERT instead of UPDATE
   - MongoDB rejects INSERT because `_id` already exists

2. **Race condition** (less common)
   - Two polling threads process same issue simultaneously
   - Both try to INSERT at same time
   - One succeeds, other fails with duplicate key

#### Solution

**Step 1: Run Migration Script**

```bash
./scripts/mongodb/run-migration.sh
```

This adds `_class` field to all documents, fixing deserialization.

**Step 2: Restart Jervis Server**

After migration, restart to ensure:
- Spring Data MongoDB cache is cleared
- New polling cycle starts clean

**Step 3: Verify Logs**

Look for:
- ✅ `Saving issue: issueKey=XXX, _id=..., state=NEW` (normal)
- ✅ `Updated issue XXX` (updates working)
- ❌ No more `BeanInstantiationException` errors
- ❌ No more `DuplicateKeyException` errors

### MongoDB Collections Schema

**Sealed Class Hierarchy:**

```kotlin
sealed class BugTrackerIssueIndexDocument {
    abstract val id: ObjectId
    abstract val issueKey: String
    abstract val state: String
    abstract val updatedAt: Instant

    data class New(...) : BugTrackerIssueIndexDocument() {
        override val state = "NEW"
    }

    data class Indexed(...) : BugTrackerIssueIndexDocument() {
        override val state = "INDEXED"
    }

    data class Failed(...) : BugTrackerIssueIndexDocument() {
        override val state = "FAILED"
    }
}
```

MongoDB automatically adds `_class` field:
- `"_class": "...New"`
- `"_class": "...Indexed"`
- `"_class": "...Failed"`

---

## Knowledge Base Architecture

### Double-Storage Model

```
┌─────────────────────────────────────────────────────────┐
│                    KNOWLEDGE BASE                        │
├─────────────────────────┬───────────────────────────────┤
│        RAG Store        │        Graph Store            │
│      (Weaviate)         │       (ArangoDB)              │
├─────────────────────────┼───────────────────────────────┤
│ • Vector embeddings     │ • Nodes (entities)            │
│ • Semantic search       │ • Edges (relationships)       │
│ • Chunk storage         │ • Structured navigation       │
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

### Ingest → Storage Flow

```kotlin
IngestRequest
   ↓
1. Chunking (simple paragraph split)
   ↓
2. Extraction
   - extractNodes()        // Pattern: "type:id"
   - extractRelationships() // Multiple formats
   ↓
3. Normalization
   - normalizeGraphRefs()   // Canonical form
   - resolveCanonicalGraphRef() // Alias resolution
   ↓
4. RAG Storage (Weaviate)
   - Embedding via EmbeddingGateway
   - Metadata: sourceUrn, clientId, kind, graphRefs
   ↓
5. Graph Storage (ArangoDB)
   - buildGraphPayload()    // Parse relationships
   - persistGraph()         // Upsert nodes + edges
   ↓
IngestResult (success, summary)
```

### Critical Properties

- **Atomic chunking**: Each chunk is independently embeddable
- **Metadata preservation**: sourceUrn, kind, graphRefs tracked
- **Bidirectional links**: Full graph traversal possible
- **Deduplication**: Canonical node keys prevent duplicates

---

## Smart Model Selector

### Problem

- **Hardcoded models**: All tasks use same model with fixed context size
- **Small tasks**: Waste RAM/VRAM on large context allocation
- **Large tasks**: Get truncated at fixed limit

### Solution

Dynamically select model based on input content length

### Architecture

#### Model Naming Convention

All models follow pattern:
```
qwen3-coder-tool-{SIZE}k:30b
```

Examples:
- `qwen3-coder-tool-8k:30b` → 8,192 tokens context
- `qwen3-coder-tool-32k:30b` → 32,768 tokens context
- `qwen3-coder-tool-128k:30b` → 131,072 tokens context

#### Available Tiers

| Tier | Context | Use Case | GPU Safe |
|------|---------|----------|----------|
| 4k | 4,096 | Tiny tasks | YES |
| 8k | 8,192 | Short docs | YES |
| 16k | 16,384 | Medium docs | YES |
| 32k | 32,768 | Long docs | YES |
| 40k | 40,960 | Large code | NO (spillover) |
| 64k | 65,536 | Very large | NO (spillover) |
| 128k | 131,072 | Massive | NO (spillover) |

### Algorithm

#### Token Estimation

```kotlin
// Conservative: 1 token ≈ 3 characters
val inputTokens = inputContent.length / 3
val totalNeeded = inputTokens + outputReserve  // 2k reserve
```

#### Model Selection

```kotlin
val selectedModel = when {
    totalNeeded <= 4096 -> "qwen3-coder-tool-4k:30b"
    totalNeeded <= 8192 -> "qwen3-coder-tool-8k:30b"
    totalNeeded <= 16384 -> "qwen3-coder-tool-16k:30b"
    totalNeeded <= 32768 -> "qwen3-coder-tool-32k:30b"
    totalNeeded <= 65536 -> "qwen3-coder-tool-64k:30b"
    else -> "qwen3-coder-tool-128k:30b"
}
```

### Benefits

- **No truncation**: Large tasks get appropriate context
- **Efficient resources**: Small tasks don't waste VRAM
- **Automatic**: No manual configuration needed
- **Scalable**: Easy to add new tiers

---

## Polling & Indexing Design

### 3-Stage Pipeline

```
Polling Handler → Indexing Collection → Pending Tasks → Qualifier Agent
```

### Stage 1: Polling Handler

**Purpose:** Download data from external APIs

**Responsibilities:**
1. Regular execution per `ConnectionDocument` schedule
2. Initial Sync vs Incremental Sync based on `lastSeenUpdatedAt`
3. Deduplication at multiple levels
4. Change detection to mark documents as NEW

**Critical:** Initial sync downloads ALL data with pagination

### Stage 2: Indexing Collection

**Purpose:** Temporary storage for documents awaiting processing

**Schema:** Polymorphic using sealed classes

**States:**
- `NEW` - Freshly polled, ready for qualification
- `INDEXED` - Processed by Qualifier, stored in RAG/Graph
- `FAILED` - Failed during qualification, needs manual review

### Deduplication (4 Levels)

1. **Pagination-level**: Filter during collection from API
2. **Repository-level (first check)**: `findExisting()` before processing
3. **Repository-level (double-check)**: Race condition protection
4. **MongoDB unique index**: Final fail-safe

---

## Vision Processing Pipeline

### Overview

**Problem:** Apache Tika is blind - doesn't understand meaning of screenshots, graphs, diagrams

### Two-Stage Architecture

**Stage 1:** General vision analysis
- Detect document type
- Extract visual elements
- Generate high-level summary

**Stage 2:** Type-specific vision analysis
- Process recognized types with specialized models
- Extract structured data
- Generate type-specific metadata

### Key Principles

- ✅ **Vision context preservation**: Never lost between stages
- ✅ **Fail-fast approach**: Errors collected, not silently ignored
- ✅ **Type-safe processing**: Deterministic flow

---

## Integration Notes

### OAuth2 Flow

1. **User initiates** → Clicks "Connect GitHub"
2. **Server initiates** → Generates state token, redirects to GitHub
3. **User authorizes** → Logs in, grants permissions at GitHub
4. **GitHub redirects** → To `/oauth2/callback?code=...&state=...`
5. **Server exchanges** → Code for access_token via GitHub API
6. **Connection stored** → With encrypted access_token

### Rate Limiting Strategy

| Source | Rate Limit Strategy | Implementation |
|--------|-------------------|-----------------|
| Internal services | None (trusted network) | KtorClientFactory |
| LLM APIs | Provider rate limit | KtorClientFactory (no limit) |
| Dynamic URLs | Per-domain sliding window | DomainRateLimiter |
| External integrations | Per-service config | Varies by service |

### Backpressure & Concurrency

Qualification processing uses Flow:
```kotlin
findTasksForQualification()
    .buffer(N)
    .flatMapMerge(concurrency = N) { processOne() }
```

Where N = `maxConcurrentRequests` from `ProviderCapabilitiesService`

---

## OAuth2 Implementation & Connection Testing

### Current Architecture (Phase 1 - Single User)

#### Test Connection Button
- Located in: `ConnectionEditDialog` in Settings → Connections → Edit
- Implementation: Calls `repository.connections.testConnection(connectionId)`
- Shows ✓/✗ status with message
- Works for all connection types (HTTP, IMAP, SMTP, OAuth2)

#### OAuth2 Authorization Flow
```
UI: User clicks "Authorize with OAuth2"
    ↓
Opens browser → /oauth2/authorize/{connectionId}
    ↓
Server generates state UUID (CSRF protection)
    ↓
Stores state → connectionId in memory map
    ↓
Redirects to provider (GitHub/GitLab/etc)
    ↓
User authorizes, provider redirects to /oauth2/callback?code=...&state=...
    ↓
Server exchanges code for access_token
    ↓
Saves token to connection.credentials
    ↓
Shows success HTML page (auto-closes)
    ↓
Desktop app polls, Mobile app resumes → Refreshes connection
```

**Current Implementation:**
- State storage: In-memory (`mutableMapOf<String, ConnectionId>()`)
- User context: ❌ NOT included (single user assumed)
- Callback endpoint: `/oauth2/callback` (public, no RPC headers)

**Limitations:**
- ❌ State lost on server restart
- ❌ Doesn't work with load balancer (multiple servers)
- ❌ Callback doesn't know which user authorized
- ❌ No multi-user support

### Multi-User Support Planning (Phase 2+)

#### Phase 2: Redis State Management (Q2 2026)

**Changes needed:**
1. Add Redis dependency: `spring-boot-starter-data-redis`
2. Replace in-memory state map with Redis
3. Store state as: `"{userId}:{connectionId}"` to include user context
4. Add 10-minute TTL to state keys

```kotlin
// BEFORE (Phase 1)
private val pendingAuthorizations = mutableMapOf<String, ConnectionId>()

suspend fun getAuthorizationUrl(connectionId: ConnectionId): String {
    val state = UUID.randomUUID().toString()
    pendingAuthorizations[state] = connectionId  // Lost on restart!
    return buildAuthorizationUrl(...)
}

// AFTER (Phase 2)
suspend fun getAuthorizationUrl(connectionId: ConnectionId, userId: String): String {
    val state = UUID.randomUUID().toString()
    redisTemplate.opsForValue().set(
        "oauth2:state:$state",
        "$userId:$connectionId",
        Duration.ofMinutes(10)
    )
    return buildAuthorizationUrl(...)
}

suspend fun handleCallback(code: String, state: String): OAuth2CallbackResult {
    val stateValue = redisTemplate.opsForValue().getAndDelete("oauth2:state:$state")
        ?: return OAuth2CallbackResult.InvalidState(...)
    
    val (userId, connectionId) = stateValue.split(":")
    // Now we know which user and which connection!
}
```

#### Phase 3: Mobile Deep Link Handling (Q3 2026)

**iOS:**
- Register URL scheme in `Info.plist`
- Implement handler for `jervis://oauth2/callback`
- Auto-refresh connection on app resume

**Android:**
- Create `OAuth2CallbackActivity`
- Register intent filter for callback URL
- Parse code/state parameters
- Trigger connection refresh

**Desktop:**
- Poll server for connection token update (polling approach)
- Or: Handle native window close event with callback
- Timeout: 5 minutes max wait

#### Phase 4: Token Management (Q4 2026)

**Features:**
- Store refresh tokens alongside access tokens
- Automatic token refresh before expiration
- Track `authorizedBy: String` + `authorizedAt: Instant` in connection
- Provider-specific refresh strategies

**Updated Entity:**
```kotlin
data class ConnectionDocument(
    // ... existing fields ...
    
    // OAuth2 specific (Phase 2+)
    val authorizedBy: String? = null,      // NEW: User ID who authorized
    val authorizedAt: Instant? = null,     // NEW: When authorized
    
    sealed class Credentials {
        data class Bearer(val token: String) : Credentials()
        data class Basic(val username: String, val password: String) : Credentials()
        data class OAuthCredentials(       // NEW: Phase 2+
            val accessToken: String,
            val refreshToken: String?,
            val expiresAt: Instant?,
            val provider: String,
            val authorizedBy: String,
            val authorizedAt: Instant
        ) : Credentials()
    }
)
```

#### Multi-User Security Considerations

1. **State Token Security**
   - ✓ Random & unique (UUID)
   - ✓ Short-lived (10 minutes)
   - ✓ Single-use (delete after use)
   - ✓ HTTPS only

2. **Token Storage**
   - ✓ Encrypted at rest (Redis encryption)
   - ✓ Per-connection (each has its own)
   - ✓ Per-user (track who authorized)
   - ✓ Audit trail (authorizedBy + authorizedAt)

3. **Callback Validation**
   - ✓ State token must exist and match
   - ✓ CSRF protection via state
   - ✓ Code exchange includes client secret
   - ✓ Only trusted provider URLs allowed

### Migration Timeline

| Phase | Timeline | What | Status |
|-------|----------|------|--------|
| **1** | Feb 2026 | Test button + OAuth2 button in UI | ✅ DONE |
| **2** | Q2 2026 | Redis state storage + user context | 📋 PLANNED |
| **3** | Q3 2026 | Mobile deep links + polling | 📋 PLANNED |
| **4** | Q4 2026 | Token refresh + expiration mgmt | 📋 PLANNED |

---

## Summary

**Current State (Phase 1):**
- Test Connection button works for all types
- OAuth2 flow: in-memory state, single user
- Desktop: polls for token, Mobile: polls on resume
- No token refresh support

**Future Improvements:**
- Redis for distributed state management
- Per-user state tokens with user context
- Mobile deep link handling
- Automatic token refresh with expiration tracking
- Comprehensive audit trail (who authorized when)