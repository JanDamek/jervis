# JERVIS Knowledge Graph Design

## Přehled

Knowledge Graph (ArangoDB) slouží jako centrální úložiště strukturovaných vztahů mezi všemi entitami v systému. Každý klient má izolovaný graf, což zajišťuje multi-tenancy.

**Integrace s RAG (Knowledge Base):**
- Každý vrchol má volitelné `ragChunks: List<String>` - seznam chunk IDs z Weaviate
- Každá hrana může mít textový `description` property, který se ukládá do RAG
- Semantické vyhledávání v RAG najde chunky → z chunk metadat získáme graph nodeKey/edgeKey
- Graph poskytuje strukturovanou navigaci, RAG poskytuje sémantické hledání

## ArangoDB Schéma - "Two-Collection" přístup

### Multi-tenancy architektura

Pro každého klienta Jervis vytváří **3 ArangoDB objekty**:

1. **`c{clientId}_nodes`** - Document Collection
   - Jeden velký koš pro všechny typy vrcholů (Users, Jira tickets, soubory, commits, Confluence pages...)
   - Heterogenní graf = maximální flexibilita pro AI agenta
   - Každý dokument má atribut **`type`** pro rozlišení entity

2. **`c{clientId}_edges`** - Edge Collection
   - Všechny hrany mezi vrcholy
   - Atribut **`edgeType`** určuje typ vztahu

3. **`c{clientId}_graph`** - Named Graph
   - ArangoDB Named Graph pro optimalizované traversal queries
   - Definice: `c{clientId}_nodes` → `c{clientId}_edges` → `c{clientId}_nodes`
   - Umožňuje použití AQL syntaxe: `FOR v IN 1..3 ANY startNode GRAPH 'c123_graph'`

### Struktura dokumentu v `c{clientId}_nodes`

```json
{
  "_key": "jira::JERV-123",
  "type": "jira_issue",           // POVINNÝ atribut - diskriminátor typu
  "ragChunks": ["chunk_uuid_1"],  // volitelný - chunk IDs z Weaviate
  // ... libovolné properties specifické pro daný typ
  "summary": "Fix login bug",
  "status": "In Progress"
}
```

**Důležité:**
- Atribut `type` je **indexován** (Persistent Index) pro rychlé filtrování
- Bez indexu by dotazy typu `FILTER doc.type == 'jira_issue'` musely procházet miliony záznamů
- Doporučené hodnoty: `user`, `jira_issue`, `commit`, `file`, `confluence_page`, `email`, atd.

### Struktura edge dokumentu v `c{clientId}_edges`

```json
{
  "_key": "mentions::jira::JERV-123->file::Service.kt",
  "edgeType": "mentions",         // POVINNÝ atribut - typ hrany
  "_from": "c123_nodes/jira::JERV-123",
  "_to": "c123_nodes/file::Service.kt"
}
```

### Výhody "Two-Collection" přístupu

✅ **Flexibilita:** AI agent může vytvořit nové typy entit bez změny schématu databáze
✅ **Jednoduchost:** Žádné složité kombinace edge definitions mezi 50 různými kolekcemi
✅ **Multi-tenancy:** Nový klient = 3 objekty (2 kolekce + 1 graph), hotovo
✅ **AQL Traversal:** Named Graph umožňuje efektivní graph queries

### Inicializace schématu

Schema se vytváří automaticky při volání `GraphDBService.ensureSchema(clientId)`:

1. Vytvoří kolekci `c{clientId}_nodes` (pokud neexistuje)
2. Vytvoří Persistent Index na `type`
3. Vytvoří kolekci `c{clientId}_edges` jako Edge Collection
4. Vytvoří Persistent Index na `edgeType`
5. Vytvoří Named Graph `c{clientId}_graph` s edge definition: `nodes → edges → nodes`

### AQL Dotazy

**Příklad 1: Najdi všechny Jira tickets přiřazené uživateli:**

```aql
FOR user IN c123_nodes
  FILTER user.type == 'user' AND user.email == 'jan@example.com'

  FOR ticket IN 1..1 INBOUND user._id GRAPH 'c123_graph'
    FILTER ticket.type == 'jira_issue'
    RETURN ticket
```

**Příklad 2: Najdi všechny soubory změněné v posledním týdnu:**

