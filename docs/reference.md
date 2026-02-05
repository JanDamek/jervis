# Reference - Terminology, Examples & Quick Lookup

**Last updated:** 2026-02-05
**Status:** Production Reference
**Purpose:** Quick lookup for terminology, constants, examples, and copy-paste snippets

---

## Table of Contents

1. [Unified Terminology](#unified-terminology)
2. [Architecture Layers](#architecture-layers)
3. [Node Key Conventions](#node-key-conventions)
4. [Naming Conventions](#naming-conventions)
5. [New Code Checklist](#new-code-checklist)

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

---

## Architecture Layers

### Layer 1: Server (Generic)

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

### Layer 2: Connection (Capability-based)

```kotlin
enum class ConnectionCapability {
    BUGTRACKER,    // Capability: provides issues/tickets
    WIKI,          // Capability: provides wiki pages
    REPOSITORY,    // Capability: provides git repos
    EMAIL,         // Capability: provides emails
    GIT            // Capability: provides git operations
}

// Connection determines WHERE data is, NOT what specific system it is
data class ConnectionDocument(
    val name: String,
    val baseUrl: String,
    val availableCapabilities: Set<ConnectionCapability>
)
```

### Layer 3: Microservices (Vendor-specific)

```kotlin
// service-atlassian
interface IAtlassianClient : IBugTrackerClient, IWikiClient {
    // Implementation specific to Atlassian Cloud API
}

// service-github
interface IGitHubClient : IBugTrackerClient, IRepositoryClient {
    // Implementation specific to GitHub API
}

// service-gitlab
interface IGitLabClient : IBugTrackerClient, IRepositoryClient {
    // Implementation specific to GitLab API
}
```

---

## Node Key Conventions

Knowledge Base uses **generic prefixes** without vendor lock-in:

### ✅ CORRECT

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

### ❌ INCORRECT

```kotlin
"jira::TASK-123"         // Vendor-specific!
"confluence::page-123"   // Vendor-specific!
"github::repo-456"       // Vendor-specific!
```

### Source URN - Preserving Source

While node keys are generic, **SourceUrn preserves the specific source**:

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
- **SourceUrn** = specific for back-queries to source system

---

## Naming Conventions

### Services

| Pattern | Example | Scope |
|---------|---------|-------|
| `{Capability}Service` | `BugTrackerService` | Server - generic |
| `{Vendor}{Capability}Client` | `AtlassianBugTrackerClient` | Microservice - specific |

### Handlers

| Pattern | Example | Scope |
|---------|---------|-------|
| `{Capability}PollingHandler` | `BugTrackerPollingHandler` | Server - generic |
| `{Capability}ContinuousIndexer` | `BugTrackerContinuousIndexer` | Server - generic |

### Entities

| Pattern | Example | Scope |
|---------|---------|-------|
| `{Capability}{Type}IndexDocument` | `BugTrackerIssueIndexDocument` | Server MongoDB |
| `{Capability}{Type}State` | `BugTrackerIssueState` | Indexing state |

### DTOs

| Pattern | Example | Scope |
|---------|---------|-------|
| `{Capability}{Operation}Request` | `BugTrackerSearchRequest` | API contract |
| `{Capability}{Type}Dto` | `BugTrackerIssueDto` | Data transfer |

---

## Reasons for Generalization

### 1. Vendor Independence
```kotlin
// ❌ What if client switches from Jira to GitHub Issues?
class JiraService { ... }  // Have to rename entire service!

// ✅ Generic name works for any vendor
class BugTrackerService { ... }  // Works for Jira, GitHub, GitLab, YouTrack...
```

### 2. Unified Knowledge Base
```kotlin
// ❌ Agent must know all vendor-specific node keys
knowledgeService.search("jira::TASK-123")
knowledgeService.search("github::issue-456")
knowledgeService.search("youtrack::BUG-789")

// ✅ Agent uses generic queries
knowledgeService.search("bugtracker::*")  // Finds issues from all systems
```

### 3. Simplified Agent Prompts
```kotlin
// ❌ Agent prompt must specify all systems
"Analyze this JIRA ticket, GitHub issue, GitLab issue, or YouTrack bug..."

// ✅ Generic terminology
"Analyze this bug tracker issue..."
```

### 4. Extensibility
```kotlin
// ✅ Adding new vendor (Mantis, Asana, ClickUp) doesn't require server changes!
// Just add new microservice with `IBugTrackerClient` implementation
```

---

## New Code Checklist

Before adding new code, ask yourself:

- [ ] Am I using **generic** names (`BugTracker`, `Wiki`) instead of vendor-specific (`Jira`, `Confluence`)?
- [ ] Is my code **vendor-independent** (works for any system of the same type)?
- [ ] Am I using **ConnectionCapability** enum instead of hardcoded vendor names?
- [ ] Are node keys in Knowledge Base **generic** (`bugtracker::`, `wiki::`)?
- [ ] Does SourceUrn **preserve specific source** for back-queries?
- [ ] Do Agent prompts use **generic terminology**?

---

## References

### Documents using correct terminology:
- `docs/knowledge-base.md` - ✅ Uses generic node keys
- `backend/common-services/.../IBugTrackerClient.kt` - ✅ Generic interface
- `backend/common-services/.../IWikiClient.kt` - ✅ Generic interface

---

## Version History

- **2026-02-01**: Initial terminology guide
- **2026-02-04**: Consolidated from individual documentation files
- **2026-02-05**: Translated to English, removed duplicates
