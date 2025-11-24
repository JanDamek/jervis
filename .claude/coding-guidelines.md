# Jervis - Coding Guidelines

Tento dokument obsahuje pravidla a konvence pro vývoj projektu Jervis.

**⚠️ VŽDY SI PŘED PROGRAMOVÁNÍM PŘEČTI TYTO GUIDELINES, ABY SIS NEDĚL STEJNÉ CHYBY!**

---

## Kotlin Style - KRITICKY DŮLEŽITÉ!

### Základní Princip

**Toto je Kotlin Coroutines/Flow aplikace, NE Java napsaná v Kotlinu!**

- ✅ Používej Kotlin idiomy (data classes, sealed classes, extension functions)
- ✅ Používej suspend functions a Flow (NE Mono/Flux všude)
- ✅ Používej type-safe wrappers pro primitives (JvmInline value classes)
- ✅ Preferuj immutability (val over var, immutable collections)
- ✅ **ŽÁDNÝ CACHE** - MongoDB s Flow je dostatečně rychlé, cache přidává pouze komplexitu
- ❌ NEPÍŠ Javu v Kotlinu!
- ❌ NEPOUŽÍVEJ verbose Java patterns kde existuje Kotlin idiom
- ❌ **NIKDY nevytvářej cache vrstvy** - vše směruj přímo na repository

### Type Safety - JvmInline Value Classes

**VŽDY používej typované wrappery místo голých String/Int/Long hodnot:**

```kotlin
// ❌ ŠPATNĚ - голé primitives
data class User(
    val accountId: String,      // Jaký typ accountId? Jira? Confluence? Email?
    val connectionId: String,   // String reprezentuje ObjectId?
    val port: Int,              // Port pro co? HTTP? SMTP? IMAP?
    val timeout: Long           // Timeout v čem? ms? seconds?
)

// ✅ SPRÁVNĚ - type-safe wrappers
@JvmInline
value class JiraAccountId(val value: String)

@JvmInline
value class ConnectionId(val value: ObjectId)

@JvmInline
value class Port(val value: Int) {
    init {
        require(value in 1..65535) { "Invalid port: $value" }
    }
}

@JvmInline
value class TimeoutMs(val value: Long) {
    init {
        require(value > 0) { "Timeout must be positive" }
    }
}

data class User(
    val accountId: JiraAccountId,    // Jasně Jira account ID
    val connectionId: ConnectionId,  // Jasně connection ID (ObjectId)
    val port: Port,                  // Port s validací
    val timeout: TimeoutMs           // Timeout v ms, jasně
)
```

**Výhody:**
- Compile-time type safety (nemůžeš omylem předat port místo timeout)
- Zero runtime overhead (díky @JvmInline)
- Validation v init bloku
- Lepší dokumentace (typ sám říká co obsahuje)
- Nemůžeš omylem mixnout různé string IDs

### Serialization - Elegantní Řešení

**NIKDY neduplikuj @Serializable(with = XSerializer::class) všude!**

```kotlin
// ❌ ŠPATNĚ - duplikace serializer anotace
@Serializable
data class Issue(
    val key: String,
    @Serializable(with = InstantSerializer::class)
    val created: Instant,
    @Serializable(with = InstantSerializer::class)
    val updated: Instant,
    @Serializable(with = InstantSerializer::class)
    val resolved: Instant?
)

// ✅ SPRÁVNĚ - použij typealias nebo wrapper type
typealias SerializableInstant = @Serializable(with = InstantSerializer::class) Instant

@Serializable
data class Issue(
    val key: String,
    val created: SerializableInstant,
    val updated: SerializableInstant,
    val resolved: SerializableInstant?
)

// ✅ JEŠTĚ LEPŠÍ - použij typ který nepotřebuje custom serializer
@Serializable
data class Issue(
    val key: String,
    val created: String,  // ISO-8601 string, parsuj až v runtime když potřebuješ
    val updated: String,
    val resolved: String?
) {
    fun createdInstant(): Instant = Instant.parse(created)
    fun updatedInstant(): Instant = Instant.parse(updated)
}

// ✅ NEBO použij Long (epoch millis)
@Serializable
data class Issue(
    val key: String,
    val createdMs: Long,
    val updatedMs: Long,
    val resolvedMs: Long?
) {
    val created: Instant get() = Instant.ofEpochMilli(createdMs)
    val updated: Instant get() = Instant.ofEpochMilli(updatedMs)
}
```

**Preferované pořadí:**
1. `String` (ISO-8601) - nejjednodušší, žádný serializer
2. `Long` (epoch millis) - kompaktní, žádný serializer
3. Typealias s anotací - pokud musíš používat Instant přímo
4. Custom serializer v každém fieldu - NIKDY!

### Kotlin Flow vs Reactor

**Toto je Flow aplikace! NIKDY nepoužívej ReactiveMongoRepository!**