```aql
FOR commit IN c123_nodes
  FILTER commit.type == 'commit'
    AND commit.timestamp > DATE_SUBTRACT(DATE_NOW(), 7, 'day')

  FOR file, edge IN 1..1 OUTBOUND commit._id GRAPH 'c123_graph'
    FILTER file.type == 'file' AND edge.edgeType == 'modifies'
    RETURN DISTINCT file
```

## Typy vrcholů (Nodes)

### 1. CODE - Kódová struktura

```kotlin
// Soubor
nodeKey: "file::src/main/kotlin/com/jervis/Service.kt"
type: "file"
props: {
    path: String,              // relativní cesta v projektu
    extension: String,         // kt, java, py, ...
    language: String,          // kotlin, java, python
    linesOfCode: Int,
    lastModified: Instant,
    gitCommitHash: String?,    // poslední commit co změnil soubor
}
ragChunks: ["chunk_uuid_1", "chunk_uuid_2"]  // obsah souboru rozdělený na chunky

// Package/Module
nodeKey: "package::com.jervis.service"
type: "package"
props: {
    name: String,
    description: String?,
}

// Class
nodeKey: "class::com.jervis.service.UserService"
type: "class"
props: {
    name: String,
    qualifiedName: String,
    isInterface: Boolean,
    isAbstract: Boolean,
    visibility: String,        // public, private, internal
    filePath: String,
}
ragChunks: ["chunk_uuid_class_doc"]

// Method/Function
nodeKey: "method::com.jervis.service.UserService.createUser"
type: "method"
props: {
    name: String,
    signature: String,         // "createUser(name: String, email: String): User"
    returnType: String,
    isConstructor: Boolean,
    isSuspend: Boolean,
    visibility: String,
    complexity: Int?,          // cyclomatic complexity
}
ragChunks: ["chunk_uuid_method_impl"]

// Variable/Field
nodeKey: "field::com.jervis.service.UserService.userRepository"
type: "field"
props: {
    name: String,
    type: String,
    isConst: Boolean,
    visibility: String,
}
```

### 2. VCS - Version Control

```kotlin
// Git Commit
nodeKey: "commit::{commitHash}"
type: "commit"
props: {
    hash: String,
    shortHash: String,         // prvních 7 znaků
    message: String,
    authorName: String,
    authorEmail: String,
    committerName: String,
    committerEmail: String,
    timestamp: Instant,
    branchName: String?,
    parentHashes: List<String>,
}
ragChunks: ["chunk_commit_msg", "chunk_commit_diff"]

// Git Branch
nodeKey: "branch::{branchName}"
type: "branch"
props: {
    name: String,
    isDefault: Boolean,
    lastCommitHash: String,
    createdAt: Instant?,
}

// Pull Request / Merge Request
nodeKey: "pr::{prNumber}"
type: "pull_request"
props: {
    number: Int,
    title: String,
    state: String,            // open, closed, merged
    authorName: String,
    createdAt: Instant,
    mergedAt: Instant?,
    sourceBranch: String,
    targetBranch: String,
}
ragChunks: ["chunk_pr_description", "chunk_pr_review"]
```

### 3. ISSUE TRACKING - Jira, GitHub Issues

```kotlin
// Jira Ticket
nodeKey: "jira::{projectKey}-{issueNumber}"  // např. "jira::JERV-123"
type: "jira_issue"
props: {
    key: String,              // JERV-123
    summary: String,
    description: String?,
    issueType: String,        // Story, Bug, Task, Epic
    status: String,           // To Do, In Progress, Done
    priority: String,         // High, Medium, Low
    assignee: String?,
    reporter: String,
    createdAt: Instant,
    updatedAt: Instant,
    dueDate: Instant?,
    storyPoints: Int?,
    labels: List<String>,
}
ragChunks: ["chunk_jira_desc", "chunk_jira_comments"]

// Jira Comment
nodeKey: "jira_comment::{issueKey}-{commentId}"
type: "jira_comment"
props: {
    author: String,
    body: String,
    createdAt: Instant,
    updatedAt: Instant?,
}
ragChunks: ["chunk_comment_content"]

// Jira Sprint
nodeKey: "sprint::{sprintId}"
type: "sprint"
props: {
    name: String,
    state: String,            // future, active, closed
    startDate: Instant?,
    endDate: Instant?,
    goal: String?,
}
```

### 4. DOCUMENTATION - Confluence, Wiki

