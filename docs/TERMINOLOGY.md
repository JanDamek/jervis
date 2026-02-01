# Jervis - Jednotná terminologie

**Datum:** 2026-02-01
**Status:** Normative
**Účel:** Definice správné generické terminologie pro celou aplikaci

---

## 🎯 Základní princip

**Server NESMÍ znát specifické názvy externích systémů!**

Správná architektura:
- **Server** = Generické typy (`BUGTRACKER_ISSUE`, `WIKI_PAGE`, `REPOSITORY`)
- **Microservices** = Specifické implementace (`service-atlassian`, `service-github`, `service-gitlab`)
- **Connection** = Abstrakce s `ConnectionCapability` enums

---

## 📚 Slovník pojmů

### ✅ SPRÁVNĚ (Generic/Abstract)

| Koncept | Správný název | Popis |
|---------|--------------|-------|
| Issue/Ticket systém | `BUGTRACKER` | Jira, GitHub Issues, GitLab Issues, YouTrack, Mantis |
| Wiki/Dokumentace | `WIKI` nebo `DOCUMENTATION` | Confluence, MediaWiki, Notion, GitBook |
| Repository | `REPOSITORY` nebo `GIT` | GitHub, GitLab, Bitbucket repos |
| Email | `EMAIL` | IMAP, POP3, SMTP |
| Content Type (Agent) | `BUGTRACKER_ISSUE` | Generic issue z libovolného bug trackeru |
| Content Type (Agent) | `WIKI_PAGE` | Generic wiki page z libovolné wiki |
| Extraction Type | `BugTrackerIssueExtraction` | Unified struktura pro všechny issue systémy |
| Extraction Type | `WikiPageExtraction` | Unified struktura pro všechny wiki systémy |

### ❌ ŠPATNĚ (Vendor-specific)

| Chybný název | Proč je špatný | Správná alternativa |
|--------------|----------------|---------------------|
| `JIRA` | Specifické pro Atlassian | `BUGTRACKER_ISSUE` |
| `CONFLUENCE` | Specifické pro Atlassian | `WIKI_PAGE` |
| `JiraService` | Vendor-locked | `BugTrackerService` |
| `ConfluenceService` | Vendor-locked | `WikiService` |
| `JiraPollingHandler` | Specifický název | `BugTrackerPollingHandler` |
| `JiraExtraction` | Vendor-specific | `BugTrackerIssueExtraction` |

---

## 🏗️ Architektonické vrstvy

### Layer 1: Server (Generic)

```kotlin
// ✅ SPRÁVNĚ - Generic types
enum class ContentType {
    EMAIL,
    BUGTRACKER_ISSUE,  // Ne JIRA!
    WIKI_PAGE,         // Ne CONFLUENCE!
    LOG,
    GENERIC
}

sealed class ExtractionResult {
    data class BugTrackerIssue(val data: BugTrackerIssueExtraction)
    data class WikiPage(val data: WikiPageExtraction)
}

data class BugTrackerIssueExtraction(
    val key: String,           // Funguje pro: JIRA-123, GH-456, #789
    val status: String,        // Open, In Progress, Done, Closed
    val parentIssue: String?,  // Epic (Jira), Parent (GitHub/GitLab)
    val milestone: String?,    // Sprint (Jira), Milestone (GitHub/GitLab)
)
```

### Layer 2: Connection (Capability-based)

```kotlin
enum class ConnectionCapability {
    BUGTRACKER,    // Capability: poskytuje issues/tickets
    WIKI,          // Capability: poskytuje wiki pages
    REPOSITORY,    // Capability: poskytuje git repos
    EMAIL,         // Capability: poskytuje emails
    GIT            // Capability: poskytuje git operations
}

// Connection určuje KDE data jsou, NE jaký je specifický systém
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
    // Implementace specifická pro Atlassian Cloud API
}

// service-github
interface IGitHubClient : IBugTrackerClient, IRepositoryClient {
    // Implementace specifická pro GitHub API
}

// service-gitlab
interface IGitLabClient : IBugTrackerClient, IRepositoryClient {
    // Implementace specifická pro GitLab API
}
```

---

## 🔄 Knowledge Base - Generické node keys

Knowledge Base používá **generické prefixy** bez vendor lock-in:

### ✅ SPRÁVNĚ

```kotlin
// Bug tracker issues
"bugtracker::<issueKey>"        // bugtracker::JIRA-123, bugtracker::GH-456
"issue::<issueKey>"              // Alternativa

// Wiki pages
"wiki::<pageId>"                 // wiki::confluence-12345, wiki::mediawiki-page-1
"doc::<pageId>"                  // Alternativa pro documentation

// Repositories
"repo::<repoId>"                 // repo::github-my-project, repo::gitlab-my-app
"git::<repoId>"                  // Alternativa

// Commits
"commit::<hash>"                 // commit::abc123def456

// Users
"user::<userId>"                 // user::john.doe@example.com

// Files
"file::<path>"                   // file::src/main/kotlin/Main.kt
```