```kotlin
// ❌ ŠPATNĚ - Reactor/ReactiveMongoRepository
interface UserRepository : ReactiveMongoRepository<User, ObjectId>
fun getUsers(): Flux<User>
fun getUser(id: String): Mono<User>

// ❌ ŠPATNĚ - konverze na List
suspend fun getAllUsers(): List<User> {
    return repo.findAll().toList() // NIKDY! Vrať Flow!
}

// ✅ SPRÁVNĚ - CoroutineCrudRepository s Flow
interface UserRepository : CoroutineCrudRepository<User, ObjectId> {
    fun findByName(name: String): Flow<User>
    fun findByEnabled(enabled: Boolean): Flow<User>
}

// ✅ SPRÁVNĚ - služby pracují s Flow
@Service
class UserService(private val repo: UserRepository) {
    suspend fun getUser(id: ObjectId): User? {
        return repo.findById(id) // Suspend function
    }

    fun getAllUsers(): Flow<User> {
        return repo.findAll() // Vrať Flow přímo!
    }

    fun getActiveUsers(): Flow<User> {
        return repo.findByEnabled(true) // Flow composition
    }
}

// ✅ SPRÁVNĚ - controllery mohou vrátit Flow nebo List (pokud UI potřebuje)
@RestController
class UserRestController(private val service: UserService) {

    // Flow pro streaming
    @GetMapping("/users/stream")
    fun streamUsers(): Flow<UserDto> {
        return service.getAllUsers().map { it.toDto() }
    }

    // List POUZE pro UI (combobox, dropdown, atd.)
    @GetMapping("/users")
    suspend fun listUsers(): List<UserDto> {
        return service.getAllUsers().map { it.toDto() }.toList()
    }
}
```

**Pravidla:**
- ✅ **VŽDY** `CoroutineCrudRepository` (NE ReactiveMongoRepository!)
- ✅ **VŽDY** vrať Flow z repositories a services
- ✅ **NIKDY** nepřevádět na List v service vrstvě
- ✅ **POUZE** v controlleru pro UI můžeš `.toList()` pokud UI to vyžaduje
- ✅ Používej Flow operators: `map`, `filter`, `flatMapConcat`, atd.

### Extension Functions

**Používej extension functions pro reusable logic:**

```kotlin
// ❌ ŠPATNĚ - utility class
object ConnectionUtils {
    fun extractDomain(connection: Connection.HttpConnection): String {
        return URL(connection.baseUrl).host
    }
}

// ✅ SPRÁVNĚ - extension function
fun Connection.HttpConnection.extractDomain(): String {
    return URL(baseUrl).host
}

// Použití
val domain = connection.extractDomain()
```

### Scope Functions

**Používej správný scope function pro daný use-case:**

```kotlin
// let - transformace nebo null-safety
val name = user?.let { it.firstName + " " + it.lastName }

// apply - konfigurace objektu (builder pattern)
val connection = Connection.HttpConnection(name = "test").apply {
    enabled = false
    rateLimitConfig = RateLimitConfig(maxRequestsPerSecond = 5)
}

// also - side effects (logging)
val result = calculateSomething()
    .also { logger.info { "Result: $it" } }

// run - execute block and return result
val domain = connection.run {
    URL(baseUrl).host
}

// with - multiple calls on same object
with(connection) {
    logger.info { "Name: $name" }
    logger.info { "URL: $baseUrl" }
    logger.info { "Enabled: $enabled" }
}
```

---

## Struktura Aplikace - Kde Co Je

### Backend Moduly

```
backend/server/src/main/kotlin/com/jervis/
├── configuration/              # Spring beans, HTTP client config
│   ├── http/                   # HttpClientConfiguration, DomainRateLimiter
│   └── mongo/                  # MongoIndexInitializer
│
├── controller/api/             # REST controllers (DTOs zde!)
│   ├── ConnectionRestController.kt       # Connection CRUD + test
│   ├── ClientRestController.kt           # Client management
│   ├── ProjectRestController.kt          # Project management
│   └── AgentOrchestratorRestController.kt # Chat API
│
├── domain/                     # Domain models (DEPRECATED - see entity/)
│   └── jira/                   # Většina domain modelů přesunuta do entity/
│
├── entity/                     # MongoDB documents (entity = domain!)
│   ├── connection/
│   │   └── Connection.kt       # Sealed class (HttpConnection, ImapConnection, ...)
│   ├── jira/
│   │   └── JiraIssueIndexDocument.kt  # FULL content (ne jen metadata!)
│   ├── ClientDocument.kt       # Clients s connectionIds
│   └── ProjectDocument.kt      # Projects
│
├── repository/                 # MongoDB repositories
│   ├── ConnectionMongoRepository.kt     # ✅ Connection (ReactiveMongoRepository)
│   ├── ClientMongoRepository.kt         # Client (ReactiveMongoRepository)
│   ├── ProjectMongoRepository.kt        # Project (CoroutineCrudRepository)
│   └── JiraIssueIndexMongoRepository.kt # Jira issues
│
├── service/
│   ├── connection/
│   │   └── ConnectionService.kt         # ✅ Connection CRUD + credential parsing
│   ├── http/
│   │   ├── HttpClientExtensions.kt      # ✅ getWithConnection(), postWithConnection()
│   │   ├── RateLimitedHttpClientFactory.kt  # Rate limiting per domain
│   │   └── DomainRateLimiter.kt         # Token bucket rate limiter
│   ├── atlassian/
│   │   └── AtlassianApiClient.kt        # ✅ Jira/Confluence API (getMyself, searchAndFetchFullIssues)
│   ├── polling/
│   │   ├── CentralPoller.kt             # ✅ Single poller for ALL connections
│   │   └── handler/
│   │       ├── PollingHandler.kt        # Interface pro všechny handlery
│   │       ├── JiraPollingHandler.kt    # ✅ Jira issues (HTTP Atlassian)
│   │       ├── ImapPollingHandler.kt    # ✅ IMAP emails (Jakarta Mail)
│   │       └── Pop3PollingHandler.kt    # ✅ POP3 emails (Jakarta Mail)
│   ├── jira/
│   │   ├── JiraContinuousIndexer.kt     # ✅ MongoDB → RAG (NO API calls!)
│   │   └── JiraIndexingOrchestrator.kt  # ✅ Chunking + embedding + RAG storage
│   ├── rag/
│   │   ├── KnowledgeService.kt          # Public RAG API
│   │   └── _internal/
│   │       └── KnowledgeServiceImpl.kt  # RAG implementation
│   │       └── JiraStateManager.kt      # State transitions (NEW/INDEXING/INDEXED/FAILED)
│   ├── client/
│   │   └── ClientService.kt             # Client CRUD
│   ├── project/
│   │   └── ProjectService.kt            # Project CRUD
│   └── cache/
│       └── ClientProjectConfigCache.kt   # In-memory cache for clients/projects
│
├── dto/                        # Data Transfer Objects (pouze v controllers!)
│   ├── connection/
│   │   └── ConnectionDtos.kt            # ✅ ConnectionCreateRequestDto, ConnectionResponseDto, etc.
│   └── ChatRequestContext.kt
│
└── rag/                        # Weaviate, embeddings
    └── weaviate/
```

