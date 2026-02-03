# Reference - Terminology, Examples & Quick Lookup

**Last updated:** 2026-02-02  
**Status:** Production Reference  
**Purpose:** Quick lookup for terminology, constants, examples, and copy-paste snippets

---

## Table of Contents

1. [Unified Terminology](#unified-terminology)
2. [Security Headers Reference](#security-headers-reference)
3. [Node Key Conventions](#node-key-conventions)
4. [Quick Examples](#quick-examples)
5. [Dependencies & Libraries](#dependencies--libraries)

---

## Unified Terminology

### Core Principle

**Server must NOT know specific external system names!**

Correct architecture:
- **Server** = Generic types (`BUGTRACKER_ISSUE`, `WIKI_PAGE`, `REPOSITORY`)
- **Microservices** = Vendor-specific implementations (`service-atlassian`, `service-github`, `service-gitlab`)
- **Connection** = Abstraction with `ConnectionCapability` enums

### Generic vs Vendor-Specific

#### ✅ CORRECT (Generic/Abstract)

| Concept | Correct Name | Description |
|---------|--------------|-------------|
| Issue/Ticket system | `BUGTRACKER` | Jira, GitHub Issues, GitLab Issues, YouTrack, Mantis |
| Wiki/Documentation | `WIKI` or `DOCUMENTATION` | Confluence, MediaWiki, Notion, GitBook |
| Repository | `REPOSITORY` or `GIT` | GitHub, GitLab, Bitbucket repos |
| Email | `EMAIL` | IMAP, POP3, SMTP |
| Content Type (Agent) | `BUGTRACKER_ISSUE` | Generic issue from any bug tracker |
| Content Type (Agent) | `WIKI_PAGE` | Generic wiki page from any wiki |
| Extraction Type | `BugTrackerIssueExtraction` | Unified structure for all issue systems |
| Extraction Type | `WikiPageExtraction` | Unified structure for all wiki systems |

#### ❌ INCORRECT (Vendor-Specific)

| Wrong Name | Why It's Wrong | Correct Alternative |
|-----------|-----------------|---------------------|
| `JIRA` | Atlassian-specific | `BUGTRACKER_ISSUE` |
| `CONFLUENCE` | Atlassian-specific | `WIKI_PAGE` |
| `JiraService` | Vendor-locked | `BugTrackerService` |
| `ConfluenceService` | Vendor-locked | `WikiService` |
| `JiraPollingHandler` | Specific name | `BugTrackerPollingHandler` |
| `JiraExtraction` | Vendor-specific | `BugTrackerIssueExtraction` |

### Architectural Layers

#### Layer 1: Server (Generic)

```kotlin
// ✅ CORRECT - Generic types
enum class ContentType {
    EMAIL,
    BUGTRACKER_ISSUE,  // Not JIRA!
    WIKI_PAGE,         // Not CONFLUENCE!
    LOG,
    GENERIC
}

sealed class ExtractionResult {
    data class BugTrackerIssue(val data: BugTrackerIssueExtraction)
    data class WikiPage(val data: WikiPageExtraction)
}

data class BugTrackerIssueExtraction(
    val key: String,           // Works for: JIRA-123, GH-456, #789
    val status: String,        // Open, In Progress, Done, Closed
    val parentIssue: String?,  // Epic (Jira), Parent (GitHub/GitLab)
    val milestone: String?,    // Sprint (Jira), Milestone (GitHub/GitLab)
)
```

#### Layer 2: Connection (Capability-Based)

```kotlin
enum class ConnectionCapability {
    BUGTRACKER,    // Capability: provides issues/tickets
    WIKI,          // Capability: provides wiki pages
    REPOSITORY,    // Capability: provides git repos
    EMAIL,         // Capability: provides emails
    GIT            // Capability: provides git operations
}

data class ConnectionDocument(
    val name: String,
    val baseUrl: String,
    val availableCapabilities: Set<ConnectionCapability>
)
```

#### Layer 3: Microservices (Vendor-Specific)

```kotlin
// service-atlassian
interface IAtlassianClient : IBugTrackerClient, IWikiClient {
    // Atlassian-specific implementation
}

// service-github
interface IGitHubClient : IBugTrackerClient, IRepositoryClient {
    // GitHub-specific implementation
}

// service-gitlab
interface IGitLabClient : IBugTrackerClient, IRepositoryClient {
    // GitLab-specific implementation
}
```

---

## Security Headers Reference

### Quick Lookup Table

| Header | Value | Required | Validation |
|--------|-------|----------|-----------|
| `X-Jervis-Client` | `a7f3c9e2-4b8d-11ef-9a1c-0242ac120002` | YES | Must match config |
| `X-Jervis-Platform` | `iOS`, `Android`, or `Desktop` | YES | Must be in allowed set |
| `X-Jervis-Client-IP` | Local IP (e.g., `192.168.1.100`) | NO | Debug only |

### Configuration

**File:** `application.yml`

```yaml
jervis:
  security:
    client-token: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002
```

**Shared Constants:** `SecurityConstants.kt`

```kotlin
const val CLIENT_TOKEN = "a7f3c9e2-4b8d-11ef-9a1c-0242ac120002"
const val CLIENT_HEADER = "X-Jervis-Client"
const val PLATFORM_HEADER = "X-Jervis-Platform"
const val PLATFORM_IOS = "iOS"
const val PLATFORM_ANDROID = "Android"
const val PLATFORM_DESKTOP = "Desktop"
```

### Test Cases (Copy-Paste Ready)

#### Valid Request

```bash
wscat -c ws://localhost:5500/rpc \
  -H "X-Jervis-Client: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002" \
  -H "X-Jervis-Platform: Desktop"
```

#### Missing Token

```bash
wscat -c ws://localhost:5500/rpc \
  -H "X-Jervis-Platform: Desktop"
```

#### Invalid Token

```bash
wscat -c ws://localhost:5500/rpc \
  -H "X-Jervis-Client: wrong" \
  -H "X-Jervis-Platform: Desktop"
```

#### Invalid Platform

```bash
wscat -c ws://localhost:5500/rpc \
  -H "X-Jervis-Client: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002" \
  -H "X-Jervis-Platform: BadValue"
```

---

## Node Key Conventions

### Knowledge Base - Generic Node Keys

Knowledge Base uses **generic prefixes** without vendor lock-in:

#### ✅ CORRECT

```kotlin
// Bug tracker issues
"bugtracker::<issueKey>"        // bugtracker::JIRA-123, bugtracker::GH-456
"issue::<issueKey>"              // Alternative

// Wiki pages
"wiki::<pageId>"                 // wiki::confluence-12345, wiki::mediawiki-page-1
"doc::<pageId>"                  // Alternative for documentation

// Repositories
"repo::<repoId>"                 // repo::github-my-project, repo::gitlab-my-app
"git::<repoId>"                  // Alternative

// Commits
"commit::<hash>"                 // commit::abc123def456

// Users
"user::<userId>"                 // user::john.doe@example.com

// Files
"file::<path>"                   // file::src/main/kotlin/Main.kt
```

#### ❌ INCORRECT

```kotlin
"jira::TASK-123"         // Vendor-specific!
"confluence::page-123"   // Vendor-specific!
"github::repo-456"       // Vendor-specific!
```

### Source URN - Preserving Origin

Although node keys are generic, **SourceUrn preserves specific origin**:

```kotlin
data class SourceUrn(
    val scheme: String,      // "jira", "github", "confluence"
    val authority: String,   // connectionId or domain
    val path: String,        // issue key, page ID, etc.
    val version: String?     // timestamp, version number
)

// Examples:
SourceUrn.parse("jira://conn-123/TASK-456?v=2026-01-01T10:00:00Z")
SourceUrn.parse("github://conn-789/issues/123?v=abc123")
SourceUrn.parse("confluence://conn-456/pages/789?v=1234567890")
```

**Why?**
- **Node key** = generic for RAG/Graph queries (`bugtracker::TASK-456`)
- **SourceUrn** = specific for querying back to source system

---

## Quick Examples

### HTTP Client Usage

#### KtorClientFactory (No Rate Limiting)

```kotlin
@Service
class OllamaClient(private val ktorClientFactory: KtorClientFactory) {
    private val httpClient by lazy { 
        ktorClientFactory.getHttpClient("ollama.primary") 
    }
    
    suspend fun callModel(request: OllamaRequest): OllamaResponse {
        return httpClient.post("/api/generate") { 
            setBody(request) 
        }.body()
    }
}
```

#### Ktor with DomainRateLimiter

```kotlin
@Service
class LinkScraperService {
    private val rateLimiter = DomainRateLimiter(
        RateLimitConfig(maxRequestsPerSecond = 10)
    )
    
    suspend fun scrapeUrl(url: String): String {
        if (!UrlUtils.isInternalUrl(url)) {
            rateLimiter.acquire(UrlUtils.extractDomain(url))
        }
        return httpClient.get(url).body()
    }
}
```

#### NDJSON Streaming

```kotlin
val response: HttpResponse = httpClient.post("/api/pull") { 
    setBody(body) 
}
val channel: ByteReadChannel = response.bodyAsChannel()

while (!channel.isClosedForRead) {
    val line = channel.readUTF8Line() ?: break
    if (line.isNotBlank()) {
        val status = Json.parseToJsonElement(line)
            .jsonObject["status"]?.jsonPrimitive?.content
        // Process line...
    }
}
```

### Prompt Templating

```kotlin
// 1. Define template
val template = """
{
  "id": "{id}",
  "type": "{type}",
  "clientId": "{clientId}",
  "content": {contentRaw}
}
"""

// 2. Prepare values
val values = mapOf(
    "id" to taskId.toString(),
    "type" to "ANALYSIS",
    "clientId" to client.id.toString(),
    "contentRaw" to Json.encodeToString(content)
)

// 3. Render
val json = PromptBuilderService.render(template, values)
```

### Tool Registration

```kotlin
@Tool
@LLMDescription(
    "Search knowledge base for related documents. " +
    "Returns list of relevant entries."
)
suspend fun searchKnowledge(query: String): String {
    return knowledgeService.search(query)
        .map { it.content }
        .joinToString("\n---\n")
}
```

---

## Dependencies & Libraries

### Core Framework Versions

| Library | Version | Use |
|---------|---------|-----|
| Kotlin | 2.0.0+ | Language |
| Koog Framework | 0.6.0 | AI Agent DSL |
| Ktor | 3.0+ | HTTP Server & Client |
| kotlinx-rpc | 0.10.1+ | Type-safe RPC |
| kotlinx.serialization | 1.7.0 | Serialization |
| Spring Boot WebFlux | 3.x | Server framework |
| MongoDB Driver | 5.0+ | Document DB |
| ArangoDB | 3.10+ | Knowledge Graph |
| Weaviate Client | 4.x | Vector Store |

### Key Classes

| Class | Module | Purpose |
|-------|--------|---------|
| `IBugTrackerClient` | common-services | Generic issue interface |
| `IWikiClient` | common-services | Generic wiki interface |
| `IRepositoryClient` | common-services | Generic repo interface |
| `ConnectionCapability` | domain | Capability enum |
| `KtorClientFactory` | server | HTTP client factory |
| `DomainRateLimiter` | common-services | Rate limiting |
| `KoogQualifierAgent` | server | Qualifier agent |
| `KnowledgeService` | server | RAG service |
| `GraphDBService` | server | Knowledge graph service |

---

## Log Patterns

### Valid Connection

```
DEBUG Verified client connection. RemoteHost: 192.168.1.100, Platform: iOS
```

### Missing Headers

```
WARN Unverified client detected - missing required security headers.
     RemoteHost: 192.168.1.100, URI: /rpc, ClientToken: MISSING, Platform: present
```

### Invalid Token

```
WARN Unverified client detected - invalid client token.
     RemoteHost: 192.168.1.100, URI: /rpc, Platform: Android
```

### Invalid Platform

```
WARN Unverified client detected - invalid platform value.
     RemoteHost: 192.168.1.100, URI: /rpc, Platform: UnknownOS
```

---

## Checklist for New Code

Before adding new code, ask yourself:

- [ ] Am I using **generic** names (`BugTracker`, `Wiki`) instead of vendor-specific (`Jira`, `Confluence`)?
- [ ] Is my code **vendor-independent** (works for any system of same type)?
- [ ] Am I using **ConnectionCapability** enum instead of hardcoded vendor names?
- [ ] Are Knowledge Base node keys **generic** (`bugtracker::`, `wiki::`)?
- [ ] Does **SourceUrn preserve specific origin** for queries?
- [ ] Do agent prompts use **generic terminology**?

---

## Summary

**Key References:**
1. Terminology → Generic not vendor-specific
2. Security headers → Client token + Platform
3. Node keys → Generic prefixes + SourceUrn for origin
4. HTTP clients → Choose by use case
5. Prompts → Templated strings with variables