```kotlin
// Confluence Page
nodeKey: "confluence::{spaceKey}::{pageId}"
type: "confluence_page"
props: {
    title: String,
    spaceKey: String,
    spaceName: String,
    version: Int,
    createdAt: Instant,
    updatedAt: Instant,
    authorName: String,
    lastModifierName: String,
    url: String,
    labels: List<String>,
}
ragChunks: ["chunk_page_1", "chunk_page_2", ...]

// Confluence Attachment
nodeKey: "confluence_attachment::{attachmentId}"
type: "confluence_attachment"
props: {
    title: String,
    mediaType: String,
    fileSize: Long,
    downloadUrl: String,
}
```

### 5. COMMUNICATION - Email, Slack, Teams

```kotlin
// Email
nodeKey: "email::{messageId}"
type: "email"
props: {
    subject: String,
    from: String,
    to: List<String>,
    cc: List<String>,
    sentAt: Instant,
    receivedAt: Instant,
    hasAttachments: Boolean,
    threadId: String?,        // pro konverzace
}
ragChunks: ["chunk_email_body"]

// Email Thread
nodeKey: "email_thread::{threadId}"
type: "email_thread"
props: {
    subject: String,
    participants: List<String>,
    messageCount: Int,
    startedAt: Instant,
    lastMessageAt: Instant,
}

// Slack Message
nodeKey: "slack::{channelId}::{messageTs}"
type: "slack_message"
props: {
    channelId: String,
    channelName: String,
    authorId: String,
    authorName: String,
    text: String,
    timestamp: Instant,
    threadTs: String?,        // parent message timestamp pokud je to reply
    reactionCount: Int,
}
ragChunks: ["chunk_slack_msg"]

// Teams Message
nodeKey: "teams::{channelId}::{messageId}"
type: "teams_message"
props: {
    channelId: String,
    channelName: String,
    authorName: String,
    subject: String?,
    body: String,
    timestamp: Instant,
}
ragChunks: ["chunk_teams_msg"]
```

### 6. MEETINGS & CALENDAR

```kotlin
// Meeting
nodeKey: "meeting::{meetingId}"
type: "meeting"
props: {
    title: String,
    startTime: Instant,
    endTime: Instant,
    organizer: String,
    attendees: List<String>,
    location: String?,
    isRecurring: Boolean,
    meetingUrl: String?,      // Teams/Zoom link
}
ragChunks: ["chunk_meeting_notes", "chunk_meeting_transcript"]
```

### 7. USERS & TEAMS

```kotlin
// User
nodeKey: "user::{email}"
type: "user"
props: {
    email: String,
    name: String,
    displayName: String?,
    jobTitle: String?,
    department: String?,
    isActive: Boolean,
}

// Team
nodeKey: "team::{teamId}"
type: "team"
props: {
    name: String,
    description: String?,
}
```

### 8. JOERN - Code Property Graph

```kotlin
// Joern importuje CPG (Code Property Graph) z master větve
// Tyto vrcholy mají prefix "joern::"

// Joern Method Node
nodeKey: "joern::method::{fullName}"
type: "joern_method"
props: {
    name: String,
    fullName: String,
    signature: String,
    filename: String,
    lineNumber: Int,
    columnNumber: Int,
    isExternal: Boolean,
}

// Joern Call Site
nodeKey: "joern::call::{methodFullName}::{lineNumber}"
type: "joern_call"
props: {
    methodFullName: String,
    name: String,              // název volané metody
    lineNumber: Int,
    argumentIndex: Int,
}

// Joern Parameter
nodeKey: "joern::param::{methodFullName}::{name}"
type: "joern_param"
props: {
    name: String,
    typeFullName: String,
    index: Int,
}

// Joern Local Variable
nodeKey: "joern::local::{methodFullName}::{name}"
type: "joern_local"
props: {
    name: String,
    typeFullName: String,
}
```

## Typy hran (Edges)

### CODE vztahy

```kotlin
// Struktura kódu
file --[contains]--> class
file --[contains]--> method              // top-level funkce
package --[contains]--> file
class --[contains]--> method
class --[contains]--> field
class --[extends]--> class               // dědičnost
class --[implements]--> class            // interface implementation
method --[calls]--> method               // volání metod
method --[accesses]--> field             // přístup k fieldům
method --[returns]--> class              // návratový typ
method --[parameter]--> class            // typ parametru
field --[type]--> class                  // typ fieldu

// Import dependencies
file --[imports]--> package
file --[imports]--> class
```