### REST API Endpoints

```
# Connection Management
GET    /api/connections              # List all connections
GET    /api/connections/{id}         # Get connection detail
POST   /api/connections              # Create connection (plain-text credentials → encrypted)
PUT    /api/connections/{id}         # Update connection
DELETE /api/connections/{id}         # Delete connection
POST   /api/connections/{id}/test    # Test connection (pro Atlassian vrátí user info)

# Client Management
GET    /api/clients                  # List all clients
POST   /api/clients                  # Create client
PUT    /api/clients/{id}             # Update client
DELETE /api/clients/{id}             # Delete client

# Project Management
GET    /api/projects                 # List all projects
POST   /api/projects                 # Create project
PUT    /api/projects/{id}            # Update project
DELETE /api/projects/{id}            # Delete project

# Chat/Agent
POST   /api/chat                     # Chat with agent
```

### MongoDB Collections

```
connections              # Sealed class: HttpConnection, ImapConnection, etc. (@TypeAlias)
clients                  # Clients with connectionIds: List<ObjectId>
projects                 # Projects with clientId
jira_issues             # FULL content (summary, description, comments, attachments)
confluence_pages        # Confluence pages (TODO: implement polling)
git_commits            # Git commits (TODO: refactor to Connection)
email_messages         # Email messages (TODO: refactor to ImapConnection)
```

---

## Security & Credentials - PLAIN TEXT!

**KRITICKY DŮLEŽITÉ - Toto NENÍ produkční aplikace!**

```kotlin
// ✅ SPRÁVNĚ - plain text credentials v DB
@Document(collection = "connections")
data class HttpConnection(
    val name: String,
    val baseUrl: String,
    val credentials: String? = null,  // Plain text: "email:api_token"
    val password: String? = null      // Plain text password
)

// ❌ ŠPATNĚ - ŽÁDNÉ encryption!
val credentialsEncrypted: String? = null  // NEPOTŘEBUJEME!
val passwordEncrypted: String? = null     // NEPOTŘEBUJEME!

// ❌ ŠPATNĚ - ŽÁDNÝ EncryptionService!
class EncryptionService {
    fun encrypt(value: String): String  // SMAZAT!
    fun decrypt(value: String): String  // SMAZAT!
}
```

**UI Pravidla:**
- ✅ **VŽDY zobrazuj credentials v UI** (žádné hvězdičky!)
- ✅ **VŽDY zobrazuj hesla** (input type="text", ne password!)
- ✅ Vše plain text v DB i UI pro snadný debug
- ✅ Aplikace je POUZE pro mě, ne production

**Důvod:**
- Potřebuji vidět co je v DB
- Potřebuji rychle najít a opravit credentials
- Žádná security overhead pro dev aplikaci
- UI input type="text" pro hesla (ne "password")

---

## CentralPoller Architecture - Coroutines Loop

**Polling = nekonečná coroutine smyčka nad Flow active connections**

```kotlin
@Service
class CentralPoller(
    private val connectionRepository: ConnectionMongoRepository,
    private val clientRepository: ClientMongoRepository,
    private val handlers: List<PollingHandler>,
    private val properties: PollingProperties, // Interval z properties!
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @PostConstruct
    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    pollAllConnections()
                } catch (e: Exception) {
                    logger.error(e) { "Polling error" }
                }

                // Počkat podle properties (např. 1 minuta)
                delay(properties.pollingIntervalMs)
            }
        }
    }

    private suspend fun pollAllConnections() {
        // Flow active & valid connections
        val jobs = mutableListOf<Job>()

        connectionRepository.findByEnabledTrue().collect { connection ->
            // Async job per connection
            val job = scope.async {
                pollConnection(connection)
            }
            jobs.add(job)
        }

        // Počkat na všechny joby
        jobs.joinAll()
    }

    private suspend fun pollConnection(connection: Connection) {
        // Najdi handler podle typu connection
        val handler = handlers.firstOrNull { it.canHandle(connection) }
            ?: return

        // Najdi clients používající tuto connection
        clientRepository.findByConnectionIdsContaining(connection.id).collect { client ->
            // Zavolej handler - fetchne FULL data a uloží jako NEW
            handler.poll(connection, client)
        }
    }
}

// Properties configuration
@ConfigurationProperties("polling")
data class PollingProperties(
    val pollingIntervalMs: Long = 60_000, // 1 minuta default
    val enabled: Boolean = true
)
```

