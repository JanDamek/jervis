# Polling & Indexing Architecture

## Přehled

Jervis používá **3-stage pipeline** pro zpracování dat z externích systémů (Jira, Confluence, Email):

```
┌─────────────┐      ┌──────────────┐      ┌─────────────────┐      ┌────────────┐
│   Polling   │  →   │   Indexing   │  →   │  Pending Tasks  │  →   │  Qualifier │
│   Handler   │      │  Collection  │      │                 │      │   Agent    │
└─────────────┘      └──────────────┘      └─────────────────┘      └────────────┘
```

## Stage 1: Polling Handler

**Účel:** Stahování dat z externích API a ukládání do indexovací MongoDB kolekce.

### Responsibilities:

1. **Pravidelné spouštění** podle `ConnectionDocument` (např. každých 5 minut)
2. **Initial Sync vs Incremental Sync**:
   - **Initial Sync** (`lastSeenUpdatedAt == null`): Stahuje VŠECHNA data s **pagination**
   - **Incremental Sync**: Stahuje jen změny od posledního pollu (bez pagination)
3. **Deduplication** - kontroluje existenci podle `issueKey`/`messageId` (3 úrovně)
4. **Change detection** - ukládá dokument jako `NEW` pokud:
   - Dokument neexistuje (nový ticket/email)
   - Dokument existuje, ale `updatedAt` je novější (změna statusu, nový komentář)

### Initial Sync s Pagination

**DŮLEŽITÉ**: První spuštění stahuje VŠECHNA data, ne jen 1000 položek!

#### Jira Initial Sync
```kotlin
// Při prvním spuštění (lastSeenUpdatedAt == null)
val jql = "status NOT IN (Closed, Done, Resolved)"  // Jen OTEVŘENÉ issues
val allIssues = fetchAllIssuesWithPagination(
    query = jql,
    batchSize = 100  // 100 issues per batch
)
// Stáhne VŠECHNY otevřené issues (ne Done/Closed), max 10,000
```

#### Confluence Initial Sync
```kotlin
// Při prvním spuštění
val cql = if (spaceKey != null) "space = \"$spaceKey\"" else null
val allPages = fetchAllPagesWithPagination(
    spaceKey = spaceKey,
    batchSize = 100  // 100 pages per batch
)
// Stáhne VŠECHNY pages, max 10,000
```

### Incremental Sync (běžný polling)

```kotlin
// Po initial syncu (lastSeenUpdatedAt != null)
val jql = "updated >= \"2025-01-01 12:00\""  // Jen změny
val changes = fetchFullIssues(
    query = jql,
    maxResults = 1000  // Stačí, nejsou to všechna data
)
```

### Deduplication (4 úrovně)

1. **Pagination-level**: Filtr duplikátů **během sběru** z API
   ```kotlin
   val seenIssueIds = mutableSetOf<String>()
   batch.filter { issue ->
       seenIssueIds.add(getIssueId(issue))  // false if already seen
   }
   ```

2. **Repository-level (first check)**: `findExisting()` před zpracováním
   ```kotlin
   val existing = findExisting(connectionDocument.id, issueId)
   if (existing != null && existing.updatedAt >= issue.updatedAt) {
       skipped++  // No changes
       continue
   }
   ```

3. **Repository-level (double-check)**: Race condition protection před `save()`
   ```kotlin
   // Pro NOVÉ issues - double-check před uložením
   val doubleCheck = findExisting(connectionDocument.id, issueId)
   if (doubleCheck != null) {
       logger.debug { "Issue appeared during processing, skipping" }
       skipped++
   } else {
       saveIssue(fullIssue)
   }
   ```

4. **MongoDB unique index**: `(connectionDocumentId, issueKey)` jako final fail-safe

### ⚠️ CO SE NESMÍ:

- ❌ Stahovat dokument, který už v indexing kolekci je a nemá změny
- ❌ Vytvářet PendingTask přímo - to dělá až Indexer
- ❌ Volat RAG/Weaviate - to dělá až Qualifier Agent

### ✅ CO SE MÁ:

- ✅ Kontrolovat `jiraUpdatedAt` vs `existing.jiraUpdatedAt`
- ✅ Ukládat jako `state = "NEW"` pouze při změně
- ✅ Ukládat **FULL data** (description, comments, attachments)
- ✅ Update `connectionDocument.pollingStates` po úspěšném pollu

## Stage 2: Indexing Collection (MongoDB)

**Účel:** Dočasné úložiště pro dokumenty čekající na zpracování.

### Schema: `jira_issues` (Sealed Class)

**BREAKING CHANGE**: Od verze s sealed class je kolekce polymorfní!

```kotlin
sealed class JiraIssueIndexDocument {
    abstract val id: ObjectId
    abstract val issueKey: String
    abstract val state: String
    abstract val jiraUpdatedAt: Instant

    // NEW: Full data from Jira API
    data class New(
        override val id: ObjectId,
        val clientId: ClientId,
        val connectionDocumentId: ConnectionId,
        override val issueKey: String,
        val summary: String,
        val description: String?,              // FULL HTML/ADF
        val issueType: String,
        val status: String,
        val comments: List<JiraComment>,       // FULL comments
        val attachments: List<JiraAttachment>,
        // ... all fields
        override val jiraUpdatedAt: Instant,
    ) : JiraIssueIndexDocument() {
        override val state = "NEW"
    }

    // INDEXED: Minimal tracking (content in RAG/Graph)
    data class Indexed(
        override val id: ObjectId,
        val clientId: ClientId,
        val connectionDocumentId: ConnectionId,
        override val issueKey: String,
        override val jiraUpdatedAt: Instant,
    ) : JiraIssueIndexDocument() {
        override val state = "INDEXED"
    }

    // FAILED: Full data + error (for retry)
    data class Failed(
        /* same as New + */
        val indexingError: String,
    ) : JiraIssueIndexDocument() {
        override val state = "FAILED"
    }
}
```

### State Machine:

```
NEW → INDEXED (content cleanup: delete full doc, insert minimal)
 ↓
FAILED (keep full data for retry)
```

- **NEW**: Full issue data, čeká na zpracování
- **INDEXED**: PendingTask vytvořen, **content smazán** (zůstane jen id, issueKey, jiraUpdatedAt)
- **FAILED**: Chyba + full data pro retry

### Content Cleanup

**DŮLEŽITÉ**: Po vytvoření `PendingTask` se maže plný content!

```kotlin
// Před: NEW document (5 KB - full description, comments, attachments)
JiraIssueIndexDocument.New(
    id = ObjectId(...),
    issueKey = "SDB-123",
    description = "Long description...",
    comments = [...],  // 50 comments
    attachments = [...],
)

// Po markAsIndexed(): INDEXED document (0.5 KB - jen tracking)
JiraIssueIndexDocument.Indexed(
    id = ObjectId(...),
    issueKey = "SDB-123",
    jiraUpdatedAt = Instant.now(),
)
```

**Proč?** Skutečný content je v RAG/Graph (přes `sourceUrn`), není potřeba duplikovat v MongoDB.

### Migration

```javascript
// Před spuštěním nové verze:
db.jira_issues.drop()
db.confluence_pages.drop()

// Vše se re-indexuje při initial sync
```

## Stage 3: Continuous Indexer

**Účel:** Převod indexing dokumentů na PendingTasks pro Qualifier Agent.

### Responsibilities:

1. **Polling MongoDB** - `continuousNewIssuesAllAccounts()` (kontinuální flow, 30s delay když prázdné)
2. **Type check** - pouze `JiraIssueIndexDocument.New` (sealed class type safety)
3. **Clean HTML** - Tika extracts plain text from Jira HTML/ADF
4. **Build content** - připravit Markdown s FULL informacemi pro Qualifier Agent
5. **Create PendingTask** - s `correlationId` pro deduplication
6. **Mark as INDEXED** - `markAsIndexed(doc)` → content cleanup

### Příklad: JiraContinuousIndexer

