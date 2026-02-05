# New Connection System Architecture

**Last updated:** 2026-02-05
**Status:** Architecture Documentation

## Overview

New architecture separates:
1. **Connection configuration** (ConnectionDocument) - only necessary data for establishing connection
2. **Provider logic** (microservices) - specific implementations for GitHub, GitLab, Atlassian, etc.
3. **Capability interfaces** (universal API) - IGitService, IBugTrackerService, IWikiService, IEmailService

## Layer Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     ConnectionSettings UI                    │
│              (Provider selection + configuration)            │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   ConnectionDocument                         │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ id: ConnectionId                                      │  │
│  │ name: String                                          │  │
│  │ provider: ProviderEnum  ◄── Determines handler       │  │
│  │ state: ConnectionStateEnum                            │  │
│  │ config: Map<String, String>  ◄── Provider-specific   │  │
│  │ availableCapabilities: Set<ConnectionCapability>     │  │
│  │ rateLimitConfig: RateLimitConfig                     │  │
│  └──────────────────────────────────────────────────────┘  │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                      CentralPoller                           │
│                                                               │
│  FOR EACH connection:                                        │
│    1. Load connection + determine clients/projects          │
│    2. Get provider from connection.provider                  │
│    3. Get capabilities from connection.availableCapabilities│
│    4. FOR EACH capability:                                   │
│         - Find handler by provider + capability              │
│         - Call handler.poll(connection, context)             │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                     PollingHandler                           │
│                  (by Provider Type)                          │
│                                                               │
│  ┌─────────────────┐  ┌─────────────────┐                  │
│  │ GitHubHandler   │  │ GitLabHandler   │  ...              │
│  │ provider: GITHUB│  │ provider: GITLAB│                   │
│  └────────┬────────┘  └────────┬────────┘                  │
│           │                    │                             │
│           └────────┬───────────┘                             │
│                    ▼                                         │
│        ┌────────────────────────┐                           │
│        │  Capability Services   │                           │
│        │  (Universal Interface) │                           │
│        ├────────────────────────┤                           │
│        │ IGitService            │                           │
│        │ IBugTrackerService     │                           │
│        │ IWikiService           │                           │
│        │ IEmailService          │                           │
│        └────────┬───────────────┘                           │
└─────────────────┼─────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│                    Microservices                             │
│  (Specific implementation for each provider)                │
│                                                               │
│  ┌─────────────────┐  ┌─────────────────┐                  │
│  │ service-github  │  │ service-gitlab  │                   │
│  │  - GitService   │  │  - GitService   │                   │
│  │  - BugTracker   │  │  - BugTracker   │                   │
│  │  - WikiService  │  │  - WikiService  │                   │
│  └─────────────────┘  └─────────────────┘                  │
│                                                               │
│  ┌─────────────────┐  ┌─────────────────┐                  │
│  │service-atlassian│  │ service-imap    │                   │
│  │  - BugTracker   │  │  - EmailService │                   │
│  │  - WikiService  │  └─────────────────┘                  │
│  │  - Repository   │                                        │
│  └─────────────────┘                                        │
└─────────────────────────────────────────────────────────────┘
```

## Provider × Capability Mapping

| Provider   | Capabilities                                  |
|------------|-----------------------------------------------|
| GITHUB     | REPOSITORY, BUGTRACKER, WIKI, GIT            |
| GITLAB     | REPOSITORY, BUGTRACKER, WIKI, GIT            |
| ATLASSIAN  | BUGTRACKER (Jira), WIKI (Confluence), REPO   |
| IMAP       | EMAIL                                         |
| POP3       | EMAIL                                         |
| SMTP       | EMAIL                                         |
| OAUTH2     | (determined after auth flow)                  |

## ConnectionDocument - New Structure

```kotlin
data class ConnectionDocument(
    val id: ConnectionId,
    val name: String,
    val provider: ProviderEnum,  // ← Determines which handler to use
    var state: ConnectionStateEnum,
    val config: Map<String, String>,  // ← Flexible configuration
    val availableCapabilities: Set<ConnectionCapability>,
    val rateLimitConfig: RateLimitConfig,
)
```

### Config Keys by Provider:

**HTTP-based (GitHub, GitLab, Atlassian):**
- `baseUrl`: String
- `authType`: "NONE" | "BASIC" | "BEARER" | "OAUTH2"
- `username`: String (for BASIC)
- `password`: String (for BASIC)
- `token`: String (for BEARER)
- `timeoutMs`: Int

**Email (IMAP, POP3, SMTP):**
- `host`: String
- `port`: Int
- `useSsl`: Boolean
- `useTls`: Boolean
- `username`: String
- `password`: String
- `folder`: String (for IMAP/POP3)

**Provider-specific (optional):**
- `jiraProjectKey`: String
- `confluenceSpaceKey`: String
- `gitRemoteUrl`: String
- etc.

## PollingStateDocument - Update

```kotlin
data class PollingStateDocument(
    val id: PollingStateId,
    val connectionId: ConnectionId,
    val provider: ProviderEnum,  // ← Changed from handlerType: String
    val lastFetchedUid: Long?,
    val lastFetchedMessageNumber: Int?,
    val lastSeenUpdatedAt: Instant?,
    val lastUpdated: Instant,
)
```

## Polling Flow

1. **CentralPoller** loads all connections
2. For each connection:
   ```kotlin
   val provider = connection.provider
   val capabilities = connection.availableCapabilities

   for (capability in capabilities) {
       val handler = handlersByProvider[provider]
       if (handler?.supportsCapability(capability) == true) {
           handler.poll(connection, context)
       }
   }
   ```

3. **PollingHandler** (e.g., GitHubPollingHandler):
   ```kotlin
   class GitHubPollingHandler : PollingHandler {
       override val provider = ProviderEnum.GITHUB

       override fun supportsCapability(cap: ConnectionCapability): Boolean {
           return cap in setOf(
               ConnectionCapability.REPOSITORY,
               ConnectionCapability.BUGTRACKER,
               ConnectionCapability.WIKI,
               ConnectionCapability.GIT
           )
       }

       override suspend fun poll(
           connection: ConnectionDocument,
           context: PollingContext
       ): PollingResult {
           // 1. Extract config
           val baseUrl = connection.config["baseUrl"]
           val token = connection.config["token"]

           // 2. Call service-github microservice
           val gitService = gitHubClient.getGitService(baseUrl, token)
           val bugTrackerService = gitHubClient.getBugTrackerService(baseUrl, token)

           // 3. Poll using universal interfaces
           pollRepositories(gitService, context)
           pollIssues(bugTrackerService, context)

           return PollingResult(...)
       }
   }
   ```

## Universal Service Interfaces

```kotlin
interface IGitService {
    suspend fun listRepositories(): List<RepositoryDto>
    suspend fun getCommits(repo: String): List<CommitDto>
    suspend fun getFile(repo: String, path: String): FileContentDto
}