**Polling Flow:**
1. Loop přes `findByEnabledTrue()` Flow ✅
2. Pro každou connection → `async` job ✅
3. V async: najdi handler, zavolej poll() ✅
4. Handler fetchne FULL data z API ✅
5. Handler uloží do MongoDB jako NEW ✅
6. `joinAll()` na konci všech jobů ✅
7. Počkat `pollingIntervalMs` a opakovat ✅

**Continuous Indexing Flow:**
1. Loop přes Flow NEW items z MongoDB
2. Pokud žádné NEW → počkat (delay podle properties)
3. Indexovat do RAG, označit INDEXED
4. Opakovat

**Klíčové body:**
- ✅ Flow všude (ne List!)
- ✅ `async` per connection (paralelní zpracování)
- ✅ `joinAll()` na konci
- ✅ Interval z properties
- ✅ Nekonečný loop s delay

---

## Connection Architecture (Sealed Class Hierarchy)

### Základní Princip

**Všechna externí připojení používají jednotný Connection sealed class:**

- `Connection` sealed class s `@TypeAlias` pro polymorfismus v MongoDB
- Různé typy: `HttpConnection`, `ImapConnection`, `Pop3Connection`, `SmtpConnection`, `OAuth2Connection`
- **ŽÁDNÁ separace domain/entity** - entity se používá přímo v services
- Entity končí/začíná na Controller boundary (REST DTOs pouze tam)
- **Credentials jsou PLAIN TEXT** (žádné encryption!)
- Collection `connections` obsahuje VŠE (discriminated union přes @TypeAlias)

```kotlin
@Document(collection = "connections")
sealed class Connection {
    abstract val id: ObjectId
    abstract val name: String
    abstract val enabled: Boolean
    abstract val rateLimitConfig: RateLimitConfig

    @TypeAlias("HttpConnection")
    data class HttpConnection(
        override val id: ObjectId = ObjectId.get(),
        override val name: String,
        val baseUrl: String,
        val authType: AuthType = AuthType.NONE,
        val credentialsEncrypted: String? = null,
        val timeoutMs: Long = 30000,
        override val rateLimitConfig: RateLimitConfig = RateLimitConfig(),
        override val enabled: Boolean = true
    ) : Connection()
}
```

### ConnectionService - Správa Připojení

**ConnectionService je centrální služba pro CRUD a encryption/decryption:**

```kotlin
@Service
class ConnectionService(
    private val repository: ConnectionMongoRepository,
    private val encryptionService: EncryptionService
) {
    // CRUD
    suspend fun create(connection: Connection): Connection
    suspend fun update(connection: Connection): Connection
    suspend fun findById(id: ObjectId): Connection?
    suspend fun findByName(name: String): Connection?
    suspend fun findAllEnabled(): List<Connection>

    // Runtime decryption (NIKDY neukladat plain-text!)
    suspend fun decryptCredentials(connection: Connection): HttpCredentials?
}
```

**Runtime credentials (NIKDY v DB plain-text!):**
```kotlin
sealed class HttpCredentials {
    data class Basic(val username: String, val password: String) : HttpCredentials()
    data class Bearer(val token: String) : HttpCredentials()
    data class ApiKey(val headerName: String, val apiKey: String) : HttpCredentials()
}
```

### Client / Connection Binding

**ClientDocument obsahuje seznam connectionIds:**

```kotlin
@Document(collection = "clients")
data class ClientDocument(
    @Id val id: ObjectId = ObjectId.get(),
    val name: String,
    val connectionIds: List<ObjectId> = emptyList(), // References to Connection
)
```

**Jak to funguje:**
1. Client má seznam `connectionIds`
2. CentralPoller se dotáže: "Které klienty používají tuto connection?"
3. Pro každého klienta se spustí polling s tou connection
4. Connection může být sdílená více klienty

---

## HTTP Klient Architektura

**NIKDY nevytvářet ad-hoc HttpClient instances:**

```kotlin
// ❌ ŠPATNĚ
val client = HttpClient()

// ✅ SPRÁVNĚ - globální bean
@Service
class AtlassianApiClient(
    private val httpClient: HttpClient, // Ktor
    private val connectionService: ConnectionService
)
```

**Použití s Connection:**
```kotlin
suspend fun callApi(connection: Connection.HttpConnection) {
    val credentials = connectionService.decryptCredentials(connection)
    val response = httpClient.getWithConnection(
        url = "${connection.baseUrl}/rest/api/3/myself",
        connection = connection,
        credentials = credentials
    )
}
```