```kotlin
// 1. Continuous flow z MongoDB (všechny accounts, newest first)
stateManager.continuousNewIssuesAllAccounts().collect { doc ->

    // 2. Type check - pouze NEW documents (sealed class)
    if (doc !is JiraIssueIndexDocument.New) {
        logger.warn { "Received non-NEW document: ${doc.issueKey}" }
        return
    }

    // 3. Clean HTML/Jira markup přes Tika
    val cleanDescription = if (!doc.description.isNullOrBlank()) {
        tikaTextExtractionService.extractPlainText(
            content = doc.description,
            fileName = "jira-${doc.issueKey}.html",
        )
    } else ""

    val cleanComments = doc.comments.map { comment ->
        comment.copy(
            body = tikaTextExtractionService.extractPlainText(
                content = comment.body,
                fileName = "jira-comment.html",
            )
        )
    }

    // 4. Build content s FULL informacemi
    val content = buildString {
        append("# ${doc.issueKey}: ${doc.summary}\n\n")
        append("**Type:** ${doc.issueType}\n")
        append("**Status:** ${doc.status}\n")
        append("**Priority:** ${doc.priority ?: "N/A"}\n")
        append("**Assignee:** ${doc.assignee}\n")
        append("**Reporter:** ${doc.reporter}\n")
        append("**Created:** ${doc.createdAt}\n")
        append("**Updated:** ${doc.jiraUpdatedAt}\n\n")

        // DESCRIPTION (cleaned)
        if (cleanDescription.isNotBlank()) {
            append("## Description\n\n")
            append(cleanDescription)
            append("\n\n")
        }

        // LABELS
        if (doc.labels.isNotEmpty()) {
            append("**Labels:** ${doc.labels.joinToString(", ")}\n\n")
        }

        // COMMENTS (cleaned) - každý komentář zvlášť
        if (cleanComments.isNotEmpty()) {
            append("## Comments\n\n")
            cleanComments.forEachIndexed { index, comment ->
                append("### Comment ${index + 1} by ${comment.author}\n")
                append("**Created:** ${comment.created}\n\n")
                append(comment.body)
                append("\n\n")
            }
        }

        // ATTACHMENTS
        if (doc.attachments.isNotEmpty()) {
            append("## Attachments\n")
            doc.attachments.forEach { att ->
                append("- ${att.filename} (${att.mimeType}, ${att.size} bytes)\n")
            }
            append("\n")
        }

        // METADATA pro Qualifier routing
        append("## Document Metadata\n")
        append("- **Source:** Jira Issue\n")
        append("- **Document ID:** jira:${doc.issueKey}\n")
        append("- **Connection ID:** ${doc.connectionDocumentId}\n")
        append("- **Issue Key:** ${doc.issueKey}\n")
        append("- **Comment Count:** ${doc.comments.size}\n")
        append("- **Attachment Count:** ${doc.attachments.size}\n")
    }

    // 5. Create PendingTask (s deduplication)
    pendingTaskService.createTask(
        taskType = BUGTRACKER_PROCESSING,
        content = content,
        projectId = doc.projectId,
        clientId = doc.clientId,
        correlationId = "jira:${doc.issueKey}",
        sourceUrn = SourceUrn.jira(doc.connectionDocumentId, doc.issueKey),
    )

    // 6. Mark jako INDEXED → content cleanup
    stateManager.markAsIndexed(doc)

    logger.info { "Created BUGTRACKER_PROCESSING task for Jira issue: ${doc.issueKey}" }
}
```

### ⚠️ CO SE NESMÍ:

- ❌ Indexovat do RAG - to dělá Qualifier Agent
- ❌ Vytvářet graph nodes - to dělá Qualifier Agent
- ❌ Volat externí API - data jsou už v MongoDB

### ✅ CO SE MÁ:

- ✅ Připravit **kompletní content** pro AI (description + comments + attachments)
- ✅ Přidat metadata pro graph routing (issueKey, connectionId, změny)
- ✅ Použít `correlationId` pro deduplication
- ✅ Clean HTML/Jira markup přes Tika

## Stage 4: Qualifier Agent (Koog)

**Účel:** Rozhodnutí co s dokumentem udělat a indexace do Graph + RAG.

### Responsibilities:

1. **Read PendingTask** - z MongoDB podle `state = READY_FOR_QUALIFICATION`
2. **Decide action** - pomocí LLM a tools:
   - Indexovat do RAG? (ano/ne)
   - Vytvořit graph nodes? (ticket, user, connections)
   - Připojit k existujícímu? (stejný ticket, update)
3. **Execute** - volat GraphTools, RagTools
4. **Mark complete** - `state = PROCESSING_COMPLETED`

### Příklad flow:

```kotlin
// PendingTask content:
"""
# SDB-1821: Create new PMDA Report Generator

**Status:** In Progress → Done  // ← ZMĚNA!
**Assignee:** Boris Briozzo

## Description
Implement new report generator for PMDA compliance...

## Comments
### Comment by Jan Klaus
Testing completed, moving to Done.

## Document Metadata
- Issue Key: SDB-1821
- Connection ID: 69281563dd3757963c43e762
- Previous Status: In Progress
- New Status: Done
"""

// Qualifier Agent rozhodne:
1. UPDATE existing graph node SDB-1821
   - Change status: "In Progress" → "Done"
   - Add comment node + edge

2. INDEX new comment to RAG
   - Chunk: "Testing completed, moving to Done"
   - Metadata: { type: "jira_comment", issueKey: "SDB-1821" }

3. CREATE edge: SDB-1821 --[commented_by]--> user:jan.klaus
```

## Deduplication Strategy

### Polling Level (Stage 1)

```kotlin
// Handler kontroluje změny
val existing = repository.findByIssueKey(issueKey)

if (existing != null && existing.jiraUpdatedAt >= newIssue.updatedAt) {
    logger.debug { "Issue $issueKey unchanged, skipping" }
    return  // SKIP
}

// Uložit jako NEW (nový nebo změněný)
repository.save(newIssue.copy(state = "NEW"))
```

### Indexer Level (Stage 3)

```kotlin
// Deduplication pomocí correlationId
pendingTaskService.createTask(
    correlationId = "jira:SDB-1821",  // ← Unique per issue
    // ...
)

// V PendingTaskService:
val existing = repository.findByCorrelationId(correlationId)
if (existing != null) {
    logger.debug { "Task already exists, skipping" }
    return existing  // ← Prevents 19 duplicate tasks!
}
```

### Qualifier Level (Stage 4)

```kotlin
// Graph upsert - stejný _key = update místo insert
graphService.upsertNode(
    key = "jira::SDB-1821",  // ← Constant
    type = "jira_issue",
    // ... updated properties
)
```

## Příklad: Změna Jira ticketu

### T0: Initial state

```
MongoDB jira_issue_index:
  - issueKey: SDB-1821
  - status: "In Progress"
  - state: INDEXED
  - jiraUpdatedAt: 2025-12-13T10:00:00Z
```

### T1: User změní status v Jira

```
Jira API:
  - SDB-1821
  - status: "Done"  ← ZMĚNA
  - updated: 2025-12-13T15:00:00Z
```

### T2: Polling Handler (každých 5 min)

```kotlin
// Handler zjistí změnu
val jiraIssue = api.getIssue("SDB-1821")
val existing = mongo.findByIssueKey("SDB-1821")

if (jiraIssue.updated > existing.jiraUpdatedAt) {
    // ZMĚNA DETEKOVÁNA
    mongo.save(jiraIssue.copy(
        id = existing.id,           // ← STEJNÉ ID
        state = "NEW",              // ← RESET na NEW
        jiraUpdatedAt = jiraIssue.updated,
    ))
}
```

**MongoDB po pollu:**
```
jira_issue_index:
  - id: 693d77ad...  (STEJNÉ)
  - issueKey: SDB-1821
  - status: "Done"  ← UPDATED
  - state: NEW      ← RESET
  - jiraUpdatedAt: 2025-12-13T15:00:00Z
```

### T3: Continuous Indexer (kontinuální)

```kotlin
// Najde NEW dokument
val doc = mongo.findByState("NEW").first()

// Vytvoří PendingTask
pendingTaskService.createTask(
    correlationId = "jira:SDB-1821",  // ← Dedup
    content = """
        # SDB-1821: ...
        **Status:** Done
        ...
        ## Document Metadata
        - Previous Status: In Progress
        - New Status: Done
    """,
)

// Mark jako INDEXED
mongo.update(doc.copy(state = "INDEXED"))
```

