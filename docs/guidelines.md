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
- For Tools: Tools throw exceptions, caller handles as tool error
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

**Use for:** Provider services with dynamic domains (user-specified URLs)

**Rate limits** are centralized in `ProviderRateLimits`:

```kotlin
// common-services/.../ratelimit/ProviderRateLimits.kt
object ProviderRateLimits {
    val GITHUB = RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 80)
    val GITLAB = RateLimitConfig(maxRequestsPerSecond = 20, maxRequestsPerMinute = 300)
    val ATLASSIAN = RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100)
}

// Usage in Application.kt:
val rateLimiter = DomainRateLimiter(ProviderRateLimits.GITHUB)
val apiClient = GitHubApiClient(httpClient, rateLimiter)
```

### Provider Response Validation

All provider API clients use `checkProviderResponse()` for typed error handling:

```kotlin
// common-services/.../http/ResponseValidation.kt
val responseText = response.checkProviderResponse("GitHub", "listRepositories")
```

Throws typed exceptions from `ProviderApiException` sealed hierarchy:
- `ProviderAuthException` (401, 403)
- `ProviderNotFoundException` (404)
- `ProviderRateLimitException` (429, parses `Retry-After` header)
- `ProviderServerException` (5xx)

### Provider Pagination Helpers

Two pagination strategies in `common-services/.../http/PaginationHelper.kt`:

**Link header** (GitHub): Parses `Link: <url>; rel="next"` header
```kotlin
paginateViaLinkHeader(httpClient, url, "GitHub", "listRepositories",
    requestBuilder = { header(...) },
    deserialize = { body -> json.decodeFromString(body) },
    maxPages = 10)
```

**Offset-based** (GitLab): Uses `x-next-page` / `x-total-pages` headers
```kotlin
paginateViaOffset("GitLab", "listProjects",
    fetchPage = { page, perPage -> /* returns Pair<List<T>, HttpResponse> */ },
    perPage = 100, maxPages = 10)
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

**Fail-fast:** No automatic fallbacks between LLM providers

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

> **Full SSOT with ASCII diagrams, all patterns and complete examples:** **[`docs/ui-design.md`](ui-design.md)**
> This section has enough detail for common tasks. For dialog patterns, expandable sections,
> typography conventions and migration checklist see `ui-design.md`.

### Source Files

| What | Where |
|------|-------|
| All `J*` components + adaptive layouts | `shared/ui-common/.../design/DesignSystem.kt` |
| Shared form helpers (GitCommitConfigFields, getCapabilityLabel) | `shared/ui-common/.../screens/settings/sections/ClientsSettings.kt` (internal) |
| Icon buttons (Refresh, Delete, Edit) | `shared/ui-common/.../util/IconButtons.kt` |
| ConfirmDialog | `shared/ui-common/.../util/ConfirmDialog.kt` |
| StatusIndicator, SettingCard, ActionRibbon | `shared/ui-common/.../components/SettingComponents.kt` |

### Core Principles

- **Consistency:** Use shared components from `com.jervis.ui.design` (JTopBar, JSection, JActionBar, etc.)
- **Adaptive layout:** `COMPACT_BREAKPOINT_DP = 600` – phone (<600dp) vs tablet/desktop (≥600dp)
- **Fail-fast in UI:** Show errors via `JErrorState`, never hide
- **Touch targets ≥ 44dp:** `JervisSpacing.touchTarget` – all clickable elements
- **No secrets masking:** Passwords, tokens, keys always visible (private app)
- **Czech UI labels:** All user-facing text in Czech, code/comments/logs in English

### Adaptive Layout – How It Works

Detection is via `BoxWithConstraints` (width-based, no platform expect/actual).

**Compact (phone < 600dp):**
- Category list as full-screen `JNavigationRow` items with icon, title, description, chevron
- Tap → full-screen section with `JTopBar` back arrow
- Entity detail replaces list entirely

**Expanded (tablet/desktop ≥ 600dp):**
- 240dp sidebar with category selection + "Zpět" button
- Content area fills remaining width
- Entity detail also replaces list (same behavior)

### Decision Tree – Which Component to Use

```
┌─ New screen type? ─────────────────────────────────────────────────┐
│                                                                     │
│  Category-based navigation (settings, admin)?                       │
│    → JAdaptiveSidebarLayout                                         │
│    Example: SettingsScreen.kt                                       │
│                                                                     │
│  Expandable list with nested items (clients + projects)?            │
│    → LazyColumn + expandable Cards + JDetailScreen for edit         │
│    Example: ClientsSettings.kt                                     │
│                                                                     │
│  Flat list with per-row actions (connections)?                       │
│    → LazyColumn + Card(outlinedCardBorder) + JActionBar at top      │
│    Example: ConnectionsSettings.kt                                  │
│                                                                     │
│  Simple scrollable form (general settings)?                         │
│    → Column(verticalScroll) with JSection blocks                    │
│    Example: GeneralSettings in SettingsScreen.kt                    │
│                                                                     │
│  Status/activity log (agent workload, live state)?                  │
│    → Status card + LazyColumn (newest first), in-memory log         │
│    Example: AgentWorkloadScreen.kt                                  │
└─────────────────────────────────────────────────────────────────────┘
```

### Key Adaptive Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JAdaptiveSidebarLayout<T>` | Sidebar (expanded) / category list (compact) | `categories`, `selectedIndex`, `onSelect`, `onBack`, `title`, `categoryIcon`, `categoryTitle`, `categoryDescription`, `content` |
| `JListDetailLayout<T>` | List with detail navigation | `items`, `selectedItem`, `isLoading`, `onItemSelected`, `emptyMessage`, `emptyIcon`, `listHeader`, `listItem`, `detailContent` |
| `JDetailScreen` | Edit form with back + save/cancel | `title`, `onBack`, `onSave?`, `saveEnabled`, `actions`, `content: ColumnScope` |
| `JNavigationRow` | Touch-friendly nav row (44dp+) | `icon`, `title`, `subtitle?`, `onClick`, `trailing` |