**Centrální konfigurace:**
- Jeden globální Ktor `HttpClient` bean
- Rate limiting **per domain** (ne per service/connection)
- Auth headers automaticky z Connection
- Domain se extrahuje z URL, rate limit aplikován automaticky

---

## Rate Limiting

**Per domain, ne per service/connection:**

```kotlin
data class RateLimitConfig(
    val maxRequestsPerSecond: Int = 10,
    val maxRequestsPerMinute: Int = 100,
    val enabled: Boolean = true
)
```

- `DomainRateLimiter` si drží mapu `domain -> RateLimitState`
- TTL cleanup (expired domains se smažou automaticky)
- Pokud je limit překročen, request čeká

---

## MongoDB Repository Pattern

### ReactiveMongoRepository vs CoroutineCrudRepository

**KRITICKY DŮLEŽITÉ - DVA různé typy:**

```kotlin
// ❌ ŠPATNĚ - zapomenout na awaitSingle()
interface ClientRepository : ReactiveMongoRepository<ClientDocument, ObjectId>

suspend fun getClient(id: ObjectId): ClientDocument {
    return clientRepository.findById(id) // CHYBA! Vrací Mono<ClientDocument>
}

// ✅ SPRÁVNĚ - s ReactiveMongoRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull

interface ClientRepository : ReactiveMongoRepository<ClientDocument, ObjectId>

suspend fun getClient(id: ObjectId): ClientDocument? {
    return clientRepository.findById(id).awaitSingleOrNull()
}

suspend fun getAllClients(): List<ClientDocument> {
    return clientRepository.findAll().collectList().awaitSingle()
}

// ✅ SPRÁVNĚ - s CoroutineCrudRepository (nový kód)
interface ConnectionRepository : CoroutineCrudRepository<Connection, ObjectId>

suspend fun getConnection(id: ObjectId): Connection? {
    return connectionRepository.findById(id) // Přímo suspend
}

suspend fun getAllConnections(): List<Connection> {
    return connectionRepository.findAll().toList() // Flow<T>.toList()
}
```

**Kdy použít který:**
- `ReactiveMongoRepository` - existující kód (ClientDocument)
- `CoroutineCrudRepository` - nový kód, lepší pro Kotlin (Connection, Project)
- **NIKDY sync `MongoRepository`** - blokující!

---

## Continuous Indexing Pattern

**KRITICKY DŮLEŽITÉ - Separace Pollingu a Indexování:**

### 1. CentralPoller (má přístup k API)

- Polluje externí API
- Stahuje **KOMPLETNÍ** data (details, comments, attachments, atd.)
- Ukládá VŠE do MongoDB jako **NEW**
- Kontroluje co už je stažené (incremental)
- Stahuje pouze nové/změněné věci

### 2. ContinuousIndexer (NEMÁ přístup k API)

- Čte **NEW** state documents z MongoDB
- Všechna data už má (poller je stáhl)
- Indexuje do RAG (chunking, embeddings, Weaviate)
- Označí jako **INDEXED**
- **NIKDY NEVOLÁ EXTERNÍ API**

**Proč:**
- Polling = rychlý bulk (stáhne vše)
- Indexing = pomalý (embeddings)
- Pokud indexing selže, data v MongoDB (retry)
- Rate limiting jen při pollingu

### Příklad - Jira Polling

```kotlin
@Component
class JiraPollingHandler(
    private val apiClient: AtlassianApiClient,
    private val repository: JiraIssueIndexMongoRepository
) : PollingHandler {

    override suspend fun poll(
        connection: Connection,
        credentials: HttpCredentials?,
        clients: List<ClientDocument>
    ): PollingResult {
        val httpConnection = connection as Connection.HttpConnection

        for (client in clients) {
            // 1. Build JQL (with filters from client config if any)
            val jql = "updated >= -7d"

            // 2. Fetch FULL issues (one API call with all fields)
            val fullIssues = apiClient.searchAndFetchFullIssues(
                connection = httpConnection,
                credentials = credentials,
                clientId = client.id,
                jql = jql,
                maxResults = 100
            )

            // 3. Save to MongoDB
            for (fullIssue in fullIssues) {
                val existing = repository.findByConnectionIdAndIssueKey(
                    connectionId = connection.id,
                    issueKey = fullIssue.issueKey
                )

                if (existing != null && existing.jiraUpdatedAt >= fullIssue.jiraUpdatedAt) {
                    skipped++
                    continue
                }

                if (existing != null) {
                    // Update, reset to NEW
                    val updated = fullIssue.copy(id = existing.id, state = "NEW")
                    repository.save(updated)
                } else {
                    // New issue
                    repository.save(fullIssue)
                }
            }
        }

        return PollingResult(itemsCreated = created, itemsSkipped = skipped)
    }
}
```

### Příklad - Jira Indexing

