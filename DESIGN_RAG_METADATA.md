# RAG Metadata Design - Universal Payload Structure

## Problem Statement

RagDocument je univerzální POJO pro **všechny** typy obsahu (CODE, EMAIL, MEETING, GIT_HISTORY, DOCUMENTATION, atd.).
Potřebujeme univerzální fieldy které budou dávat smysl napříč různými typy zdrojů a zároveň poskytovat agentovi dostatek
informací pro:

1. Pochopení kontextu (kdo/kdy/co)
2. Navigaci mezi souvisejícími chunky
3. Rozhodnutí zda fetchnout originál
4. Volbu správného MCP toolu pokud je potřeba další akce

## Current RagDocument Fields

### Existing Universal Fields (KEEP)

- `projectId: ObjectId?` - project scope
- `clientId: ObjectId` - client scope
- `summary: String` - actual content/text
- `ragSourceType: RagSourceType` - type discriminator (EMAIL, CODE, GIT_HISTORY, etc.)
- `createdAt: Instant` - temporal context
- `sourceUri: String?` - URI for fetching original (e.g., email://accountId/messageId)
- `chunkId: Int?` - chunk index
- `chunkOf: Int?` - total chunks count

### Code-Specific Fields (CODE/JOERN sources)

- `language: String?` - programming language
- `packageName: String?` - Java/Kotlin package
- `className: String?` - class name
- `parentClass: String?` - parent class
- `methodName: String?` - method name
- `lineStart: Int?` - start line
- `lineEnd: Int?` - end line
- `symbolName: String?` - symbol identifier
- `joernNodeId: String?` - Joern CPG node ID
- `safetyTags: List<String>` - security/safety markers

### Git-Specific Fields

- `gitCommitHash: String?` - commit hash
- `branch: String` - git branch
- `archivedAt: Instant?` - archival timestamp

### Email-Specific Fields (PROBLEM - too specific!)

- `emailMessageId: String?`
- `emailFrom: String?`
- `emailSubject: String?`
- `emailReceivedAt: String?`
- `attachmentIndex: Int?`
- `attachmentFileName: String?`
- `attachmentMimeType: String?`
- `totalAttachments: Int?`

### Vector Storage Fields

- `vectorStoreIds: List<String>` - Qdrant IDs

## Proposed Universal Metadata Fields

### Context Metadata (WHO/WHEN/WHERE)

```kotlin
// Universal "author/sender/creator" field
from: String?
// Examples:
// - EMAIL: sender email
// - GIT_HISTORY: commit author
// - MEETING: organizer
// - SLACK/TEAMS: message author

// Universal "title/subject/topic" field
subject: String?
// Examples:
// - EMAIL: email subject
// - MEETING: meeting title
// - GIT_HISTORY: commit message (first line)
// - DOCUMENTATION: document title

// Universal temporal marker (ISO 8601 date string)
timestamp: String?
// Examples:
// - EMAIL: received date
// - GIT_HISTORY: commit date
// - MEETING: meeting date
// - DOCUMENTATION: last modified date
```

### Relationship Metadata (PARENT/CHILD)

```kotlin
// Universal reference ID for grouping related chunks
parentRef: String?
// Examples:
// - EMAIL_ATTACHMENT: parent emailMessageId
// - EMAIL body chunks: emailMessageId (for grouping body chunks together)
// - GIT_HISTORY: commit hash
// - MEETING chunks: meeting ID
// - CODE method chunks: class qualified name

// Universal index within parent
indexInParent: Int?
// Examples:
// - EMAIL_ATTACHMENT: attachment index (0, 1, 2...)
// - EMAIL body: chunk index within email
// - MEETING: section/paragraph index
// - CODE: method position in class

// Total count of siblings
totalSiblings: Int?
// Examples:
// - EMAIL: total attachments count
// - MEETING: total sections
// - CODE: total methods in class
```

### Content Type Metadata

```kotlin
// Universal content/file type
contentType: String?
// Examples:
// - EMAIL_ATTACHMENT: MIME type (application/pdf, image/png)
// - DOCUMENTATION: file extension (.md, .rst)
// - CODE: language (kotlin, java)
// - AUDIO_TRANSCRIPT: audio format (mp3, wav)

// Universal filename/path
fileName: String?
// Examples:
// - EMAIL_ATTACHMENT: attachment filename
// - CODE: source file path
// - DOCUMENTATION: document path
// - MEETING: transcript filename
```

## Migration Strategy

### Phase 1: Add Universal Fields (Non-Breaking)

Add new universal fields to RagDocument:

- `from: String?`
- `subject: String?`
- `timestamp: String?`
- `parentRef: String?`
- `indexInParent: Int?`
- `totalSiblings: Int?`
- `contentType: String?`
- `fileName: String?`

Keep existing email-specific fields for backward compatibility.

### Phase 2: Update Indexing Services

Update all indexing services to populate universal fields:

#### EmailContentIndexer

```kotlin
from = message.from
subject = message.subject
timestamp = message.receivedAt.toString()
parentRef = message.messageId
totalSiblings = message.attachments.size
contentType = "text/html"
// Keep: emailMessageId, emailFrom, emailSubject (backward compat)
```

#### EmailAttachmentIndexer

```kotlin
from = message.from
subject = message.subject
timestamp = message.receivedAt.toString()
parentRef = message.messageId
indexInParent = attachmentIndex
totalSiblings = message.attachments.size
contentType = attachment.contentType
fileName = attachment.fileName
// Keep: attachmentIndex, attachmentFileName, attachmentMimeType (backward compat)
```

#### GitHistoryIndexingService

```kotlin
from = commit.author
subject = commit.message.lines().first()
timestamp = commit.date
parentRef = commit.hash
contentType = "git-commit"
```

#### MeetingTranscriptIndexingService

```kotlin
from = metadata.participants.joinToString(", ")
subject = metadata.title
timestamp = metadata.date?.toString()
parentRef = "meeting-${meetingFile.fileName}"
indexInParent = index
contentType = "meeting-transcript"
fileName = meetingFile.fileName.toString()
```

### Phase 3: Update LlmContentSynthesisStrategy

Update metadata extraction to use universal fields first, fall back to specific fields:

```kotlin
private fun extractRelevantMetadata(metadata: Map<String, String>): Map<String, String> {
    return buildMap {
        // Type discrimination
        metadata["ragSourceType"]?.let { type ->
            when (type) {
                "EMAIL" -> put("type", "email")
                "EMAIL_ATTACHMENT" -> {
                    val fileName = metadata["fileName"] ?: metadata["attachmentFileName"] ?: "unknown"
                    val index = metadata["indexInParent"] ?: metadata["attachmentIndex"] ?: "?"
                    put("type", "attachment[$index]:$fileName")
                }
                "GIT_HISTORY" -> put("type", "commit")
                "MEETING_TRANSCRIPT" -> put("type", "meeting")
                else -> put("type", type.lowercase())
            }
        }

        // Universal context metadata
        metadata["from"]?.let { put("from", it) }
        metadata["subject"]?.let { put("subj", it.take(50)) }
        metadata["timestamp"]?.let { put("when", it.substringBefore('T')) }

        // Relationship metadata
        metadata["totalSiblings"]?.takeIf { it != "0" }?.let { put("related", it) }
        metadata["indexInParent"]?.let { put("index", it) }

        // Source reference
        metadata["sourceUri"]?.let { put("uri", it) }
        metadata["fileName"]?.let { put("file", it) }

        // Code-specific
        metadata["gitCommitHash"]?.let { put("commit", it.take(8)) }
        metadata["className"]?.let { put("class", it) }
        metadata["methodName"]?.let { put("method", it) }
    }
}
```

### Phase 4: Update Planner Prompt

Update prompts-tools.yaml to explain universal metadata structure:

```yaml
EMAIL CHUNK STRUCTURE:
1. EMAIL BODY: type=email, from=sender, subj=subject, when=date, related=N (attachments)
2. ATTACHMENT: type=attachment[0]:filename, from=sender, subj=subject, when=date

GIT COMMIT STRUCTURE:
  type=commit, from=author, subj=commit_message, when=date, commit=hash

MEETING STRUCTURE:
  type=meeting, from=participants, subj=meeting_title, when=date, file=transcript.txt

All chunks include:
  - parentRef: Groups related chunks (same email, same commit, same meeting)
  - indexInParent: Position within parent (attachment 0/1/2, paragraph 1/2/3)
  - sourceUri: Backend reference for original source
```

### Phase 5: Deprecate Email-Specific Fields (Future)

After migration is complete and stable, deprecate:

- `emailMessageId` → use `parentRef`
- `emailFrom` → use `from`
- `emailSubject` → use `subject`
- `emailReceivedAt` → use `timestamp`
- `attachmentIndex` → use `indexInParent`
- `attachmentFileName` → use `fileName`
- `attachmentMimeType` → use `contentType`
- `totalAttachments` → use `totalSiblings`

## Expected Agent Behavior

### Use Case 1: Find Email About Topic

**Query**: "Kdy jsme řešili sanitární čerpadlo?"

**RAG Returns**:

```
[1] type=email; from=dodavatel@firma.cz; subj=Sanitární čerpadlo - faktura; when=2025-01-15; related=1
Posíláme fakturu za sanitární čerpadlo dle objednávky.

[2] type=attachment[0]:faktura.pdf; from=dodavatel@firma.cz; subj=Sanitární čerpadlo - faktura; when=2025-01-15
Faktura č. 2025/0042
Položka: Sanitární čerpadlo GRUNDFOS UP 20-30
Cena: 12.500 Kč
```

**Agent Understanding**:

- Chunk [1] is email body with 1 attachment (`related=1`)
- Chunk [2] is attachment 0 from same email (same `from`/`subj`/`when`)
- Both chunks already contain full text - NO need to fetch original
- Can synthesize answer: "Dne 15.1.2025 poslal dodavatel@firma.cz fakturu na čerpadlo GRUNDFOS UP 20-30 za 12.500 Kč"

### Use Case 2: Find Code Change

**Query**: "Kdo změnil autorizaci a kdy?"

**RAG Returns**:

```
[1] type=commit; from=jan.novak@firma.cz; subj=Fix authorization bug in UserService; when=2025-01-10; commit=a3b2c1d8
Modified UserService.authenticate() to properly validate JWT tokens before allowing access.
```

**Agent Understanding**:

- Code change by jan.novak@firma.cz on 2025-01-10
- Commit hash a3b2c1d8
- Can answer: "Jan Novák změnil autorizaci 10.1.2025 v commitu a3b2c1d8"
- If more details needed, can use `code_analyze` tool to check current implementation

### Use Case 3: Find Meeting Decision

**Query**: "Co jsme rozhodli ohledně architektury?"

**RAG Returns**:

```
[1] type=meeting; from=Jan,Petr,Marie; subj=Architecture Review Q1 2025; when=2025-01-08; file=meeting-2025-01-08.txt
Team decided to migrate to microservices architecture. Starting with user service extraction.
```

**Agent Understanding**:

- Meeting on 2025-01-08 with Jan, Petr, Marie
- Decision documented in meeting-2025-01-08.txt
- Clear answer without needing original file

## Benefits

1. **Universal Design**: Same metadata pattern works for all source types
2. **Token Efficiency**: Compact field names (`from`, `subj`, `when` vs `emailFrom`, `emailSubject`, `emailReceivedAt`)
3. **Agent Clarity**: Agent sees consistent structure regardless of source type
4. **Relationship Tracking**: `parentRef` + `indexInParent` creates clear hierarchy
5. **Backward Compatible**: Keep old fields during migration
6. **Tool Selection**: Agent knows when to use MCP tools vs RAG search
7. **No Re-fetching**: Full text in chunks means no unnecessary API calls

## Implementation Order

1. ✅ Add universal fields to RagDocument (non-breaking)
2. ✅ Update EmailContentIndexer to populate universal fields
3. ✅ Update EmailAttachmentIndexer to populate universal fields
4. Update GitHistoryIndexingService to populate universal fields
5. Update MeetingTranscriptIndexingService to populate universal fields
6. Update remaining indexing services
7. Update LlmContentSynthesisStrategy metadata extraction
8. Update planner prompt documentation
9. Test agent behavior across all source types
10. Deprecate email-specific fields (future phase)