### Pattern 1: Category-Based Settings

```kotlin
enum class SettingsCategory(val title: String, val icon: String, val description: String) {
    GENERAL("Obecné", "⚙️", "Základní nastavení aplikace a vzhledu."),
    CLIENTS("Klienti a projekty", "🏢", "Správa klientů, projektů a jejich konfigurace."),
    CONNECTIONS("Připojení", "🔌", "Technické parametry připojení."),
    CODING_AGENTS("Coding Agenti", "🤖", "Konfigurace coding agentů."),
}

@Composable
fun SettingsScreen(repository: JervisRepository, onBack: () -> Unit) {
    val categories = remember { SettingsCategory.entries.toList() }
    var selectedIndex by remember { mutableIntStateOf(0) }

    JAdaptiveSidebarLayout(
        categories = categories,
        selectedIndex = selectedIndex,
        onSelect = { selectedIndex = it },
        onBack = onBack,
        title = "Nastavení",
        categoryIcon = { it.icon },
        categoryTitle = { it.title },
        categoryDescription = { it.description },
        content = { category -> SettingsContent(category, repository) },
    )
}
```

### Pattern 2: Entity List → Detail

```kotlin
JListDetailLayout(
    items = clients,
    selectedItem = selectedClient,
    isLoading = isLoading,
    onItemSelected = { selectedClient = it },
    emptyMessage = "Žádní klienti nenalezeni",
    emptyIcon = "🏢",
    listHeader = {
        JActionBar {
            RefreshIconButton(onClick = { loadClients() })
            JPrimaryButton(onClick = { /* new */ }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Přidat klienta")
            }
        }
    },
    listItem = { client ->
        Card(
            modifier = Modifier.fillMaxWidth().clickable { selectedClient = client },
            border = CardDefaults.outlinedCardBorder(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp).heightIn(min = JervisSpacing.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(client.name, style = MaterialTheme.typography.titleMedium)
                    Text("ID: ${client.id}", style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.KeyboardArrowRight, null,
                     tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    },
    detailContent = { client ->
        ClientEditForm(client, repository, onSave = { ... }, onCancel = { selectedClient = null })
    },
)
```

### Pattern 3: Edit Form (Detail Screen)

```kotlin
JDetailScreen(
    title = client.name,
    onBack = onCancel,
    onSave = { onSave(client.copy(name = name, ...)) },
    saveEnabled = name.isNotBlank(),
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.weight(1f).verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        JSection(title = "Základní údaje") {
            OutlinedTextField(value = name, onValueChange = { name = it },
                label = { Text("Název") }, modifier = Modifier.fillMaxWidth())
        }
        JSection(title = "Git Commit Konfigurace") {
            GitCommitConfigFields(  // Shared helper from ClientsSettings.kt
                messageFormat = ..., authorName = ..., authorEmail = ...,
                committerName = ..., committerEmail = ...,
                gpgSign = ..., gpgKeyId = ...,
                // + all onChange callbacks
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
```