```kotlin
@Service
class JiraContinuousIndexer(
    private val stateManager: JiraStateManager,
    private val orchestrator: JiraIndexingOrchestrator
) {
    private suspend fun indexContinuously() {
        stateManager.continuousNewIssues().collect { doc ->
            try {
                indexIssue(doc)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index ${doc.issueKey}" }
                stateManager.markAsFailed(doc, e.message)
            }
        }
    }

    private suspend fun indexIssue(doc: JiraIssueIndexDocument) {
        stateManager.markAsIndexing(doc)

        // Use data from MongoDB (NO API CALLS!)
        val result = orchestrator.indexSingleIssue(
            clientId = doc.clientId,
            document = doc // Full document with all data
        )

        if (result.success) {
            stateManager.markAsIndexed(doc, result.summaryChunks, result.commentChunks)
        } else {
            stateManager.markAsFailed(doc, result.error)
        }
    }
}
```

### Document Structure - FULL Content

```kotlin
@Document(collection = "jira_issues")
data class JiraIssueIndexDocument(
    @Id val id: ObjectId = ObjectId.get(),

    // References
    @Indexed val connectionId: ObjectId, // NOT accountId!
    @Indexed val clientId: ObjectId,

    // Metadata
    @Indexed val issueKey: String,
    val projectKey: String,

    // === FULL CONTENT (fetched by poller) ===
    val summary: String,
    val description: String? = null,
    val issueType: String,
    val status: String,
    val priority: String? = null,
    val assignee: String? = null,
    val reporter: String? = null,
    val labels: List<String> = emptyList(),
    val comments: List<JiraComment> = emptyList(), // FULL
    val attachments: List<JiraAttachment> = emptyList(), // FULL
    val linkedIssues: List<String> = emptyList(),
    val createdAt: Instant,
    val jiraUpdatedAt: Instant,

    // === STATE ===
    @Indexed val state: String = "NEW", // NEW/INDEXING/INDEXED/FAILED
    @Indexed val updatedAt: Instant = Instant.now(),
    val lastIndexedAt: Instant? = null,
    val archived: Boolean = false,

    // === STATS ===
    val totalRagChunks: Int = 0,
    val commentChunkCount: Int = 0,
    val attachmentCount: Int = 0
)

data class JiraComment(
    val id: String,
    val author: String,
    val body: String,
    val created: Instant,
    val updated: Instant
)

data class JiraAttachment(
    val id: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val downloadUrl: String,
    val created: Instant
)
```

**Klíčové body:**
- MongoDB = "staging area" mezi API a RAG
- Document obsahuje KOMPLETNÍ data (ne jen metadata)
- Indexer je čistý ETL: MongoDB → Weaviate
- State management: NEW → INDEXING → INDEXED/FAILED

---

## CentralPoller Pattern

**Jeden poller pro všechny connection types:**

```kotlin
@Service
class CentralPoller(
    private val connectionService: ConnectionService,
    private val clientRepository: ClientMongoRepository,
    private val handlers: List<PollingHandler>
) {
    @PostConstruct
    fun start() {
        scope.launch {
            delay(10_000) // Initial delay

            while (isActive) {
                pollAllConnections()
                delay(5_000)
            }
        }
    }

    private suspend fun pollAllConnections() {
        val connections = connectionService.findAllEnabled()

        for (connection in connections) {
            // Find clients using this connection
            val clients = clientRepository
                .findByConnectionIdsContaining(connection.id)
                .collectList()
                .awaitSingle()

            if (clients.isEmpty()) continue

            // Find handler
            val handler = handlers.firstOrNull { it.canHandle(connection) }
                ?: continue

            // Decrypt credentials
            val credentials = connectionService.decryptCredentials(connection)

            // Poll
            val result = handler.poll(connection, credentials, clients)
            logger.info { "Polled ${connection.name}: ${result.itemsCreated} created" }
        }
    }
}
```

---

## Properties Pattern

**Každá služba MUSÍ:**

1. Mít `@ConfigurationProperties` POJO
2. Číst z `application.yml`
3. NIKDY nehardcodovat

```kotlin
// ✅ SPRÁVNĚ
@Service
class SomeService(
    private val properties: ServiceProperties
)

@ConfigurationProperties("service")
data class ServiceProperties(
    val someValue: String,
    val timeout: Long = 30000
)

// ❌ ŠPATNĚ - parametry v constructoru
@Service
class SomeService(
    private val someValue: String
)
```

---

## Logging

1. **Startup**: logovat co se děje, initial delay, intervaly
2. **Errors**: Očekávané (duplicate) = WARN, neočekávané = ERROR se stack trace
3. **Progress**: logovat statistiky (processed, skipped, failed)

---

## MongoDB Structure Changes

**MongoDB collections requiring drops:**

### Drop these collections:
```bash
use jervis_db
db.jira_issue_index.drop()  # Old structure
db.atlassian_connections.drop()  # Replaced by connections
```

### New structure:
- `connections` - unified (sealed class with @TypeAlias)
- `jira_issues` - new structure with `connectionId` and full content
- `clients` - now has `connectionIds: List<ObjectId>`

---

## Connection Management System - Kompletní Implementace

### 🎯 Architecture Overview

**Koncept:** Jeden unified Connection systém pro všechny externí služby (Jira, Confluence, IMAP, POP3, SMTP, atd.)

```
1. Connection (entity) - kredenciály + konfigurace
2. Client/Project - přiřazují connections s filtry
3. CentralPoller - polluje všechny enabled connections
4. Type-specific Handlers - zpracovávají jednotlivé typy
5. MongoDB staging area - ukládá FULL content
6. ContinuousIndexer - indexuje do RAG
```