### VCS vztahy

```kotlin
// Git struktura
commit --[modifies]--> file              // commit mění soubor
commit --[creates]--> file               // commit vytváří nový soubor
commit --[deletes]--> file               // commit maže soubor
commit --[parent]--> commit              // parent commit
branch --[contains]--> commit            // commits na větvi
commit --[authored_by]--> user           // autor commitu

// Code changes v commitu
commit --[modifies_method]--> method     // commit mění konkrétní metodu
commit --[modifies_class]--> class       // commit mění třídu
commit --[renames]--> file               // commit přejmenoval soubor
```

### ISSUE TRACKING vztahy

```kotlin
// Jira vztahy
jira_issue --[blocks]--> jira_issue
jira_issue --[relates_to]--> jira_issue
jira_issue --[duplicates]--> jira_issue
jira_issue --[parent_of]--> jira_issue   // Epic -> Story
jira_issue --[assigned_to]--> user
jira_issue --[reported_by]--> user
jira_issue --[in_sprint]--> sprint
jira_issue --[has_comment]--> jira_comment
jira_comment --[authored_by]--> user

// Jira ↔ Code propojení
jira_issue --[mentions_file]--> file
jira_issue --[mentions_class]--> class
jira_issue --[mentions_method]--> method
commit --[fixes]--> jira_issue           // "fix JERV-123" v commit message
commit --[implements]--> jira_issue      // "implement JERV-456"
pull_request --[resolves]--> jira_issue
```

### DOCUMENTATION vztahy

```kotlin
// Confluence struktura
confluence_page --[child_of]--> confluence_page
confluence_page --[has_attachment]--> confluence_attachment
confluence_page --[authored_by]--> user
confluence_page --[last_modified_by]--> user

// Confluence ↔ ostatní entity
confluence_page --[documents]--> class
confluence_page --[documents]--> method
confluence_page --[references]--> jira_issue
confluence_page --[references]--> commit
confluence_page --[design_decision_for]--> package
```

### COMMUNICATION vztahy

```kotlin
// Email
email --[part_of_thread]--> email_thread
email --[sent_by]--> user
email --[sent_to]--> user
email --[cc_to]--> user
email --[mentions]--> jira_issue
email --[mentions]--> pull_request
email --[discusses]--> confluence_page

// Slack/Teams
slack_message --[reply_to]--> slack_message
slack_message --[posted_by]--> user
slack_message --[mentions]--> user
slack_message --[mentions]--> jira_issue
slack_message --[shares]--> confluence_page
teams_message --[posted_by]--> user
```

### MEETINGS vztahy

```kotlin
meeting --[organized_by]--> user
meeting --[attended_by]--> user
meeting --[discusses]--> jira_issue
meeting --[discusses]--> confluence_page
meeting --[results_in]--> jira_issue     // meeting vytvořil ticket
```

### JOERN vztahy

```kotlin
// Code Property Graph z Joern
joern_method --[contains]--> joern_call
joern_method --[contains]--> joern_param
joern_method --[contains]--> joern_local
joern_call --[calls]--> joern_method
joern_call --[argument]--> joern_param

// Propojení Joern ↔ naše code entity
joern_method --[represents]--> method    // link mezi Joern CPG a našimi entities
file --[analyzed_by_joern]--> joern_method
```

### Časové a kontextové hrany

```kotlin
// Časová posloupnost
email --[followed_by]--> commit          // email o bugu → fix commit
jira_issue --[followed_by]--> commit
meeting --[followed_by]--> jira_issue

// Znalostní vazby (odvozené z RAG semantic similarity)
method --[similar_to]--> method          // sémanticky podobné metody
confluence_page --[related_to]--> confluence_page
jira_issue --[conceptually_related]--> jira_issue
```

## RAG integrace

### Ukládání do RAG

Každý vrchol/hrana má:
```kotlin
data class GraphNode(
    val key: String,
    val type: String,
    val props: Map<String, Any?>,
    val ragChunks: List<String> = emptyList(),  // UUIDs Weaviate chunks
)
```

