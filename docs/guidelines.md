# Jervis – Engineering & Architecture Guidelines (2026)

**Status:** Production Documentation (2026-02-05)
**Purpose:** Single source of truth for engineering, architecture, and UI guidelines

---

## Table of Contents

1. [Core Development Principles](#core-development-principles)
2. [Kotlin & Code Style](#kotlin--code-style)
3. [Error Handling & Logging](#error-handling--logging)
4. [HTTP Clients & Service Integration](#http-clients--service-integration)
5. [LLM Integration & Prompting](#llm-integration--prompting)
6. [UI Design System](#ui-design-system)
7. [Architecture & Modules](#architecture--modules)
8. [Security & Operations](#security--operations)

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

### DTO & Data Modeling

**❌ NO String Constants for Types:**
- Never use `String` for fixed sets of values (e.g., `type: String` for "HTTP", "IMAP").
- ✅ ALWAYS use `enum class` (e.g., `ConnectionTypeEnum`).

**❌ NO UI-Modifiable State in DTOs:**
- `state` fields in DTOs (e.g., `ConnectionStateEnum`) are for **read-only display** in UI.
- UI must NOT allow changing state directly.
- State changes happen only in backend services (e.g., on error, on successful connection test).

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
".trimIndent())
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

> **SSOT:** Full design system documentation is in **[`docs/ui-design.md`](ui-design.md)**.
> This section is a quick-reference summary. When in conflict, `ui-design.md` is authoritative.

### Core Principles

- **Consistency:** Use shared components from `com.jervis.ui.design` (JTopBar, JSection, JActionBar, etc.)
- **Adaptive layout:** `COMPACT_BREAKPOINT_DP = 600` – phone (<600dp) vs tablet/desktop (≥600dp)
- **Fail-fast in UI:** Show errors via `JErrorState`, never hide
- **Touch targets ≥ 44dp:** `JervisSpacing.touchTarget` – all clickable elements
- **No secrets masking:** Passwords, tokens, keys always visible (private app)

### Key Adaptive Components

| Component | Use when |
|-----------|---------|
| `JAdaptiveSidebarLayout` | Category-based navigation (settings, admin panels) |
| `JListDetailLayout` | Entity list with create/edit/detail (clients, projects) |
| `JDetailScreen` | Edit forms – provides consistent back nav + save/cancel bar |
| `JNavigationRow` | Touch-friendly navigation rows in compact mode |

### Shared Form Helpers

| Helper | Location | Purpose |
|--------|----------|---------|
| `GitCommitConfigFields(...)` | `ClientsSettings.kt` (internal) | Reusable git commit config form |
| `getCapabilityLabel(capability)` | `ClientsSettings.kt` (internal) | Human-readable capability labels |
| `getIndexAllLabel(capability)` | `ClientsSettings.kt` (internal) | "Index all..." labels per capability |

### Quick Decision Tree

```
Category-based screen?         → JAdaptiveSidebarLayout
Entity CRUD list?              → JListDetailLayout + JDetailScreen
Simple flat list with actions? → LazyColumn + JActionBar + state components
Edit form?                     → JDetailScreen (provides back + save/cancel)
```

### Card & Spacing Standards

- Cards: `CardDefaults.outlinedCardBorder()` (no elevation, no surfaceVariant)
- Spacing: `JervisSpacing.outerPadding` (10dp), `.sectionPadding` (12dp), `.itemGap` (8dp), `.touchTarget` (44dp)
- Between form sections: `Arrangement.spacedBy(16.dp)`

### Forbidden Patterns

| Don't | Do instead |
|-------|-----------|
| `Card(elevation/surfaceVariant)` | `Card(border = outlinedCardBorder())` |
| `Box { CircularProgressIndicator() }` | `JCenteredLoading()` |
| Inline save/cancel in form | `JDetailScreen(onSave, onBack)` |
| Fixed sidebar without adaptive | `JAdaptiveSidebarLayout` |
| `IconButton` without explicit 44dp size | `IconButton(Modifier.size(touchTarget))` |
| `TopAppBar` directly | `JTopBar(title, onBack, actions)` |
| Duplicating `getCapabilityLabel()` | Import from `ClientsSettings.kt` |

---

## Architecture & Modules

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

## Security & Operations

### Security Headers

- Server-side `SecurityHeaderFilter` REQUIRES `X-Jervis-Client` header with shared token
- Missing/invalid value → connection without response (port scanning prevention)
- Client MUST send:
  - `X-Jervis-Client: <token>`
  - `X-Jervis-Platform: Desktop|Android|iOS`

### Development Mode Rules

- **No deprecations, no compatibility layers:** Deprecated code is forbidden
- **Policy: No Deprecated Code**
  - NEVER create `@Deprecated` annotations
  - NEVER leave old code "for compatibility"
  - If change needed, refactor ENTIRE code including all usage
  - Deprecated functionality is IMMEDIATELY removed
- UI shows all values: passwords, tokens, keys always visible (private app)
- DocumentDB (Mongo): nothing encrypted; store in "plain text" (private dev instance)

---

## Summary

### Key Practices

1. **Fail-fast errors, fail-fast validation**
2. **Type-safe, idiomatic Kotlin**
3. **HTTP client selection by use case**
4. **String-based LLM integration with templating**
5. **Consistent, responsive UI with shared components**
6. **Clear module boundaries and contracts**
7. **Documentation in existing files ONLY - no status/summary files**

### Required Components

- `com.jervis.ui.util.DeleteIconButton` - For all delete buttons (per-row)
- `com.jervis.ui.util.ConfirmDialog` - For all delete confirmations
- `com.jervis.ui.util.CopyableTextCard` - For all text content with copy functionality

---

**Document Version:** 2.0
**Last Updated:** 2026-02-04
**Applies To:** All engineering, architecture, and UI development in Jervis