### ✅ Connection Entity - Sealed Class

**Location:** `backend/server/src/main/kotlin/com/jervis/entity/connection/Connection.kt`

```kotlin
@TypeAlias("Connection")
sealed class Connection {
    abstract val id: ObjectId
    abstract val name: String
    abstract val enabled: Boolean

    // HTTP (Atlassian, REST APIs)
    data class HttpConnection(
        override val id: ObjectId = ObjectId.get(),
        override val name: String,
        override val enabled: Boolean = true,
        val baseUrl: String,
        val authType: HttpAuthType,
        val credentials: String?, // Plain text: "email:api_token" or "bearer_token"
        val rateLimitConfig: RateLimitConfig? = null,
        val timeoutMs: Long = 30000
    ) : Connection()

    // IMAP (Gmail, Outlook, atd.)
    data class ImapConnection(
        override val id: ObjectId = ObjectId.get(),
        override val name: String,
        override val enabled: Boolean = true,
        val host: String,
        val port: Int,
        val username: String,
        val password: String, // Plain text
        val useSsl: Boolean = true,
        val folderName: String = "INBOX"
    ) : Connection()

    // POP3
    data class Pop3Connection(...)

    // SMTP
    data class SmtpConnection(...)

    // OAuth2
    data class OAuth2Connection(...)
}
```

**MongoDB Collection:** `connections`
**Poznámka:** Sealed class vyžaduje `@TypeAlias` pro polymorphic serialization

### ✅ Client/Project Connection Assignment

**ClientDocument:**
```kotlin
data class ClientDocument(
    val id: ObjectId,
    val name: String,

    // ✅ NEW: Unified connections
    val connectionIds: List<ObjectId> = emptyList(),
    val connectionFilters: List<ConnectionFilter> = emptyList(),

    // ❌ DEPRECATED (ale stále existují pro backward compatibility)
    @Deprecated("Use connectionIds instead")
    val atlassianConnectionId: ObjectId? = null,
    @Deprecated("Use connectionFilters instead")
    val atlassianJiraProjects: List<String> = emptyList()
)
```

**ConnectionFilter:**
```kotlin
data class ConnectionFilter(
    val connectionId: ObjectId,

    // Jira-specific
    val jiraProjects: List<String> = emptyList(),      // ["PROJ", "DEV"]
    val jiraBoardIds: List<String> = emptyList(),

    // Confluence-specific
    val confluenceSpaces: List<String> = emptyList(),  // ["SUPPORT", "DOCS"]

    // Email-specific
    val emailFolders: List<String> = emptyList()       // ["INBOX", "Support"]
)
```

**ProjectDocument:** Stejná struktura - project-level přebíjí client-level

### ✅ CentralPoller - Single Poller for All

**Location:** `backend/server/src/main/kotlin/com/jervis/service/polling/CentralPoller.kt`

**Flow:**
1. Každých 5 sekund polluje všechny `enabled` connections
2. Pro každou connection najde klienty: `clientRepository.findByConnectionIdsContaining(connectionId)`
3. Najde správný handler: `handlers.firstOrNull { it.canHandle(connection) }`
4. Parse credentials: `connectionService.parseCredentials(connection)`
5. Spustí polling: `handler.poll(connection, credentials, clients)`

**Polling Intervals:**
- HTTP (Jira/Confluence): 5 minut
- IMAP: 1 minuta
- POP3: 2 minuty
- SMTP: 1 hodina (většinou pro sending, ne polling)

### ✅ Polling Handlers

**Interface:** `backend/server/src/main/kotlin/com/jervis/service/polling/handler/PollingHandler.kt`

```kotlin
interface PollingHandler {
    fun canHandle(connection: Connection): Boolean

    suspend fun poll(
        connection: Connection,
        credentials: HttpCredentials?,
        clients: List<ClientDocument>
    ): PollingResult
}
```

**Implementované Handlers:**

1. **JiraPollingHandler** (`JiraPollingHandler.kt`)
   - `canHandle`: `connection is HttpConnection && baseUrl.contains("atlassian.net")`
   - Používá `AtlassianApiClient.searchAndFetchFullIssues()`
   - Parsuje `connectionFilters.jiraProjects` do JQL
   - Ukládá do `jira_issue_index` (state = NEW)

2. **ImapPollingHandler** (`ImapPollingHandler.kt`)
   - `canHandle`: `connection is ImapConnection`
   - Používá Jakarta Mail API
   - Polluje posledních 50 zpráv
   - Ukládá do `email_message_index` (state = NEW)

3. **Pop3PollingHandler** (`Pop3PollingHandler.kt`)
   - `canHandle`: `connection is Pop3Connection`
   - Podobné jako IMAP, ale pro POP3 protocol
   - Ukládá do `email_message_index`

### ✅ MongoDB Staging Area

**JiraIssueIndexDocument:** `jira_issue_index` collection
```kotlin
data class JiraIssueIndexDocument(
    val id: ObjectId,
    val clientId: ObjectId,
    val connectionId: ObjectId,

    // FULL content (ne jen metadata!)
    val issueKey: String,
    val summary: String,
    val description: String?,
    val comments: List<JiraComment> = emptyList(),    // FULL
    val attachments: List<JiraAttachment> = emptyList(), // FULL

    // State machine
    val state: String = "NEW", // NEW → INDEXED → ARCHIVED
    val indexedAt: Instant? = null,
    val updatedAt: Instant = Instant.now()
)
```