**MongoDB po indexeru:**
```
jira_issue_index:
  - state: INDEXED  ← Hotovo

pending_tasks:
  - correlationId: "jira:SDB-1821"
  - content: "Status changed to Done"
  - state: READY_FOR_QUALIFICATION
```

### T4: Qualifier Agent

```kotlin
// Načte PendingTask
val task = mongo.findByState("READY_FOR_QUALIFICATION")

// LLM rozhodne
agent.processTask(task) {
    // Update graph node
    graphService.upsertNode(
        key = "jira::SDB-1821",
        type = "jira_issue",
        properties = mapOf("status" to "Done"),
    )

    // Index změny do RAG
    ragService.indexChunk(
        text = "SDB-1821 moved to Done status",
        metadata = mapOf("type" to "status_change"),
    )
}

// Mark jako COMPLETED
mongo.update(task.copy(state = "PROCESSING_COMPLETED"))
```

## Monitoring & Debugging

### Polling Logs

```
INFO  c.j.s.p.h.b.BugTrackerPollingHandlerBase - → Jira handler polling 1 client(s)
INFO  c.j.s.p.h.b.BugTrackerPollingHandlerBase -   Discovered 5 Jira issues for client Tepsivo
INFO  c.j.s.p.h.b.BugTrackerPollingHandlerBase -   Tepsivo: discovered=5, created=2, skipped=3
INFO  c.j.s.p.h.b.BugTrackerPollingHandlerBase - ← Jira handler completed | Total: discovered=5, created=2, skipped=3, errors=0
```

- **discovered**: Počet issues vrácených z API
- **created**: Počet NEW dokumentů (nové nebo změněné)
- **skipped**: Počet beze změn (existující s `jiraUpdatedAt < API.updated`)

### Indexer Logs

```
INFO  c.j.s.jira.JiraContinuousIndexer - Processing Jira issue SDB-1821 (Create new PMDA Report Generator)
INFO  c.j.s.jira.JiraContinuousIndexer - Created BUGTRACKER_PROCESSING task for Jira issue: SDB-1821
DEBUG c.j.s.jira.state.JiraStateManager - Marked Jira issue SDB-1821 as INDEXED (no chunks created)
```

- **Processing**: Začátek zpracování NEW dokumentu
- **Created task**: PendingTask vytvořen
- **Marked as INDEXED**: Dokument hotov, čeká na Qualifier

### Common Issues

**Problem: 19 duplicate tasks pro stejný ticket**

```json
{
  "correlationId": "jira:SDB-1821",
  "createdAt": "2025-12-13T14:26:53.893Z"
},
{
  "correlationId": "jira:SDB-1821",
  "createdAt": "2025-12-13T14:31:54.337Z"
},
// ... 17 more
```

**Cause:** Chybí deduplication v `PendingTaskService.createTask()`

**Fix:**
```kotlin
// Před save() zkontrolovat existenci
val existing = repository.findByCorrelationId(correlationId)
if (existing != null) return existing
```

---

**Problem: Content neobsahuje description ani comments**

```markdown
# SDB-1821: ...
**Status:** Done

## Document Metadata
...
```

**Cause:**
1. Polling handler neukládá full data (description, comments prázdné)
2. Indexer nemá co zpracovat

**Fix:**
```kotlin
// V JiraPollingHandler.fetchFullIssues()
JiraIssueIndexDocument(
    description = extractDescription(fields.description),  // ✅
    comments = fetchComments(issue.key),                   // ← ADD
    attachments = fetchAttachments(issue.key),             // ← ADD
)
```

---

**Problem: Polling vytváří NEW dokument každých 5 minut**

**Cause:** Nefunguje `lastSeenUpdatedAt` update

**Fix:**
```kotlin
// V BugTrackerPollingHandlerBase po úspěšném pollu
connectionService.save(
    connectionDocument.copy(
        pollingStates = pollingStates + ("JIRA" to PollingState(lastSeenUpdatedAt = maxUpdated))
    )
)
```
