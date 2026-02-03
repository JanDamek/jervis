# Developer Guidelines

**Last updated:** 2026-02-02  
**Status:** Production Standards  
**Purpose:** Engineering best practices, coding standards, and UI design system

---

## Table of Contents

1. [Core Development Principles](#core-development-principles)
2. [Kotlin & Code Style](#kotlin--code-style)
3. [Error Handling & Logging](#error-handling--logging)
4. [HTTP Clients & Service Integration](#http-clients--service-integration)
5. [LLM Integration & Prompting](#llm-integration--prompting)
6. [UI Design System](#ui-design-system)

---

## Core Development Principles

### FAIL-FAST Philosophy

Errors must not be hidden. An exception is better than masking an error.

**❌ DON'T:**
- Try/catch inside business logic (services, tools, repositories)
- Catch exceptions just for logging and re-throw
- Use generic `Result<T>` wrappers everywhere

**✅ DO:**
- Try/catch ONLY at boundaries: I/O, REST boundary, top-level controller
- Let exceptions propagate to top-level handler
- For Tools (Koog): Tools throw exceptions, framework handles as tool error
- Validate input (fail-fast), not defensive programming everywhere

### Kotlin-First & Idiomatic Code

- Use coroutines + Flow as foundation for async work
- Avoid "Java in Kotlin" patterns
- Prefer streaming (`Flow`, `Sequence`) over building large `List`
- Use extension functions instead of "utils" classes
- No @Deprecated code: all changes refactored immediately

### IF-LESS Pattern

Where code might expand, replace `if/when` with:
- Polymorphism
- Sealed hierarchies
- Strategy maps
- Routing tables

**OK for trivial cases:** `if/when` is fine for simple decisions

### SOLID Principles

- Small, single-purpose functions
- High cohesion, low coupling
- Eliminate duplicates
- Dependency injection

### Code Comments & Language

**Language:** English only in code, comments, and logs

**Comments:**
- ❌ NO decorative comments (ASCII art, section headers, formatting)
  - ❌ `// ════════════`
  - ❌ `// ───────────`
  - ❌ `// ==================== SECTION ====================`
- ✅ Self-documenting code speaks for itself
- ✅ ONLY KDoc for public API
- ✅ Only critical "why" notes inline
- ✅ Use blank lines or package structure for separation

### NO Metadata Maps (Antipattern)

**❌ NEVER use:** `metadata: MutableMap<String, Any>` or generic maps for structured data

**✅ ALWAYS create:** Proper data classes with typed fields

**Why?** No type safety, no IDE support, no refactoring support, poor readability

---

## Kotlin & Code Style

### No Serialization of Mixed-Type Collections

**Problem:** Ollama API requests had heterogeneous maps (e.g., `mapOf("name" to "model", "stream" to false)`)

**Error:** `IllegalStateException: Serializing collections of different element types is not yet supported`

**Solution:** Define `@Serializable` data classes for each request type

```kotlin
@Serializable
data class OllamaPullRequest(
    val name: String,
    val stream: Boolean = false
)

@Serializable
data class OllamaPullResponse(
    val status: String,
    val digest: String? = null,
    val total: Long? = null,
    val completed: Long? = null
)
```

**Benefit:**
- Type-safe API calls
- IDE autocomplete and compile-time checks
- Clear request/response structure
- Follows guidelines preference for `kotlinx.serialization`

---

## Error Handling & Logging

### Exception Hierarchy

- **Business Logic:** Throw exceptions, don't catch
- **I/O Layer:** Catch, log, transform to domain exceptions
- **REST Boundary:** Catch, convert to HTTP response
- **Top Level:** Global exception handler

### Logging Levels

- **ERROR:** Unrecoverable failure requiring intervention
- **WARN:** Degraded operation, security concerns, repeated errors
- **INFO:** Significant state transitions, deployment events
- **DEBUG:** Detailed flow, tool execution, validation details

---

## HTTP Clients & Service Integration

### Decision Tree

| Scenario | Client | Why |
|----------|--------|-----|
| Internal service (192.168.x.x, localhost) | `KtorClientFactory` | Trusted network |
| External LLM API with built-in rate limiting | `KtorClientFactory` | No client-side limit needed |
| Microservice with `@HttpExchange` | `WebClientFactory` | Spring integration |
| Microservice with dynamic domain | Ktor + `DomainRateLimiter` | User-specified URLs |
| Link scraper | Ktor + `DomainRateLimiter` | Dynamic domain scraping |

### KtorClientFactory - No Rate Limiting

**Use for:**
- Ollama (localhost)
- LM Studio (localhost)
- Searxng (internal)
- OpenAI, Anthropic, Google (have built-in rate limiting)

**Setup:**
```kotlin
@Service
class OllamaClient(private val ktorClientFactory: KtorClientFactory) {
    private val httpClient by lazy { 
        ktorClientFactory.getHttpClient("ollama.primary") 
    }
    
    suspend fun call(...) = httpClient.post("/api/generate") { 
        setBody(...) 
    }.body<Response>()
}
```

**Critical:** LLM calls must have NO timeout (can take 15+ minutes)

```kotlin
install(HttpTimeout) {
    requestTimeoutMillis = null // No timeout for LLM
    socketTimeoutMillis = null  // No socket timeout for streaming
}
```

### Ktor with DomainRateLimiter

**Use for:** Microservices with dynamic domains (user-specified URLs)

```kotlin
private val rateLimiter = DomainRateLimiter(
    RateLimitConfig(
        maxRequestsPerSecond = 10,
        maxRequestsPerMinute = 100
    )
)

private suspend fun <T> rateLimitedRequest(
    url: String,
    block: suspend (HttpClient, String) -> T
): T {
    if (!UrlUtils.isInternalUrl(url)) {
        val domain = UrlUtils.extractDomain(url)
        rateLimiter.acquire(domain)  // Blocks until quota available
    }
    return block(httpClient, url)
}
```

### WebClientFactory - Only for @HttpExchange

**Use for:** Compute services with declarative HTTP contracts

```kotlin
@HttpExchange(url = "/parse", method = "POST")
interface ITikaClient {
    suspend fun process(request: TikaProcessRequest): TikaProcessResponse
}

@HttpExchange(url = "/jira/search", method = "POST")
interface IAtlassianClient {
    suspend fun searchJiraIssues(request: JiraSearchRequest): JiraSearchResponse
}
```

### NDJSON Streaming (Newline-Delimited JSON)

**Problem:** Ollama `/api/pull` returns streaming NDJSON

**❌ WRONG:** `.body<Type>()` → `NoTransformationFoundException`

**✅ CORRECT:** Read line-by-line with `bodyAsChannel()`

```kotlin
val response: HttpResponse = httpClient.post("/api/pull") { 
    setBody(body) 
}
val channel: ByteReadChannel = response.bodyAsChannel()
val mapper = jacksonObjectMapper()

while (!channel.isClosedForRead) {
    val line = channel.readUTF8Line() ?: break
    if (line.isNotBlank()) {
        val jsonNode = mapper.readTree(line)
        val status = jsonNode.get("status")?.asText()
        // Process each JSON line...
    }
}
```

---

## LLM Integration & Prompting

### String Boundaries & Templating (SSOT)

LLM boundary is ALWAYS String → String:
- `nodeLLMRequest` and `nodeLLMSendToolResult` work with text only
- Type-safe I/O held at edges via `PromptBuilderService`

### JSON Payload Templating

**❌ NEVER:** Ad-hoc `StringBuilder`/`buildString` for JSON to LLM

**✅ ALWAYS use:** `PromptBuilderService`

```kotlin
// 1. Prepare static template
val template = """
{
  "id": "{id}",
  "type": "{type}",
  "clientId": "{clientId}",
  "projectId": {projectIdRaw},
  "content": {contentRaw}
}
"""

// 2. Prepare values
val values = mapOf(
    "id" to taskId.toString(),
    "type" to contentType,
    "clientId" to clientId,
    "projectIdRaw" to projectId?.toString() ?: "null",
    "contentRaw" to Json.encodeToString(content)
)

// 3. Render with fail-fast
val json = PromptBuilderService.render(template, values)
```

### LLM Model Routing

**Providers:** Defined in `models-config.yaml`

| Provider | Use Case | Model |
|----------|----------|-------|
| OLLAMA_QUALIFIER | CPU qualifier agent | `qwen3-coder-tool:30b` |
| OLLAMA_EMBEDDING | Vector embeddings | `nomic-embed-text` |
| OLLAMA_PRIMARY | Main reasoning | Configured model |
| OPENAI | Premium tasks | `gpt-4` |
| ANTHROPIC | Alternate | `claude-3` |

**Fail-fast:** No fallbacks outside Koog configuration

### Prompt Structure

```kotlin
system("""
    You are [ROLE] - [PURPOSE].

    MANDATORY WORKFLOW:
    1. [STEP 1]: Description
    2. [STEP 2]: Description
    3. [STEP 3]: Description

    CRITICAL STOP RULES - NEVER VIOLATE:
    ❌ NEVER [bad behavior]
    ❌ NEVER [bad behavior]
    ✅ ALWAYS [good behavior]
    ✅ ALWAYS [good behavior]

    [Additional context-specific rules]
""".trimIndent())
```

**Key Points:**
- Keep system prompts under 50 lines
- Use clear section headers (WORKFLOW, STOP RULES)
- Avoid concrete tool names
- Use visual markers (❌ ✅)
- Be specific about what NOT to do

### Tool Registration Best Practices

```kotlin
@Tool
@LLMDescription(
    "Analyze and interpret user request to understand goal. " +
    "Returns: NormalizedRequest with type, entities and outcome."
)
suspend fun interpretRequest(): String {
    // Implementation
}
```

**Critical Rules:**
- ❌ NEVER mention tool names in prompts (e.g., "call interpretRequest()")
- ❌ NEVER say "Always call X after Y"
- ✅ Use generic descriptions: "Analyze the request type"
- ✅ Let LLM discover tools via @LLMDescription

---

## UI Design System

### Hierarchy & Data Model

**Connection** → **Client** → **Project**

1. **Connection** - Technical connection to external system (GitHub, Jira, Confluence...)
   - Contains: credentials, URL, auth type
   - Global or assigned to client

2. **Client** - Organization/Team
   - Has assigned Connections
   - Has default Git commit configuration for all projects
   - Can inherit or override per-project

3. **Project** - Specific project within client
   - Selects sources from client's connections
   - Can override Git commit configuration

### Design Principles

**Consistency:** Use shared components from `com.jervis.ui.design`

**Fail-Fast:** Show errors openly via `JErrorState`, don't hide

**Unified States:** Loading/error/empty via shared components

**Mobile-First:** All screens in `shared/ui-common` must work on Desktop + iPhone

**No Secrets Masking:** Passwords, tokens, keys always visible (private app)

### Shared Components

**Layout:**
- `JTopBar(title, onBack, actions)` - Navigation bar
- `JSection(title, content)` - Logical block with background
- `JActionBar(content)` - Action buttons (right-aligned)

**Tables & Lists:**
- `JTableHeaderRow`, `JTableHeaderCell` - Table header
- `JTableRowCard(selected, content)` - List/table row card

**States:**
- `JCenteredLoading()` - Centered spinner
- `JErrorState(message, onRetry)` - Error with retry
- `JEmptyState(message, icon)` - Empty state

**Utilities:**
- `JRunTextButton(onClick, enabled, text)` - Action button with ▶
- `ConfirmDialog(...)` - Confirmation dialog
- `RefreshIconButton()`, `DeleteIconButton()`, `EditIconButton()` - Standard buttons
- `CopyableTextCard(text, label)` - Clickable text card

### Responsive Design

**✅ DO:**
- Use `Modifier.fillMaxWidth()`
- Implement scrolling for narrow displays
- Touch targets ≥ 44dp on mobile
- Column layouts for narrow screens
- Readable text on small displays

**❌ DON'T:**
- Fixed widths
- Content overflow on mobile
- Tiny touch targets

### Spacing

```kotlin
// JervisSpacing constants
outerPadding        // 10.dp - outer margin
sectionPadding      // 12.dp - section interior
itemGap             // 8.dp  - element spacing
```

### Component Migration

| Before | After | Reason |
|--------|-------|--------|
| `TopAppBar` | `JTopBar` | Consistency |
| `CircularProgressIndicator` centered | `JCenteredLoading()` | Unified state |
| Custom loading/error UI | Shared components | Consistency |
| Direct layout | `JSection` | Organized blocks |
| Inline actions | `JActionBar` | Consistent action bar |

---

## Shared Architecture

### Module Structure

- **backend/server**: Spring Boot WebFlux (orchestrator, RAG, scheduling, integrations)
- **backend/service-***: Compute-only services (joern, tika, whisper, atlassian)
- **shared/common-dto**: DTO objects
- **shared/common-api**: `@HttpExchange` contracts
- **shared/domain**: Pure domain types
- **shared/ui-common**: Compose Multiplatform UI screens
- **apps/desktop**: Primary desktop application
- **apps/mobile**: iOS/Android port from desktop

### Communication Contract

- UI ↔ Server: ONLY `@HttpExchange` interfaces in `shared/common-api`
- Server ↔ Microservices: REST via `@HttpExchange` in `backend/common-services`
- No UI access to internal microservice contracts

---

## Documentation & AI Assistant Guidelines

### When Working with AI/LLM Tools (CRITICAL)

**❌ NEVER create:**
- Standalone documentation files just to dump information
- Summary/cleanup/status files that duplicate existing docs
- "Helper" markdown files that could go in logs or comments
- Multiple files with similar content (causes confusion & merge conflicts)

**✅ ALWAYS do:**
- Integrate documentation into existing files ONLY
- Use table of contents and cross-references
- Single source of truth per topic
- Output logs/summaries to console, not new files
- If something doesn't fit in existing docs, it probably shouldn't be documented

### Documentation Principles

1. **Existing Files Only**
   - ✅ Update `security.md`, `operations.md`, `implementation-notes.md`, etc.
   - ✅ Add new sections with table of contents entries
   - ❌ Create `NEW_FEATURE_STATUS.md`, `IMPLEMENTATION_PLAN.md`, etc.

2. **No Status/Cleanup Files**
   - ❌ Files like `CONSOLIDATION_SUMMARY.md`, `DELETE_THESE_FILES.md`, `FINAL_SUMMARY.md`
   - ❌ Temporary documentation that exists only to explain other docs
   - ✅ Summaries go in console output or git commit messages

3. **Cross-References**
   - If adding OAuth2 info, update `README.md` table of contents
   - Link related sections: "See also: security.md → OAuth2 Multi-User Support"
   - Keep related info together

4. **Consolidation**
   - Do NOT create separate files to consolidate other files
   - Directly merge content into proper home file
   - Remove redundancy, don't document the removal process

### Exception: Source Code Comments

- ✅ Comments in `.kt`, `.swift`, `.ts` files are OK
- ✅ Inline documentation for complex logic
- ✅ KDoc for public APIs
- But: Comments in docs should go directly to the doc, not to a separate file

### When You Violate This Rule

If you accidentally create unnecessary files:
1. List them in console output
2. Delete them IMMEDIATELY
3. Consolidate content into existing docs
4. Do NOT create "cleanup" or "deletion instruction" files

---

## Summary

**Key Practices:**
1. Fail-fast errors, fail-fast validation
2. Type-safe, idiomatic Kotlin
3. HTTP client selection by use case
4. String-based LLM integration with templating
5. Consistent, responsive UI with shared components
6. Clear module boundaries and contracts
7. **Documentation in existing files ONLY - no status/summary files**