**EmailMessageIndexDocument:** `email_message_index` collection
```kotlin
data class EmailMessageIndexDocument(
    val id: ObjectId,
    val clientId: ObjectId,
    val connectionId: ObjectId,

    // FULL content
    val messageUid: String,
    val subject: String,
    val from: String,
    val to: List<String>,
    val textBody: String?,
    val htmlBody: String?,
    val attachments: List<EmailAttachment> = emptyList(),

    // State machine
    val state: String = "NEW", // NEW → INDEXED → ARCHIVED
    val indexedAt: Instant? = null
)
```

### ✅ RAG Indexing

**JiraIndexingOrchestrator** (`JiraIndexingOrchestrator.kt`)
- Čte z `jira_issue_index` WHERE state = "NEW"
- NIKDY nevolá Jira API!
- Vytváří `DocumentToStore` pro RAG:
  - Main issue: summary + description + metadata
  - Každý comment jako separate document s `relatedDocs`
- Volá `knowledgeService.store(StoreRequest(documents))`
- Mění state na "INDEXED"

**EmailIndexingOrchestrator** (připraven, ale neimplementován)
- Bude číst z `email_message_index` WHERE state = "NEW"
- Indexuje emails do RAG

### ✅ REST API - ConnectionRestController

**Endpoints:**
- `GET /api/connections` - list all
- `GET /api/connections/{id}` - get by ID
- `POST /api/connections` - create
- `PUT /api/connections/{id}` - update
- `DELETE /api/connections/{id}` - delete
- `POST /api/connections/{id}/test` - test connection

**Test Connection Logic:**
- HTTP: Ping URL s credentials, pro Atlassian volá `/rest/api/3/myself`
- IMAP: Připojí se k serveru, otevře folder, vrátí count zpráv
- POP3: Podobné jako IMAP
- SMTP: Test autentizace

### ✅ UI - Connection Management

**ConnectionsWindow** (`apps/desktop/src/main/kotlin/com/jervis/desktop/ui/ConnectionsWindow.kt`)
- List všech connections
- Create/Edit/Delete buttons
- Test button - zobrazí výsledek (success/failure + details)
- ConnectionCreateDialog - formulář pro vytvoření connection
- ConnectionEditDialog - formulář pro editaci (s volitelným password update)

**ClientsWindow - ClientDialog**
- Multi-select connections (checkbox list)
- Zobrazuje typ každé connection (HTTP, IMAP, POP3, ...)
- Pro Atlassian connections (HTTP + atlassian.net):
  - "Filters" button → AtlassianFilterDialog

**AtlassianFilterDialog**
- **Jira Projects:** comma-separated keys (PROJ, DEV, SUPPORT)
- **Confluence Spaces:** comma-separated keys (DEV, SUPPORT, DOCS)
- Uloženo jako `ConnectionFilter` per connection per client

### ✅ Complete Data Flow Example

**Scenario:** Pollování Jira issues

1. **Setup:**
   ```
   ConnectionsWindow:
     → Create HTTP connection "Atlassian Prod"
     → URL: https://company.atlassian.net
     → Credentials: email@company.com:api_token
     → Test → Success!

   ClientsWindow:
     → Edit client "MyClient"
     → Select "Atlassian Prod" connection
     → Click "Filters"
     → Jira Projects: "PROJ, DEV"
     → Save
   ```

2. **Polling (každých 5 minut):**
   ```
   CentralPoller:
     → Find enabled connections
     → Find "Atlassian Prod" connection
     → Find clients: ["MyClient"]

     → JiraPollingHandler:
         - Get filter: connectionFilters.jiraProjects = ["PROJ", "DEV"]
         - Build JQL: "project IN ('PROJ', 'DEV') AND updated >= -7d"
         - Call AtlassianApiClient.searchAndFetchFullIssues()
         - For each issue:
             - Check if exists (by connectionId + issueKey)
             - If changed: update + state = NEW
             - If new: insert + state = NEW
         - Result: 15 issues discovered, 3 created, 12 skipped
   ```

3. **Indexing:**
   ```
   JiraContinuousIndexer (každých 10s):
     → Find jira_issue_index WHERE state = "NEW"
     → For each issue:
         - JiraIndexingOrchestrator.indexSingleIssue()
         - Create main document (summary + description)
         - Create comment documents (each comment separate)
         - KnowledgeService.store()
         - State = "INDEXED"
   ```

### 🔄 Next Steps / TODO

1. **ProjectDialog** - stejná struktura jako ClientDialog:
   - Multi-select connections
   - Filters per connection
   - Project-level přebíjí client-level

2. **EmailContinuousIndexer** - implementace indexování emailů do RAG

3. **Confluence Polling** - podobný handler jako Jira:
   - ConfluencePollingHandler
   - ConfluencePageIndexDocument
   - ConfluenceContinuousIndexer

4. **OAuth2 Connection** - implementace OAuth2 flow pro moderní API

---

**Poslední aktualizace:** 2025-01-24

**⚠️ DŮLEŽITÉ:**
Tento dokument je ŽIVÝ - aktualizuj ho po každé změně!