### Pattern 4: Flat List with Actions (Connections, Logs)

```kotlin
Column(modifier = Modifier.fillMaxSize()) {
    JActionBar {
        JPrimaryButton(onClick = { showCreateDialog = true }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Přidat připojení")
        }
    }
    Spacer(Modifier.height(JervisSpacing.itemGap))

    if (isLoading && items.isEmpty()) JCenteredLoading()
    else if (items.isEmpty()) JEmptyState(message = "Žádná data", icon = "📋")
    else LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.weight(1f),
    ) {
        items(data) { item ->
            Card(modifier = Modifier.fillMaxWidth(), border = CardDefaults.outlinedCardBorder()) {
                Column(Modifier.padding(16.dp)) {
                    // ... content rows ...
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        JPrimaryButton(onClick = { ... }) { Text("Akce") }
                        Button(onClick = { ... }, colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )) { Icon(Icons.Default.Delete, contentDescription = "Smazat") }
                    }
                }
            }
        }
    }
}
```

### Pattern 5: Expandable Card (Nested Content)

```kotlin
var expanded by remember { mutableStateOf(false) }

Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
     border = CardDefaults.outlinedCardBorder()) {
    Column(Modifier.padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Default.KeyboardArrowUp
                 else Icons.Default.KeyboardArrowDown, null)
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
            // ... nested content ...
        }
    }
}
```

### Shared Form Helpers

| Helper | Location | Purpose |
|--------|----------|---------|
| `GitCommitConfigFields(...)` | `ClientsSettings.kt` (internal) | Reusable git commit config form (7 fields + GPG) |
| `getCapabilityLabel(capability)` | `ClientsSettings.kt` (internal) | Human-readable label: BUGTRACKER→"Bug Tracker" etc. |
| `getIndexAllLabel(capability)` | `ClientsSettings.kt` (internal) | "Indexovat všechny repozitáře" etc. |

### Card & Spacing Standards

```kotlin
// ALL list item cards:
Card(modifier = Modifier.fillMaxWidth(), border = CardDefaults.outlinedCardBorder())

// Spacing constants:
JervisSpacing.outerPadding   // 10.dp - screen margins
JervisSpacing.sectionPadding // 12.dp - JSection internal
JervisSpacing.itemGap        // 8.dp  - between items in LazyColumn
JervisSpacing.touchTarget    // 44.dp - minimum clickable height

COMPACT_BREAKPOINT_DP = 600  // phone < 600dp, tablet/desktop >= 600dp

// Between form sections: Arrangement.spacedBy(16.dp)
// Between fields in a section: Spacer(Modifier.height(JervisSpacing.itemGap))
```

### Typography Quick Reference

| Context | Style | Color |
|---------|-------|-------|
| Card title | `titleMedium` | default |
| Card subtitle / ID | `bodySmall` | `onSurfaceVariant` |
| Section title | `titleMedium` (via JSection) | `primary` |
| Capability group label | `labelMedium` | `primary` |
| Help text / hint | `bodySmall` | `onSurfaceVariant` |
| Error text | `bodySmall` | `error` |

### Forbidden Patterns

| Don't | Do instead |
|-------|-----------|
| `Card(elevation = ..., colors = surfaceVariant)` | `Card(border = outlinedCardBorder())` |
| `Box { CircularProgressIndicator() }` | `JCenteredLoading()` |
| Inline save/cancel below form | `JDetailScreen(onSave, onBack)` |
| Fixed sidebar without adaptive | `JAdaptiveSidebarLayout` |
| `IconButton` without explicit 44dp size | `IconButton(Modifier.size(JervisSpacing.touchTarget))` |
| `TopAppBar` directly | `JTopBar(title, onBack, actions)` |
| Duplicating `getCapabilityLabel()` | Import from `ClientsSettings.kt` |
| Platform expect/actual for layout | `BoxWithConstraints` width check (automatic in J* components) |
| `Row` of buttons without alignment | `JActionBar { ... }` or `Row(Arrangement.spacedBy(8.dp, Alignment.End))` |

> **More patterns:** Dialog patterns (selection, multi-select, create, delete confirm),
> screen anatomy ASCII diagrams, file structure reference → see **[`docs/ui-design.md`](ui-design.md)**

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