**Při vytváření/update vrcholu:**
1. Extrahujeme textový obsah z `props` (description, code, message, body, ...)
2. Rozdělíme na chunky (max 512 tokenů)
3. Uložíme chunky do Weaviate s metadaty:
   ```json
   {
     "text": "chunk content",
     "metadata": {
       "source": "graph",
       "graphNodeKey": "file::src/main/Service.kt",
       "type": "file",
       "clientId": "...",
       "projectId": "...",
       "timestamp": "..."
     }
   }
   ```
4. Do GraphNode uložíme list chunk UUIDs

**Při semantickém hledání:**
1. RAG najde relevantní chunky
2. Z chunk metadata získáme `graphNodeKey`
3. GraphDBService načte celý vrchol/hranu
4. Vrátíme strukturovaná data + kontext z grafu

### Edge descriptions v RAG

Hrany mohou mít textový popis:
```kotlin
edge {
    edgeType = "documents",
    fromKey = "confluence::SPACE::123",
    toKey = "class::UserService",
    props = {
        "description": "Confluence page explains the business logic of UserService class and its role in authentication flow",
        "confidence": 0.95,
        "extractedBy": "agent",
    }
}
```

Popis se uloží do RAG chunk s metadata: `graphEdgeKey = "{fromKey}--[{edgeType}]-->{toKey}"`

## Implementace v GraphTools

```kotlin
@Tool
@LLMDescription("Search graph using semantic query. Finds nodes/edges based on text meaning, not just keywords.")
fun semanticSearch(
    @LLMDescription("Natural language query describing what you're looking for")
    query: String,

    @LLMDescription("Optional entity type filter (file, class, jira_issue, commit, etc.)")
    type: String = "",

    @LLMDescription("Maximum results")
    limit: Int = 10,
): String {
    // 1. RAG semantic search
    val chunks = ragService.search(query, filters = mapOf("source" to "graph", "entityType" to entityType), limit = limit)

    // 2. Extract graph keys from chunks
    val nodeKeys = chunks.mapNotNull { it.metadata["graphNodeKey"] as? String }.distinct()

    // 3. Load full nodes from graph
    val nodes = nodeKeys.map { graphService.getNode(clientId, it) }

    // 4. Return results
    return formatResults(nodes)
}

@Tool
@LLMDescription("Find related entities with semantic reasoning. Combines graph traversal + RAG search.")
fun findRelatedWithContext(
    @LLMDescription("Starting node key")
    nodeKey: String,

    @LLMDescription("What kind of relationship/context are you looking for?")
    relationshipQuery: String,

    @LLMDescription("Maximum depth to traverse")
    maxDepth: Int = 2,
): String {
    // 1. Graph traversal - get connected nodes
    val connectedNodes = graphService.traverse(clientId, nodeKey, TraversalSpec(maxDepth))

    // 2. Load RAG chunks for each node
    val nodesWithContent = connectedNodes.map { node ->
        val chunks = node.ragChunks.map { chunkId -> ragService.getChunk(chunkId) }
        node to chunks
    }

    // 3. Semantic filter using relationshipQuery
    val relevant = nodesWithContent.filter { (node, chunks) ->
        val allText = chunks.joinToString(" ") { it.text }
        semanticRelevance(relationshipQuery, allText) > 0.7
    }

    return formatResults(relevant)
}
```

## Příklady použití

### 1. Analýza Jira ticket dopadu na kód

**Agent dotaz:** "Který kód musím změnit pro JERV-123?"

```kotlin
// 1. Najdi ticket
val ticket = graphService.getNode("jira::JERV-123")

// 2. Semantic search v ticketu pro klíčová slova
val mentionedClasses = graphService.getRelated("jira::JERV-123", edgeTypes=["mentions_class"])

// 3. Najdi related commits
val relatedCommits = graphService.getRelated("jira::JERV-123", edgeTypes=["fixes", "implements"], direction=INBOUND)

// 4. Z commitů najdi změněné soubory
relatedCommits.forEach { commit ->
    val modifiedFiles = graphService.getRelated(commit.key, edgeTypes=["modifies", "creates"])
}

// 5. RAG semantic search v confluence dokumentaci
val docs = semanticSearch("JERV-123 implementation architecture")
```

### 2. Impact analysis - změna metody

**Agent dotaz:** "Co všechno se rozbije, když změním metodu UserService.authenticate()?"