interface IBugTrackerService {
    suspend fun searchIssues(query: String): List<IssueDto>
    suspend fun getIssue(id: String): IssueDetailDto
    suspend fun getComments(issueId: String): List<CommentDto>
}

interface IWikiService {
    suspend fun listPages(space: String): List<WikiPageDto>
    suspend fun getPage(id: String): WikiPageDetailDto
}

interface IEmailService {
    suspend fun fetchMessages(folder: String, since: Instant?): List<EmailMessageDto>
    suspend fun sendMessage(message: EmailDto)
}
```

## Migration from Old Structure

### Phase 1: Parallel Run (current state)
- ✅ ConnectionDocument has provider enum
- ✅ PollingStateDocument uses provider instead of handlerType
- ✅ PollingHandler interface has provider property
- ⏳ Old ConnectionDocument structure still exists

### Phase 2: Gradual Migration (TODO)
1. Create ConnectionDocumentNew with config map
2. Create migrations to convert old connections
3. Update RPC layer to work with config map
4. Move provider-specific logic to microservices

### Phase 3: Remove Old Structure
1. Delete old ConnectionDocument
2. Rename ConnectionDocumentNew → ConnectionDocument
3. Clean up unused DTOs

## Benefits of New Architecture

1. **Separation of Concerns**: Server only routes, microservices implement
2. **Extensibility**: New provider = new microservice + handler
3. **Type Safety**: Provider enum ensures correct routing
4. **Simplicity**: ConnectionDocument contains only necessary data
5. **Testability**: Each layer testable separately
6. **Universal API**: Common interface for all providers of the same capability

## Example: Adding New Provider (e.g., Azure DevOps)

1. Add `AZURE_DEVOPS` to `ProviderEnum`
2. Create `service-azure-devops` microservice:
   ```kotlin
   class AzureDevOpsGitService : IGitService { ... }
   class AzureDevOpsBugTrackerService : IBugTrackerService { ... }
   ```
3. Create `AzureDevOpsPollingHandler`:
   ```kotlin
   class AzureDevOpsPollingHandler : PollingHandler {
       override val provider = ProviderEnum.AZURE_DEVOPS
       override fun supportsCapability(cap) = ...
       override suspend fun poll(...) = ...
   }
   ```
4. Add to `ConnectionSettings.kt` UI for configuration

**No changes to ConnectionDocument or database are needed!**