### ❌ ŠPATNĚ

```kotlin
"jira::TASK-123"         // Vendor-specific!
"confluence::page-123"   // Vendor-specific!
"github::repo-456"       // Vendor-specific!
```

### 🔗 Source URN - Zachování zdroje

I když node keys jsou generické, **SourceUrn zachovává specifický zdroj**:

```kotlin
data class SourceUrn(
    val scheme: String,      // "jira", "github", "confluence"
    val authority: String,   // connectionId nebo domain
    val path: String,        // issue key, page ID, etc.
    val version: String?     // timestamp, version number
)

// Příklady:
SourceUrn.parse("jira://conn-123/TASK-456?v=2026-01-01T10:00:00Z")
SourceUrn.parse("github://conn-789/issues/123?v=abc123")
SourceUrn.parse("confluence://conn-456/pages/789?v=1234567890")
```

**Proč?**
- **Node key** = generický pro RAG/Graph dotazy (`bugtracker::TASK-456`)
- **SourceUrn** = specifický pro zpětné dotazy do source systému

---

## 📝 Konvence pojmenování

### Services

| Pattern | Příklad | Scope |
|---------|---------|-------|
| `{Capability}Service` | `BugTrackerService` | Server - generic |
| `{Vendor}{Capability}Client` | `AtlassianBugTrackerClient` | Microservice - specific |

### Handlers

| Pattern | Příklad | Scope |
|---------|---------|-------|
| `{Capability}PollingHandler` | `BugTrackerPollingHandler` | Server - generic |
| `{Capability}ContinuousIndexer` | `BugTrackerContinuousIndexer` | Server - generic |

### Entities

| Pattern | Příklad | Scope |
|---------|---------|-------|
| `{Capability}{Type}IndexDocument` | `BugTrackerIssueIndexDocument` | Server MongoDB |
| `{Capability}{Type}State` | `BugTrackerIssueState` | Indexing state |

### DTOs

| Pattern | Příklad | Scope |
|---------|---------|-------|
| `{Capability}{Operation}Request` | `BugTrackerSearchRequest` | API contract |
| `{Capability}{Type}Dto` | `BugTrackerIssueDto` | Data transfer |

---

## 🚫 Důvody pro generalizaci

### 1. **Vendor Independence**
```kotlin
// ❌ Co když klient přejde z Jira na GitHub Issues?
class JiraService { ... }  // Musíme přejmenovat celou službu!

// ✅ Generic název funguje pro libovolný vendor
class BugTrackerService { ... }  // Funguje pro Jira, GitHub, GitLab, YouTrack...
```

### 2. **Unified Knowledge Base**
```kotlin
// ❌ Agent musí znát všechny vendor-specific node keys
knowledgeService.search("jira::TASK-123")
knowledgeService.search("github::issue-456")
knowledgeService.search("youtrack::BUG-789")

// ✅ Agent používá generic dotazy
knowledgeService.search("bugtracker::*")  // Najde issues ze všech systémů
```

### 3. **Simplified Agent Prompts**
```kotlin
// ❌ Agent prompt musí specifikovat všechny systémy
"Analyze this JIRA ticket, GitHub issue, GitLab issue, or YouTrack bug..."

// ✅ Generic terminology
"Analyze this bug tracker issue..."
```

### 4. **Extensibility**
```kotlin
// ✅ Přidání nového vendora (Mantis, Asana, ClickUp) nevyžaduje změny v serveru!
// Jen přidáme nový microservice s `IBugTrackerClient` implementací
```

---

## 📖 Reference

### Dokumenty používající správnou terminologii:
- `docs/knowledgebase-implementation.md` - ✅ Používá generic node keys
- `backend/common-services/.../IBugTrackerClient.kt` - ✅ Generic interface
- `backend/common-services/.../IWikiClient.kt` - ✅ Generic interface

### Dokumenty vyžadující update:
- `docs/guidelines.md` - ⚠️ Obsahuje Jira/Confluence specifické příklady
- `docs/polling-indexing-architecture.md` - ⚠️ Jira/Confluence specific sections

---

## ✅ Checklist pro nový kód

Před přidáním nového kódu se ptej:

- [ ] Používám **generic** názvy (`BugTracker`, `Wiki`) místo vendor-specific (`Jira`, `Confluence`)?
- [ ] Je můj kód **vendor-independent** (funguje pro libovolný systém stejného typu)?
- [ ] Používám **ConnectionCapability** enum místo hardcoded vendor names?
- [ ] Node keys v Knowledge Base jsou **generické** (`bugtracker::`, `wiki::`)?
- [ ] SourceUrn **zachovává specifický zdroj** pro zpětné dotazy?
- [ ] Agent prompts používají **generic terminology**?

---

**Závěr:** Generic terminology = Vendor independence + Extensibility + Simplified agent logic