```kotlin
// 1. Najdi metodu
val method = "method::com.jervis.service.UserService.authenticate"

// 2. Kdo volá tuto metodu? (direct callers)
val callers = graphService.getRelated(method, edgeTypes=["calls"], direction=INBOUND)

// 3. Které testy ji testují?
val tests = graphService.getRelated(method, edgeTypes=["tests"], direction=INBOUND)

// 4. Joern CPG analýza - data flow
val joernMethod = graphService.getRelated(method, edgeTypes=["represents"], direction=INBOUND)
val dataFlows = graphService.traverse(joernMethod[0].key, TraversalSpec(maxDepth=5, edgeTypes=["argument", "calls"]))

// 5. Které Jira tickets to zmiňují?
val relatedTickets = graphService.getRelated(method, edgeTypes=["mentions_method"], direction=INBOUND, entityType="jira_issue")
```

### 3. Knowledge discovery - email → ticket → code

**Uživatel:** "Někdo mi poslal email o problému s přihlašováním"

```kotlin
// 1. Najdi email semanticky
val emails = semanticSearch("login authentication problem", entityType="email")

// 2. Z emailu najdi zmíněné tickets
val tickets = graphService.getRelated(emails[0].key, edgeTypes=["mentions"])

// 3. Z ticketů najdi related commits
val commits = graphService.getRelated(tickets[0].key, edgeTypes=["fixes", "implements"], direction=INBOUND)

// 4. Z commitů najdi změněné soubory a metody
val changedCode = commits.flatMap { commit ->
    graphService.getRelated(commit.key, edgeTypes=["modifies_method", "modifies_class"])
}

// 5. Confluence dokumentace k těmto třídám
val docs = changedCode.flatMap { code ->
    graphService.getRelated(code.key, edgeTypes=["documents"], direction=INBOUND, entityType="confluence_page")
}
```

## Implementační status

### ✅ Hotovo (2026-02-01)

1. **KnowledgeServiceImpl - Relationship extraction & storage:**
   - ✅ `extractRelationships()` - Podpora pipe/arrow/bracket formátů
   - ✅ `buildGraphPayload()` - Parsing a normalizace relationships
   - ✅ `persistGraph()` - Evidence-based edge storage
   - ✅ Short-hand node expansion (TASK-123 → jira:TASK-123)
   - ✅ Bidirectional linking (RAG ↔ Graph)

2. **GraphTools - Basic operations:**
   - ✅ `searchKnowledgeBase()` - Hybrid RAG + Graph search
   - ✅ `getRelated()` - Direct neighbor lookup
   - ✅ `traverse()` - Multi-hop graph traversal
   - ✅ `storeRelationship()` - Manual edge creation

3. **Indexery pro jednotlivé zdroje:**
   - ✅ `JiraContinuousIndexer` - issues + comments → PendingTasks
   - ✅ `ConfluenceContinuousIndexer` - pages → PendingTasks
   - ✅ `EmailContinuousIndexer` - emails → PendingTasks
   - ✅ `GitContinuousIndexer` - commits → PendingTasks
   - ⚠️ **Qualifier Agent zodpovídá za graph indexing** (ne indexery!)

4. **Canonicalization & Alias resolution:**
   - ✅ Per-client MongoDB registry (GraphEntityRegistryDocument)
   - ✅ Conservative canonicalization (order:order_X → order:X)
   - ✅ Cache + mutex pro race condition prevention

### 🚧 TODO

1. **Rozšířit GraphTools:**
   - ⏳ `semanticSearch()` - RAG + Graph combined ranking
   - ⏳ `findRelatedWithContext()` - Traversal s semantic filtering
   - ⏳ `analyzeImpact()` - Impact analysis pro změny
   - ⏳ `traceRequirement()` - Od požadavku k implementaci

2. **Link discovery (automatická detekce):**
   - ⏳ Regex patterns v textech (JERV-123, @user, file paths)
   - ⏳ Semantic similarity pro soft links (podobné tickets/docs)
   - ⏳ LLM agent pro odvození relationship type
   - ⏳ Joern CPG integration (code structure → graph)

3. **Graph maintenance:**
   - ⏳ Garbage collection - staré nodes (TTL policy)
   - ⏳ Deduplication - merge nodes from different sources
   - ⏳ Consistency checks - detect broken edges
   - ⏳ Statistics - graph metrics dashboard

4. **Advanced features:**
   - ⏳ Relationship confidence scores
   - ⏳ Temporal relationships (validFrom/validTo)
   - ⏳ Relationship suggestions (RAG-based)
   - ⏳ Graph diff (změny v čase)
