# Jervis – jednotné Engineering & UI Guidelines (2025‑12)

Poslední aktualizace: 2025‑12‑03

Tento dokument je jediný zdroj pravdy (SSOT) pro architekturu, programovací pravidla a UI zásady v projektu Jervis.
Všechny ostatní historické „guidelines“ soubory jsou aliasy odkazující sem.

## 1) Cíle a principy

- **FAIL-FAST:** Chyby nezakrývat, žádné tiché fallbacky. Výjimka je lepší než maskovat chybu.
    - ❌ Try/catch uvnitř business logiky (services, tools, repositories)
    - ❌ Catching exceptions jen pro logging a re-throw
    - ❌ Generic Result<T> wrappery všude
    - ✅ Try/catch POUZE na hranicích: I/O, REST boundary, top-level controller
    - ✅ Let it crash - exception propaguje do top-level handler
    - ✅ Pro Tools (Koog): Tools throwují exception, framework je handluje jako tool error
    - ✅ Validace na vstupu (fail-fast), ne defensive programming všude
- Kotlin‑first, idiomaticky: coroutines + Flow jako základ asynchronní práce. Vyhýbat se „Javě v Kotlinu". Preferuj
  streamování (`Flow`, `Sequence`) před budováním velkých `List`.
- IF‑LESS pattern: kde hrozí rozšiřování, nahrazuj `if/when` polymorfismem, sealed hierarchiemi, strategy mapami nebo
  routovací tabulkou. `if/when` je OK pro triviální případy.
- SOLID: malé, jednoúčelové funkce; vysoká soudržnost, nízké vazby; eliminace duplicit (preferuj extension functions
  před „utils" třídami).
- Jazyk v kódu, komentářích a logách: výhradně angličtina. Inline komentáře „co" nepíšeme; „proč" patří do KDoc.
- **NO DECORATIVE COMMENTS:** NIKDY nepoužívej dekorativní komentáře jako ASCII art separátory nebo section headers:
    - ❌ `// ════════════`
    - ❌ `// ───────────`
    - ❌ `// ==================== RESULT POJO ====================`
    - ❌ `// ==================== READ OPERATIONS ====================`
    - ❌ `// === Section Header ===`
    - ❌ `/* ********** */`
    - ✅ Píš samopopisující kód - structure speaks for itself
    - ✅ POUZE KDoc pro public API
    - ✅ Žádné inline komentáře kromě kritických "proč" poznámek
    - ✅ Pokud potřebuješ oddělení, použij prázdný řádek nebo package structure
- **NO METADATA MAPS - ANTIPATTERN:** NIKDY nepoužívej `metadata: MutableMap<String, Any>` nebo podobné generické mapy
  pro ukládání strukturovaných dat. VŽDY vytvoř proper data class s typovanými fieldy. Metadata mapy jsou antipattern -
  neumožňují type safety, refactoring, IDE podporu ani čitelnost kódu.

### Development mód – pravidla pro rychlý vývoj (platí do odvolání)

- **Žádné deprecations, žádné kompatibilitní vrstvy:** Deprecated kód je zakázán. Veškeré změny se ROVNOU refactorují.
  Breaking changes jsou povoleny. Cílem je udržet kód čistý a přímočarý bez legacy vrstev.
- **Policy: No Deprecated Code**
    - NIKDY nevytvářej `@Deprecated` anotace
    - NIKDY nenechávej starý kód „pro kompatibilitu"
    - Pokud je třeba změna, refactoruj CELÝ kód včetně všech míst použití
    - Deprecated funkcionality se OKAMŽITĚ mažou a nahrazují novým řešením
- UI neschovává žádné hodnoty: hesla, tokeny, klíče a jiné „secrets" jsou v UI vždy viditelné (žádné maskování). Tato
  aplikace není veřejná.
- DocumentDB (Mongo): nic nešifrujeme; vše ukládáme v „plain text". Toto je vědomé rozhodnutí pro privátní dev instanci.

### Shared UI pro Desktop i Mobile (iPhone)

- Všechny obrazovky mají společný zdroj pravdy v `shared/ui-common` (Compose Multiplatform). Desktop i mobil (zejména
  iPhone) používají stejné composables.
- Layouty musí být adaptivní: žádné fixní šířky, rozumné zalamování, skrolování, touch‑targety na mobilu ≥ 44dp.

## 2) Architektura a moduly

- Server (Spring Boot WebFlux) je jediný zdroj pravdy; orchestruje procesy, RAG, plánování, integrace a modely.
- Compute‑only služby:
    - `backend:service‑joern` - Code analysis
    - `backend:service‑tika` - Document processing
    - `backend:service‑whisper` - Audio transcription
    - `backend:service‑atlassian` - Atlassian Cloud API (Jira, Confluence, Bitbucket, etc.) - samostatný build cycle,
      future Swagger API integration
- Shared KMP: `shared:common‑dto` (DTO), `shared:common‑api` (HttpExchange kontrakty), `shared:domain` (čisté doménové
  typy), `shared:ui‑common` (Compose UI obrazovky).
- Aplikace: `apps:desktop` (primární), `apps:mobile` (iOS/Android, port z desktopu).

## 3) Komunikace a kontrakty

- Mezi klienty a serverem výhradně `@HttpExchange` rozhraní v `shared:common‑api` jako `I***Service`. Serverové
  controllery je implementují.
- `backend:common-services` obsahuje jen interní REST kontrakty pro service‑*** → server; není dostupné v UI/common
  modulech.

### 3.x) LLM – String hranice a templating (SSOT pravidla)

- Hranice k LLM (Koog 0.5.x) je VŽDY String → String.
    - Uzly `nodeLLMRequest` / `nodeLLMSendToolResult` i `PromptExecutor` pracují pouze s textem.
    - Plně typové I/O (object‑in/object‑out) u LLM uzlu není podporované – typovou smlouvu držíme na okrajích.

- Templating pro vstupní/výstupní payloady je jednotně přes službu `PromptBuilderService`.
    - ZÁKAZ ad‑hoc `StringBuilder`/`buildString` pro JSON payloady k LLM.
    - Používej:
        - `render(template: String, mapping: Map<String, String>)` – fail‑fast na chybějící placeholdery.
        - `renderFromObject(template: String, data: Any, extra: Map<String, String> = emptyMap())` – data objekt → map (
          null → "null"), `extra` přepíše kolize.
    - Placeholdery mají syntaxi `{name}`. Šablona musí sama řešit uvozovky (když očekává JSON string, napiš je v
      šabloně).
    - Fail‑fast: pokud je v šabloně placeholder, pro který není hodnota, vyhazujeme chybu už při renderu.

- Doporučený postup pro JSON payloady k LLM:
    1) Připrav si šablonu jako statický String:
       ```json
       {
         "id": "{id}",
         "type": "{type}",
         "clientId": "{clientId}",
         "projectId": {projectIdRaw},
         "correlationId": "{correlationId}",
         "state": "{state}",
         "createdAt": "{createdAt}",
         "content": {contentRaw}
       }
       ```
    2) Předem si připrav hodnoty, které mají jít do JSON jako literály (bez dodatečných uvozovek), např. `null` nebo už
       serializovaný String → `contentRaw = Json.encodeToString(content)`.
    3) Zavolej `PromptBuilderService.render(template, mapping)` nebo `renderFromObject(...)`.

- Příklad (implementováno): `KoogQualifierAgent.buildUserPayload()` používá šablonu a `PromptBuilderService.render(...)`
  místo `buildString { ... }`.

- Výstup LLM: stále String. Vynucujeme "JSON‑only" v system promptu a parsujeme přes `kotlinx.serialization` s
  tolerantním fallbackem.

- Důvod:
    - Koog nástroje a LLM uzly jsou v 0.5.x textové; vlastní typy v `@Tool` parametrech jsou omezené (např. `Set` selže
      v reflektoru).
    - Templating dává determinismus, konzistenci a fail‑fast chování bez skrytých fallbacků.

### 3.1) HTTP klienti – pravidla pro volání externích služeb

**SSOT: Používej VŽDY Ktor HttpClient, WebClient POUZE pro `@HttpExchange` služby**

#### A) Ktor HttpClient – dvě kategorie:

**1. KtorClientFactory (bez rate limitu) – interní služby + LLM API s vlastním rate limitem**

- **Použití:**
    - **Interní služby na lokální síti:** Ollama, LM Studio, searxng
    - **Externí LLM API s vlastním rate limitem:** OpenAI, Anthropic, Google (mají built-in rate limiting na jejich
      straně)
- **Factory:** `KtorClientFactory` v `com.jervis.configuration`
- **Konfigurace:**
    - `ktor.*` v `application.yml` (connectionDocument-pool, timeouts, api-versions, logging)
    - `retry.ktor.*` – exponenciální backoff pro transientní chyby
- **Charakteristika:**
    - **ŽÁDNÝ client-side rate limiting** (interní služby na 192.168.x.x, 10.x.x.x, localhost NEBO API s vlastním rate
      limitem)
    - Retry pouze pro connectionDocument errors (ne timeout)
    - Coroutines-first, connectionDocument pooling, JSON serialization (kotlinx.serialization)
    - Per-provider API verze a auth headers (např. `anthropic-version`, `x-api-key`, `Authorization: Bearer`)
- **Příklad použití:**
  ```kotlin
  @Service
  class OllamaClient(private val ktorClientFactory: KtorClientFactory) {
      private val httpClient by lazy { ktorClientFactory.getHttpClient("ollama.primary") }
      suspend fun call(...) = httpClient.post("/api/generate") { setBody(...) }.body<Response>()
  }
  ```

**2. Ktor HttpClient (s rate limitem) – integrace třetích stran v microservices**

- **Použití:**
    - Microservice integrace třetích stran: `backend:service-atlassian` (Jira, Confluence, Bitbucket)
    - Link scraper v server modulu (user-specified URLs)
- **Implementace:** Ktor HttpClient s `DomainRateLimiter` z `common-services`
- **Rate limiting:**
    - Sdílený `DomainRateLimiter` z `com.jervis.common.ratelimit` (common-services modul)
    - Per-domain rate limiting (automatická extrakce domény z URL)
    - Konfigurované limity per služba (např. Atlassian: 10 req/sec, 100 req/min)
    - Interní IP adresy (192.168.x.x, 10.x.x.x, localhost) jsou exempt z rate limitu
- **Charakteristika:**
    - Coroutines-first (suspend functions)
    - Rate limiter v `common-services` je sdílený mezi všemi moduly
    - Automatické čekání při překročení limitu (sliding window)
    - Content negotiation, request logging
- **Kdy použít:** Microservices které volají externí API s dynamickými doménami (user-specified URLs)
- **Příklad použití:**
  ```kotlin
  // V service-atlassian modulu
  @Service
  class AtlassianApiClient {
      private val rateLimiter = DomainRateLimiter(RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100))

      private suspend fun <T> rateLimitedRequest(url: String, block: suspend (HttpClient, String) -> T): T {
          if (!UrlUtils.isInternalUrl(url)) {
              val domain = UrlUtils.extractDomain(url)
              rateLimiter.acquire(domain)
          }
          val client = httpClient(null)
          return block(client, url)
      }

      suspend fun searchJiraIssues(...) = rateLimitedRequest(url) { client, _ ->
          client.request(url) { ... }.body<JiraSearchResponse>()
      }
  }
  ```

#### B) WebClient (Spring WebFlux) – POUZE pro `@HttpExchange`

**Použití:** Externí compute služby s `@HttpExchange` rozhraními: joern, tika, whisper, atlassian

- **Factory:** `WebClientFactory` v `com.jervis.configuration`
- **Konfigurace:**
    - `connections.*` v `application.yml` (connection-pool, timeouts, buffers)
    - `retry.webclient.*` – exponenciální backoff
- **Charakteristika:**
    - Spring WebFlux reactive approach (Reactor)
    - POUZE pro služby používající `@HttpExchange` deklarativní HTTP kontrakty
    - Custom buffer sizes (např. 64 MB pro Tiku)
- **Důvod existence:** Kompatibilita s `@HttpExchange` pattern (deklarativní REST klienty)
- **Příklad použití:**
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

#### C) Pravidla pro výběr HTTP klienta:

1. **Interní služby na lokální síti** (Ollama, LM Studio, searxng) → `KtorClientFactory` (bez rate limitu)
2. **Externí LLM API s vlastním rate limitem** (OpenAI, Anthropic, Google) → `KtorClientFactory` (bez rate limitu)
3. **Microservice integrace třetích stran** (service-atlassian) → Ktor HttpClient s `DomainRateLimiter` z
   `common-services`
4. **Externí compute služby s `@HttpExchange`** (joern, tika, whisper, atlassian) → `WebClientFactory`
5. **Link scraper v server modulu** → Ktor HttpClient s `DomainRateLimiter` z `common-services`
6. **Nové služby:** VŽDY Ktor (buď KtorClientFactory nebo s DomainRateLimiter podle potřeby), NIKDY WebClient (kromě
   `@HttpExchange`)
7. **Koog agenti:** Preferují Ktor jako connection client (coroutines-first)

**Rozhodovací strom:**

- Je to `@HttpExchange` služba pro komunikaci server → microservice? → WebClientFactory
- Je to interní služba (192.168.x.x, localhost) nebo LLM API s vlastním rate limitem? → KtorClientFactory
- Je to microservice integrace s dynamickou doménou (user-specified URL)? → Ktor HttpClient s DomainRateLimiter (v
  microservice)
- Je to link scraper v server modulu? → Ktor HttpClient s DomainRateLimiter
- Nová služba bez `@HttpExchange`? → KtorClientFactory (default)

#### D) Migrace WebClientFactory → KtorClientFactory (provedeno 2025-12-02):

**1. Migrace LLM klientů:**

- ✅ `OllamaClient`, `AnthropicClient`, `GoogleLlmClient`, `OpenAiClient`, `LmStudioClient` → `KtorClientFactory`
- ✅ `OllamaQualifierClient` → používá Koog (nepřímá migrace přes KoogQualifierAgent)

**2. Migrace Embedding klientů:**

- ✅ `OllamaEmbeddingClient` (qualifier) → `KtorClientFactory`
- ✅ `OllamaEmbeddingCpuClient` (embedding) → `KtorClientFactory`
- ✅ `LmStudioEmbeddingClient` → `KtorClientFactory`
- ✅ `OpenAiEmbeddingClient` → `KtorClientFactory`

**3. Migrace Model Preloaders & Refreshers:**

- ✅ `LlmModelPreloader` (ollama.primary, ollama.qualifier) → `KtorClientFactory`
- ✅ `LlmModelRefresher` (keep-alive) → `KtorClientFactory`
- ✅ `EmbeddingModelPreloader` (ollama.embedding) → `KtorClientFactory`
- ✅ `EmbeddingModelRefresher` (keep-alive) → `KtorClientFactory`

**4. Migrace Web search & Tools:**

- ✅ `ContentSearchWebTool` (searxng) → `KtorClientFactory`
- ✅ `DocumentFromWebTool` (searxng) → `KtorClientFactory`
- ✅ `KoogQualifierTools` (searxng) → `KtorClientFactory`
- ✅ `KoogQualifierGraphRagTools` (searxng) → `KtorClientFactory`
- ✅ `KoogQualifierAgent` → `KtorClientFactory`

**5. Co ZŮSTALO na WebClient (správně):**

- ✅ `InternalHttpClientsConfig` pro `ITikaClient`, `IJoernClient`, `IWhisperClient` (HttpExchange services)

**6. Co bylo ODSTRANĚNO:**

- ❌ `RateLimitedHttpClientFactory` (deprecated, nepoužíváno)

**Technické detaily migrace:**

- **Import changes:**
    - `com.jervis.configuration.WebClientFactory` → `com.jervis.configuration.KtorClientFactory`
    - `org.springframework.web.reactive.function.client.WebClient` → `io.ktor.client.HttpClient`
    - `org.springframework.web.reactive.function.client.awaitBody` → `io.ktor.client.call.body`
- **Constructor injection:**
    - `webClientFactory: WebClientFactory` → `ktorClientFactory: KtorClientFactory`
- **Client initialization:**
    - `webClientFactory.getWebClient("endpoint")` → `ktorClientFactory.getHttpClient("endpoint")`
- **HTTP API calls:**
    - WebClient: `.post().uri("/path").bodyValue(body).retrieve().awaitBody<Type>()`
    - Ktor: `.post("/path") { setBody(body) }.body<Type>()`
- **Streaming:**
    - WebClient: `.bodyToFlux(String::class.java).asFlow()`
    - Ktor: `response.bodyAsChannel()` + `readUTF8Line()`
- **NDJSON (Newline Delimited JSON) streaming:**
    - Ollama `/api/pull` endpoint vrací streaming NDJSON (Content-Type: `application/x-ndjson`)
    - **NELZE** použít `.body<Type>()` - způsobí `NoTransformationFoundException`
    - **Správný způsob:** Čti line-by-line pomocí `bodyAsChannel()` + `readUTF8Line()`
    - Příklad:
      ```kotlin
      val response: HttpResponse = httpClient.post("/api/pull") { setBody(body) }
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
    - Použito v: `LlmModelPreloader.readPullResponse()`, `EmbeddingModelPreloader.readPullResponse()`
- **Error handling:**
    - `WebClientResponseException` → `ResponseException` (io.ktor.client.plugins)
    - `WebClientRequestException` → `ClientRequestException` (io.ktor.client.plugins)

**Konečný stav:**

- **KtorClientFactory:** 18 služeb (LLM clients, embedding clients, preloaders, refreshers, tools, agents)
- **WebClientFactory:** 3 služby (tika, joern, whisper - pouze HttpExchange)
- **Global HttpClient bean:** 1 použití (AtlassianApiClient s rate limitem)

**Configuration properties změny:**

- `connections.api-versions` → ODSTRANĚNO (přesunuto do `ktor.api-versions`)
- `ktor.*` konfigurace přidána pro KtorClientFactory
- `retry.ktor.*` konfigurace přidána pro Ktor retry policy

**Serialization best practice (2025-12-02):**

- **Problem:** Ollama API requesty obsahovaly heterogenní mapy (např. `mapOf("name" to "model", "stream" to false)`)
- **Error:** `IllegalStateException: Serializing collections of different element types is not yet supported`
- **Solution:** Definovat @Serializable data classes pro každý request type
    - Příklad: `@Serializable data class OllamaPullRequest(val name: String, val stream: Boolean = false)`
    - Response types také jako @Serializable data classes s nullable fields
- **Benefit:**
    - Dodržuje guidelines preference pro `kotlinx.serialization`
    - Type-safe API calls
    - Jasně definovaná struktura requestů a responses
    - IDE autocomplete a compile-time checks
- **Implementováno v:** `LlmModelPreloader`, `EmbeddingModelPreloader`, `LlmModelRefresher`, `EmbeddingModelRefresher`
- **Závěr:** VŽDY používat @Serializable data classes pro HTTP requesty, NIKDY `Map<String, Any>` s mixed types

## 3.2) Koog – Qualifier (první agent v pipeline)

- Qualifier běží PŘED orchestrátorem v CPU smyčce: `BackgroundEngine.runQualificationLoop()`.
- Vstupy:
    - systemPrompt = „goal“ ze `prompts.yaml` dle `PendingTaskTypeEnum.promptType` (EMAIL_QUALIFIER, LINK_QUALIFIER,
      QUALIFIER)
    - userPrompt = `PendingTaskDocument.content` (případně proměnné jako `currentDate`, `clientId`, `projectId`,
      `correlationId`)
- Směrování modelu: provider `OLLAMA_QUALIFIER` (CPU endpoint) definovaný v `models-config.yaml` →
  `OllamaQualifierClient` → `KoogQualifierAgent`. ŽÁDNÉ fallbacky mimo Koog.
- Endpointy: z `EndpointProperties.endpoints.ollama.qualifier.baseUrl`. Fail‑fast na prázdnou hodnotu.
- JSON‑only výstup: parser `JsonParser` odstraní případné <think> a vyparsuje pouze finální JSON do DTO (
  `GenericQualifierOut`, `LinkQualifierOut`).
- Povolené nástroje v kvalifikátoru (volitelné, pouze dle potřeby):
    - `ragSearch`, `ragIndex` (KnowledgeService)
    - `graphUpsertNode`, `graphLink` (GraphDBService)
    - `webSearch` (Searxng)
      Vždy předávej `CLIENT_ID` a pokud existuje `PROJECT_ID` přesně tak, jak jsou v proměnných promptu.

### 3.2.1) Backpressure & konkurence kvalifikace

- Zpracování tisíců tasků běží přes Kotlin Flow:
    - `findTasksForQualification()` → `.buffer(N)` → `.flatMapMerge(concurrency=N)` → per‑task `processOne()`
    - N = `ProviderCapabilitiesService.providers[OLLAMA_QUALIFIER].maxConcurrentRequests`
- Dvouúrovňové limity:
    - per‑provider: `ProviderConcurrencyManager`
    - per‑model (volitelné, pokud `concurrency` v `models-config.yaml`): `ModelConcurrencyManager`
- Žádné `toList()` ani Java‑style batching. Každý task má vlastní chybovou izolaci a logy.
- Časové limity/reties: spoléháme na Ktor timeouts a retry politiku; timeouty se NEretryují.

### 3.2.2) Prompts – kvalifikátor

Prompty v `prompts.yaml` sekcích `EMAIL_QUALIFIER`, `LINK_QUALIFIER`, `QUALIFIER` obsahují:

- deterministická pravidla pro `discard`/`delegate`
- „TOOLS YOU CAN USE“ s přesnými názvy a parametry
- „IDENTITY“ blok s `CLIENT_ID`, `PROJECT_ID`, `CORRELATION_ID`
- jasný JSON kontrakt (Return JSON only: {...})

## 3.3) Koog – Main Agent (KoogWorkflowAgent)

**KoogWorkflowAgent** je hlavní produkční AI agent JERVIS postavený na Koog framework, určený pro komplexní workflow a
dlouhodobou práci.

**Koog Framework dokumentace:** Kompletní reference knihoven Koog 0.5.3 je v `docs/koog-libraries.md`. Obsahuje popisy
core libraries, built-in tools, prompt executors, a usage patterns.

### 3.3.1) Architektura

- **Lokace:** `com.jervis.koog.KoogWorkflowAgent`
- **Framework:** Koog AIAgent with custom strategy graph
- **Model routing:** Ollama (přes `simpleOllamaAIExecutor()`)
- **Max iterations:** 8 (configurable via `AIAgentConfig`)
- **Tools:** Centrální `ToolRegistry` s všemi dostupnými Koog Tools

### 3.3.2) Strategy Graph

Agent používá multi-node strategy:

1. **nodePreEnrich** – před konverzací načte RAG + Graph context
2. **nodeSendInput** – pošle enriched input do LLM
3. **nodeExecuteTool** – vykoná tool call
4. **nodeSendToolResult** – pošle výsledek tool callu zpět do LLM
5. **nodeFinish** – ukončí s assistant response v target language

### 3.3.3) Dostupné Tools – Koog Built-in + JERVIS Custom (2025-12-02)

**Architektura:** Používáme Koog built-in tools kde existují + JERVIS custom tools pro specifickou funkcionalitu.

**1. Koog Built-in File System Tools** (`ai.koog.agents.ext.tool.file`):

- ✅ `ListDirectoryTool(JVMFileSystemProvider.ReadOnly)` – list directory contents
- ✅ `ReadFileTool(JVMFileSystemProvider.ReadOnly)` – read file content
- ✅ `EditFileTool(JVMFileSystemProvider.ReadWrite)` – edit files with patch/replace
- ✅ `WriteFileTool(JVMFileSystemProvider.ReadWrite)` – write/create new files
- **Provider:** `JVMFileSystemProvider.ReadOnly` or `.ReadWrite`
- **Source:** `agents-ext-jvm-0.5.3.jar`

**2. Koog Built-in Shell Tools** (`ai.koog.agents.ext.tool.shell`):

- ✅ `ExecuteShellCommandTool(executor, confirmationHandler)` – execute shell commands
- **Executor:** `JvmShellCommandExecutor()`
- **Confirmation:** `PrintShellCommandConfirmationHandler()` or custom
- **Source:** `agents-ext-jvm-0.5.3.jar`

**3. JERVIS MemoryTools** (`com.jervis.koog.tools.MemoryTools`):

- ✅ `memoryWrite(actionType, content, reason, context, result, entityType, entityKey, tags)` – permanent memory with
  audit trail
- ✅ `memoryRead(limit, projectId, correlationId)` – read last N memories
- ✅ `memorySearch(searchMode, query, entityType, days, limit)` – multi-dimensional search
- **Persistence:** MongoDB `agent_memory` collection, NEVER deleted
- **Audit trail:** WHY (reason), WHAT (content), WHEN (timestamp), RESULT, CONTEXT
- **Parameters:** `taskContext: Plan`, `memoryService: AgentMemoryService`

**4. JERVIS RagTools** (`com.jervis.koog.tools.RagTools`) - Atomic Chunking (2025-12-07):

- ✅ `storeChunk(documentId, content, nodeKey, entityTypes, graphRefs, knowledgeType)` – atomic chunk storage
    - Agent extracts entity context, service embeds and stores
    - Returns chunkId for graph linking
    - Enables Hybrid Search (BM25 + Vector)
- ✅ `searchHybrid(query, alpha, maxResults, knowledgeTypes)` – hybrid search (BM25 + Vector)
    - `alpha`: 0.0=BM25 only, 0.5=hybrid, 1.0=vector only
    - Use for duplicate detection before node creation
- **Storage:** Weaviate per-client collections (`{clientSlug}_rag_text`, `{clientSlug}_rag_code`)
- **Architecture:** Agent-driven chunking, service only embeds
- **Parameters:** `task: PendingTaskDocument`, `knowledgeService: KnowledgeService`

**5. JERVIS GraphTools** (`com.jervis.koog.tools.GraphTools`):

- ✅ `getRelatedNodes(nodeKey, direction, edgeTypes, limit)` – traverse graph relationships
- ✅ `getNode(nodeKey)` – get specific node
- ✅ `upsertNode(nodeKey, nodeType, properties)` – create/update nodes
- ✅ `createLink(fromKey, toKey, edgeType, properties)` – create relationships
- **Storage:** ArangoDB per-client Knowledge Graph
- **Parameters:** `taskContext: Plan`, `graphService: GraphDBService`

**6. JERVIS TaskTools** (`com.jervis.koog.tools.TaskTools`):

- ✅ `scheduleTask(content, scheduledDateTime, taskName, cronExpression, action, taskId)` – system task scheduling
- ✅ `createUserTask(title, description, priority, dueDate, sourceType, sourceUri, metadata)` – user-facing tasks
- ✅ `userDialog(question, proposedAnswer)` – interactive dialog
- **Parameters:** `taskContext: Plan`, `mcpRegistry: McpToolRegistry`

### 3.3.4) Long-Term Memory Provider (implementováno 2025-12-02)

**Architektura dlouhodobé paměti agenta:**

Agent podporuje dlouhodobou persistentní paměť s těmito vlastnostmi:

- **Nic se nemaže** – všechny záznamy jsou permanent (no deletion policy)
- **Audit trail** – každá změna obsahuje:
    - důvod úpravy (why) - `reason` field
    - kontext (client, project, correlation) - `clientId`, `projectId`, `correlationId`
    - vstupní zadání - `context` field
    - výsledek operace - `result` field
    - souvislosti s jinými úkoly/commity/branches - `relatedTo[]`, `entityType`, `entityKey`
- **Vyhledávání** – podle tématu, data, projektu, entity, „why" kontextu
- **Storage:** MongoDB (`agent_memory` kolekce)

**Datový model (`AgentMemoryDocument`):**

```kotlin
@Document(collection = "agent_memory")
data class AgentMemoryDocument(
    @Id val id: ObjectId,

    // Isolation & Context
    @Indexed val clientId: String,              // per-client izolace
    @Indexed val projectId: String? = null,     // per-project izolace (optional)
    @Indexed val correlationId: String? = null, // workflow/session tracking
    @Indexed val timestamp: Instant,            // kdy vznikl záznam

    // Core Memory Content
    @Indexed val actionType: String,            // FILE_EDIT, TASK_CREATE, DECISION, ANALYSIS, SHELL_EXEC, ...
    val content: String,                        // CO agent udělal nebo se naučil (main content)
    val reason: String,                         // PROČ to udělal (audit trail - "why")
    val context: String? = null,                // V JAKÉM kontextu (user request, previous steps)
    val result: String? = null,                 // Výsledek operace (success/error details)

    // Entity Tracking
    val entityType: String? = null,             // FILE, CLASS, METHOD, TASK, COMMIT, BRANCH, ...
    @Indexed val entityKey: String? = null,     // file path, task ID, commit hash, ...
    val relatedTo: List<String> = emptyList(),  // odkazy na jiné memory ID nebo entity

    // Search & Metadata
    @Indexed val tags: List<String>,            // tagy pro vyhledávání
    val embedding: List<Double>? = null,        // optional: pro sémantické vyhledávání
    val metadata: Map<String, String>,          // volná metadata
)
```

**Repository methods (`AgentMemoryRepository`):**

- `findByClientIdOrderByTimestampDesc` – základní read (poslední N záznámů)
- `findByClientIdAndProjectIdOrderByTimestampDesc` – per-project záznamy
- `findByClientIdAndCorrelationIdOrderByTimestamp` – celá session/workflow
- `findByClientIdAndActionTypeOrderByTimestampDesc` – podle typu akce
- `findByClientIdAndEntityTypeAndEntityKeyOrderByTimestampDesc` – podle entity
- `findByClientIdAndTagsInOrderByTimestampDesc` – podle tagů
- `searchByText` – full-text search (vyžaduje MongoDB text index)
- `findByClientIdAndTimestampBetweenOrderByTimestampDesc` – časový rozsah

**Service API (`AgentMemoryService`):**

- `write(memory: AgentMemoryDocument)` – uložení nové paměti
- `read(clientId, limit)` – poslední N záznámů
- `readByProject(clientId, projectId, limit)` – per-project
- `readByCorrelation(clientId, correlationId)` – celá session
- `searchByActionType(clientId, actionType, limit)` – filtr podle typu
- `searchByEntity(clientId, entityType, entityKey, limit)` – entity tracking
- `searchByTags(clientId, tags, limit)` – podle tagů
- `searchByText(clientId, query, limit)` – full-text search
- `searchByTimeRange(clientId, from, to, limit)` – časové okno
- `searchLastDays(clientId, days, limit)` – posledních X dní
- `getStats(clientId)` – statistiky (total, last7Days, last30Days, byActionType, byEntityType)

**Příklad použití z agenta:**

```kotlin
// Uložení memory po úspěšné editaci souboru
val memory = AgentMemoryDocument(
    clientId = task.clientId.toString(),
    projectId = task.projectId?.toString(),
    correlationId = task.correlationId,
    actionType = "FILE_EDIT",
    content = "Refactored UserService.kt - extracted validation logic into ValidatorService",
    reason = "User requested code organization improvement. UserService had 450 lines with mixed concerns.",
    context = "User input: 'Please refactor UserService - it's too big'",
    result = "SUCCESS: Created ValidatorService.kt. All 127 tests pass.",
    entityType = "FILE",
    entityKey = "src/main/kotlin/com/example/service/UserService.kt",
    tags = listOf("refactoring", "validation", "user-service"),
)
memoryService.write(memory)
```

**Integrace s KoogBridgeTools:**

- Tools `memoryWrite`, `memoryRead`, `memorySearch` jsou exponovány do Koog ToolRegistry
- Automatické nastavení `clientId`, `projectId`, `correlationId` z `taskContext` contextu
- Failure semantics: všechny memory operace throwují `IllegalStateException` při selhání (Koog treats as tool error)

**Indexy (doporučené):**

```javascript
// MongoDB shell commands
db.agent_memory.createIndex({clientId: 1, timestamp: -1})
db.agent_memory.createIndex({clientId: 1, projectId: 1, timestamp: -1})
db.agent_memory.createIndex({clientId: 1, correlationId: 1, timestamp: 1})
db.agent_memory.createIndex({clientId: 1, actionType: 1, timestamp: -1})
db.agent_memory.createIndex({clientId: 1, entityType: 1, entityKey: 1, timestamp: -1})
db.agent_memory.createIndex({clientId: 1, tags: 1, timestamp: -1})
db.agent_memory.createIndex({clientId: 1, timestamp: 1})

// Full-text search index (pro searchByText)
db.agent_memory.createIndex({content: "text", reason: "text", context: "text"})
```

**Reference dokumentace:**

- Example JSON: `docs/agent-memory-example.json` – ukázka kompletního memory záznamu s audit trail
- Implementation: `backend/server/src/main/kotlin/com/jervis/service/agent/AgentMemoryService.kt`
- Repository: `backend/server/src/main/kotlin/com/jervis/repository/AgentMemoryRepository.kt`
- Entity: `backend/server/src/main/kotlin/com/jervis/domain/agent/AgentMemoryDocument.kt`
- Tools: `backend/server/src/main/kotlin/com/jervis/koog/tools/MemoryTools.kt`

### 3.3.5) Tool Registry Pattern – Direct Koog Integration

Centrální `ToolRegistry` v `KoogWorkflowAgent.create()` s přímými Koog ToolSet implementacemi:

```kotlin
val toolRegistry = ToolRegistry {
    // File System Tools
    tools(
        FileSystemTools(
            allowedPaths = listOf(System.getProperty("user.home")),
        )
    )

    // Shell Tools
    tools(
        ShellTools(
            workingDirectory = System.getProperty("user.home"),
            allowedPaths = listOf(System.getProperty("user.home")),
        )
    )

    // Long-Term Memory Tools
    tools(
        MemoryTools(
            taskContext = taskContext,
            memoryService = memoryService,
        )
    )

    // RAG Tools
    tools(
        RagTools(
            taskContext = taskContext,
            mcpRegistry = mcpRegistry,
        )
    )

    // Task Management Tools
    tools(
        TaskTools(
            taskContext = taskContext,
            mcpRegistry = mcpRegistry,
        )
    )

    // System Utilities
    tools(SystemTools())
}
```

**Implementace (2025-12-02):**

- **6 samostatných ToolSet tříd** – každý s jasně definovanou odpovědností
- **Žádný bridge layer** – přímá integrace s Koog framework
- **Celkem 17 tools:**
    - FileSystemTools: 3 (listDirectory, readFile, editFile)
    - ShellTools: 1 (executeShell)
    - MemoryTools: 3 (memoryWrite, memoryRead, memorySearch)
    - RagTools: 2 (ragSearch, ragIndex)
    - TaskTools: 3 (scheduleTask, createUserTask, userDialog)
    - SystemTools: 5 (jsonParse, jsonStringify, getCurrentDateTime, calculateDateTime)

**Lokace souborů:**

- `backend/server/src/main/kotlin/com/jervis/koog/tools/FileSystemTools.kt`
- `backend/server/src/main/kotlin/com/jervis/koog/tools/ShellTools.kt`
- `backend/server/src/main/kotlin/com/jervis/koog/tools/MemoryTools.kt`
- `backend/server/src/main/kotlin/com/jervis/koog/tools/RagTools.kt`
- `backend/server/src/main/kotlin/com/jervis/koog/tools/TaskTools.kt`
- `backend/server/src/main/kotlin/com/jervis/koog/tools/SystemTools.kt`

**Pravidla:**

- ToolRegistry se vytváří JEDNOU v `create()` metodě
- NESMÍ se vytvářet ad-hoc v různých částech aplikace
- Všechny tools jsou připraveny na multiklientní režim (client/project isolation)
- Každý ToolSet je `@LLMDescription` anotovaný pro LLM consumption
- Failure semantics: všechny tools throwují `IllegalStateException` při selhání (Koog treats as tool error)
- Tool results jsou vždy `String` (Koog očekává textovou odpověď)
- **ŽÁDNÝ KoogBridgeTools** – pouze přímé Koog ToolSet implementace

### 3.3.6) Pre-Enrichment Context

Před každým během agent automaticky načte:

- **RAG context:** Top 15 relevantních chunks (min score 0.55)
- **Graph context:** Related nodes (limit 20) z GraphDB
- **Target language:** Z `taskContext.originalLanguage`

Tento context se předá do LLM jako prefix před user input.

### 3.3.7) Graph-Based Routing Architecture (implementováno 2025-12-03)

**Nová architektura pro efektivní zpracování dat s CPU/GPU separací a inteligentním routingem.**

#### Přehled architektury

**Problém:** Původní architektura auto-indexovala vše přímo do RAG bez strukturování, způsobovala context overflow u
velkých dokumentů a neefektivně využívala drahé GPU modely.

**Řešení:** Dvoustupňová architektura s CPU-based kvalifikací (structuring) a GPU-based exekucí (analysis/actions).

```
┌─────────────────┐
│ CentralPoller   │ stáhne data z API → MongoDB (state=NEW)
│ (interval-based)│
└─────────────────┘
        ↓
┌─────────────────┐
│ContinuousIndexer│ čte NEW dokumenty (non-stop loop) →
│ (non-stop loop) │ vytvoří task → state=INDEXED
└─────────────────┘ (INDEXED = "obsah předán do Jervis", ne "už v RAG"!)
        ↓
┌─────────────────────────────────────────────────┐
│ BackgroundEngine - Qualification Loop (CPU)     │
│ • Runs continuously (30s interval)              │
│ • Processes tasks               │
└─────────────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────────────┐
│ KoogQualifierAgent (CPU - OLLAMA_QUALIFIER)     │
│ • SequentialIndexingTool (chunking: 4000/200)  │
│ • GraphRagLinkerTool (Graph ↔ RAG links)       │
│ • TaskRoutingTool (DONE vs READY_FOR_GPU)      │
│ • TaskMemory creation (context summary)        │
└─────────────────────────────────────────────────┘
        ↓
    ┌───┴───┐
    ↓       ↓
┌────────┐ ┌──────────────────────────────────────┐
│  DONE  │ │ READY_FOR_GPU (complex analysis)     │
└────────┘ └──────────────────────────────────────┘
                    ↓
        ┌─────────────────────────────────────────┐
        │ BackgroundEngine - Execution Loop (GPU) │
        │ • Runs ONLY when idle (no user requests)│
        │ • Preemption: interrupted by user       │
        └─────────────────────────────────────────┘
                    ↓
        ┌─────────────────────────────────────────┐
        │ KoogWorkflowAgent (GPU - OLLAMA)        │
        │ • TaskMemoryTool (loads context)        │
        │ • Focus on analysis/actions             │
        │ • No redundant structuring work         │
        └─────────────────────────────────────────┘
```

#### Komponenty implementace

**1. Enums (PendingTaskTypeEnum, PendingTaskStateEnum):**

- ✅ `QUALIFICATION_IN_PROGRESS` - Qualifier zpracovává task
- ✅ `READY_FOR_GPU` - kvalifikace hotova, čeká na GPU exekuci

**2. ContinuousIndexers (ETL: MongoDB → PendingTask):**

- Běží non-stop, polling na NEW dokumenty v MongoDB (30s delay když prázdné)
- ŽÁDNÉ API volání - jen čtou z MongoDB a vytvářejí PendingTask
- Stavy: NEW (z API) → INDEXING (zpracovává se) → INDEXED (task vytvořen)
- INDEXED = "obsah předán do Jervis jako pending task", NE "již v RAG/Graph"!
- ✅ `EmailContinuousIndexer` - vytváří DATA_PROCESSING task z emailů + zpracovává linky
- ✅ `JiraContinuousIndexer` - vytváří DATA_PROCESSING task z Jira issues
- ✅ `ConfluenceContinuousIndexer` - vytváří DATA_PROCESSING task z Confluence pages
- ✅ `GitContinuousIndexer` - vytváří DATA_PROCESSING task z Git commitů

**2.1 Link Handling Flow - SAMOSTATNÉ PENDING TASKY:**

**DŮLEŽITÉ: Linky se NIKDY nestahují v continuous indexeru!**

Architektura: **Document → Link → Link → Link** (každý jako samostatný pending task)

**1. EmailContinuousIndexer (stejně Jira/Confluence/Git):**

- Zpracuje email
- Extrahuje linky z body (pomocí LinkExtractor)
- Vytvoří **DATA_PROCESSING task PRO EMAIL** (bez stahování linků!)
- V email tasku uvede: "Tento email obsahuje 3 linky: url1, url2, url3"
- Pro každý nalezený link vytvoří **SAMOSTATNÝ LINK_PROCESSING task**:
    * Link URL
    * Source context (emailId, subject, sender pro Graph edge Email→Link)
    * Kontext okolo linku (text před/po linku)

**2. KoogQualifierAgent zpracuje EMAIL task (DATA_PROCESSING):**

- Indexuje email obsah do RAG/Graph
- Vytvoří Email vertex
- Vytvoří Person vertices (from, to, cc)
- Vytvoří Graph edges: Email→Person (sender), Person→Email (recipient)
- Uvede v metadata: "3 linky budou zpracovány samostatně"
- Routing: DONE (email indexován, linky čekají ve frontě)

**3. KoogQualifierAgent zpracuje LINK task (LINK_PROCESSING) - SAMOSTATNĚ:**

- Přečte link info (URL, source email/jira/confluence, context)
- Kvalifikuje bezpečnost (LinkSafetyQualifier):
    * Už indexován? → DONE (skip)
    * Unsafe pattern match? → DONE (blocked)
    * Whitelist domain? → SAFE
    * Blacklist domain/pattern? → UNSAFE
    * Jinak → UNCERTAIN
- Pro SAFE: stáhne obsah (document_from_web), indexuje do RAG/Graph
- Pro UNSAFE: vytvoří pattern (manage_link_safety), DONE
- Pro UNCERTAIN: vytvoří pattern NEBO stáhne (podle context analysis)
- Vytvoří Link vertex + Graph edge: Link→Source (found_in Email/Jira/Confluence)
- **REKURZE STOP:** Link NEEXTRAHUJE svoje vlastní linky - jen je uvede v kontextu
- Routing: DONE (link zpracován)

**Linky mohou být nalezeny v:**

- Email body (EmailContinuousIndexer)
- Jira issue description/comments (JiraContinuousIndexer)
- Confluence page content (ConfluenceContinuousIndexer)
- Git commit message (GitContinuousIndexer)

**Pattern Types pro manage_link_safety:**

- DOMAIN: Block entire domain (e.g., "unsubscribe.example.com")
- PATH_REGEX: Block URL path pattern (e.g., "/calendar/event\\?action=")
- QUERY_PARAM: Block query parameter (e.g., "action=accept")
- FULL_REGEX: Custom regex (e.g., ".*\\b(unsubscribe|opt-out)\\b.*")

**Task Flow Diagram:**

```
Email → DATA_PROCESSING task (email content + "3 linky nalezeny")
     → LINK_PROCESSING task (link1, source=email:123)
     → LINK_PROCESSING task (link2, source=email:123)
     → LINK_PROCESSING task (link3, source=email:123)
```

**3. KoogQualifierAgent - nové tools (2025-12-07):**

```kotlin
// RagTools.storeChunk - ATOMIC chunk storage (PREFERRED)
@Tool
fun storeChunk(
    documentId: String,
    content: String,       // Agent-extracted entity context
    nodeKey: String,       // Graph node key
    entityTypes: List<String> = emptyList(),
    graphRefs: List<String> = emptyList(),
    knowledgeType: String = "DOCUMENT"
): String
// Returns chunkId for graph linking
// Agent extracts context, service only embeds

// RagTools.searchHybrid - hybrid search for duplicate detection
@Tool
fun searchHybrid(
    query: String,
    alpha: Float = 0.5f,  // 0.0=BM25, 0.5=hybrid, 1.0=vector
    maxResults: Int = 10,
    knowledgeTypes: List<String>? = null
): String

// SequentialIndexingTool - LEGACY (pouze pro velké dokumenty)
@Tool
fun indexDocument(
    documentId: String,
    content: String,
    title: String,
    location: String,
    knowledgeType: String = "DOCUMENT",
    relatedDocs: List<String> = emptyList()
): String
// Používá automatický chunking (4000 chars, 200 overlap)
// Jen pro velké dokumenty kde není potřeba přesná extrakce

// GraphRagLinkerTool - bi-directional linking
@Tool
fun createNodeWithRagLinks(
    nodeKey: String,
    nodeType: String,
    properties: String,
    ragChunkIds: String = ""
): String

@Tool
fun createRelationship(
    fromKey: String,
    toKey: String,
    edgeType: String,
    properties: String = ""
): String

// TaskRoutingTool - routing decision
@Tool
fun routeTask(
    routing: String, // "DONE" or "READY_FOR_GPU"
    reason: String,
    contextSummary: String = "",
    graphNodeKeys: String = "",  // Comma-separated
    ragDocIds: String = ""       // Comma-separated
): String
```

**Chunking strategie:**

**PREFERRED: Agent-driven atomic chunking (RagTools.storeChunk)**

- Agent identifikuje hlavní entitu v textu (Confluence page, Email, Jira issue)
- Agent extrahuje POUZE entity-defining text snippet (title, summary, klíčová pole)
- Agent zkonstruuje nodeKey podle pattern ze schema (např. `confluence::<pageId>`)
- Agent volá `storeChunk()` s extrahovaným contextem
- Service jen embeduje a ukládá do Weaviate s BM25 indexací
- Výhody: Přesná kontrola nad chunky, hybrid search, žádná redundance

**LEGACY: Automatický chunking (SequentialIndexingTool.indexDocument)**

- Pouze pro velké dokumenty (≥ 4000 chars) kde není potřeba přesná extrakce
- Chunk size: 4000 znaků, Overlap: 200 znaků (kontext kontinuita)
- Service automaticky rozdělí dokument a embeduje všechny chunky
- Použití: Výjimečně v KoogQualifierAgent pro velké plain-text dokumenty

**4. TaskMemory - context passing:**

```kotlin
@Document(collection = "task_memory")
data class TaskMemoryDocument(
    val correlationId: String,           // 1:1 s PendingTask
    val clientId: ObjectId,
    val projectId: ObjectId?,
    val contextSummary: String,          // Brief overview pro GPU
    val graphNodeKeys: List<String>,     // Graph references
    val ragDocumentIds: List<String>,    // RAG chunk IDs
    val structuredData: Map<String, String>, // Metadata
    val routingDecision: String,         // DONE / READY_FOR_GPU
    val routingReason: String,
    val sourceType: String?,             // EMAIL, JIRA, GIT_COMMIT
    val sourceId: String?
)
```

**Repository:** `TaskMemoryRepository : CoroutineCrudRepository<TaskMemoryDocument, ObjectId>`

**Service API:**

```kotlin
suspend fun saveTaskMemory(...): TaskMemoryDocument
suspend fun loadTaskMemory(correlationId: String): TaskMemoryDocument?
suspend fun deleteTaskMemory(correlationId: String)
```

**5. KoogWorkflowAgent - TaskMemoryTool:**

```kotlin
// Načte context z Qualifiera
@Tool
fun loadTaskContext(): String
```

**Použití v workflow:**

1. Agent zavolá `loadTaskContext()` na začátku
2. Získá: context summary, Graph node keys, RAG document IDs
3. Použije Graph/RAG tools s poskytnutými keys/IDs pro full content
4. Fokus na analýzu a akce, ne na strukturování

**6. BackgroundEngine - preemption:**

**Mechanismus:**

- `LlmLoadMonitor` trackuje aktivní user requesty
- User request start → `registerRequestStart()` → `interruptNow()`
- `interruptNow()` ruší aktuální background task (Job.cancel())
- Background tasky pokračují až po idle thresholdu (30s)

**Dva loops:**

- **Qualification loop (CPU):** běží kontinuálně, 30s interval
- **Execution loop (GPU):** běží POUZE když je idle (žádné user requesty)

**Preemption garantuje:** User requesty mají VŽDY prioritu nad background tasky

#### Routing kritéria (TaskRoutingTool)

**DONE (simple structuring):**

- Dokument indexován a strukturován (Graph + RAG)
- Žádné action items nebo rozhodnutí
- Jednoduchý informační obsah
- Rutinní updaty (status change, minor commit)

**READY_FOR_GPU (complex analysis):**

- Vyžaduje akci uživatele (odpovědět na email, update Jira, review code)
- Komplexní rozhodování
- Analýza nebo investigace
- Code changes nebo architektonická rozhodnutí
- Koordinace více entit
- Task zmiňuje current usera nebo vyžaduje jeho expertízu

**Context Summary (pro READY_FOR_GPU):**

- Stručný přehled strukturovaných dat
- Klíčové nálezy a action items
- Otázky vyžadující odpověď
- Graph node keys pro quick reference
- RAG document IDs pro full content retrieval

#### Výhody architektury

1. **Cost efficiency:** Drahé GPU modely jen když nutné
2. **No context overflow:** Chunking řeší velké dokumenty
3. **Bi-directional navigation:** Graph (structured) ↔ RAG (semantic)
4. **Efficient context passing:** TaskMemory eliminuje redundantní práci
5. **User priority:** Preemption zajišťuje okamžitou response
6. **Scalability:** CPU kvalifikace může běžet paralelně na více tasků

#### Referenční implementace

**Lokace:**

- Tools: `backend/server/src/main/kotlin/com/jervis/koog/tools/`
    - `SequentialIndexingTool.kt`
    - `GraphRagLinkerTool.kt`
    - `TaskRoutingTool.kt`
    - `TaskMemoryTool.kt`
- Agents: `backend/server/src/main/kotlin/com/jervis/koog/`
    - `qualifier/KoogQualifierAgent.kt`
    - `KoogWorkflowAgent.kt`
- Memory: `backend/server/src/main/kotlin/com/jervis/service/agent/TaskMemoryService.kt`
- Indexers: `backend/server/src/main/kotlin/com/jervis/service/`
    - `email/EmailContinuousIndexer.kt`
    - `jira/BugTrackerContinuousIndexer.kt`
    - `listener/git/GitContinuousIndexer.kt`
- Background: `backend/server/src/main/kotlin/com/jervis/service/background/BackgroundEngine.kt`

**Konfigurace:**

- Max iterations Qualifier: 10 (pro chunking loops)
- Max iterations Workflow: 20 (komplexní workflow)
- Idle threshold: 30s (GPU execution)
- Qualification interval: 30s (DB polling)

## 3.4) Bezpečnostní hlavičky klienta (nutné pro Desktop/Mobile)

- Serverový `SecurityHeaderFilter` VYŽADUJE hlavičku `X-Jervis-Client` se sdíleným tokenem. Chybějící/špatná hodnota →
  spojení bez odpovědi (prevence skenování portů).
- Klient (Desktop/Mobile) MUSÍ posílat:
    - `X-Jervis-Client: <token>` (viz `application.yml → jervis.security.client-token` a
      `shared/common-api/SecurityConstants`)
    - `X-Jervis-Platform: Desktop|Android|iOS`
- Desktop implementace: `NetworkModule.createHttpClient()` nastavuje hlavičky v `defaultRequest { ... }`.

## 4) Vrstvy (sjednoceno dle aktuálního kódu)

- Controller: pracuje s DTO; mapuje `DTO ↔ Entity` (případně přes mapper); volá Service. Controller nikdy nevrací Entity
  do UI.
- Service: pracuje s MongoDB Entity (Documents) a business pravidly; volá Repository. Domain typy lze použít pomocně (
  čisté výpočty), ale Service není povinně „domain‑only".
- Repository: pouze Entity ↔ DB.
    - **IMPORTANT:** Všechny repository MUSÍ používat `CoroutineCrudRepository` (non-blocking s Kotlin coroutines).
    - **ZAKÁZÁNO:** `MongoRepository` (blocking) se NEPOUŽÍVÁ. Vždy `CoroutineCrudRepository`.
    - Příklad: `interface AgentMemoryRepository : CoroutineCrudRepository<AgentMemoryDocument, ObjectId>`
    - Repository metody vrací `Flow<T>` pro streamy nebo `suspend fun` pro jednotlivé hodnoty.
- Zakázané vztahy: Controller → Repository, Service → DTO, Controller vrací Entity.

## 5) Programovací pravidla (Kotlin/Spring)

- Concurrency: coroutines, žádné `.block()` v produkci; Reactor pouze pro interop.
- DI: výhradně constructor injection. Žádné field injection, žádné manuální singletony.
- Extension functions preferuj před „Utils" třídami.
- Serializace: `kotlinx.serialization` jako standard; Jackson jen pro interop (YAML, JSON bridging). Odlišná jména polí
  explicitně anotuj.
- Logging: strukturovaný pomocí `mu.KotlinLogging`, např. `private val logger = KotlinLogging.logger {}` a
  `logger.info { "message with $var" }`;
  doplňuj trace/correlation ID, pokud je k dispozici. **POZOR:** Používej `mu.KotlinLogging`, NE
  `io.github.oshai.kotlinlogging`.

## 6) Konfigurace (properties) – striktní pravidla

- Používej VÝHRADNĚ properties třídy: `@ConfigurationProperties` + `data class` s pouze `val` poli. ŽÁDNÉ `@Value` v
  kódu. ✓
- Žádné výchozí hodnoty v properties třídách, žádná `nullable` pole. Každá hodnota musí být poskytnuta v
  `application.yml` nebo ENV. ✓
- Chybějící hodnota = fail‑fast při startu aplikace (žádné tiché fallbacky). ✓
- Properties třídy mapuj na YAML strom logicky (prefixy). Příklad: `preload.ollama.*`, `ollama.keep-alive.default`.
- Definuj vlastní `@Qualifier` anotace pro rozlišení beanů (např. `@OllamaPrimary`, `@OllamaQualifier`) místo string
  klíčů.

### 6.1) Koog & Ollama – výběr endpointu/modelu

- Koog agenti NEčtou model z `prompts.yaml`. Efektivní model a endpoint se nastavují přímo v exekutoru / nebo přes
  provider‑kandidáty `models-config.yaml`.
- Pro JERVIS platí:
    - `OLLAMA` → GPU `endpoints.ollama.primary`
    - `OLLAMA_QUALIFIER` → CPU `endpoints.ollama.qualifier`
- Žádné feature‑flag fallbacky. Chybějící endpoint → fail‑fast při startu.

## 7) Struktura balíčků a přístupnost

- Jeden veřejný boundary interface na okraji celku; vnitřek označ `_internal` a udržuj package‑private (nesvádět k
  neplánovanému použití).
- Interface nepoužívej „za každou cenu“. Pokud není potřeba více implementací, stačí `class`.
- Pokud je potřeba re‑use napříč celky, vytvoř pomocný balíček se sdílenými službami (bez vynucování interface, pokud
  nedává smysl).

## 8) UI design (Compose Multiplatform – viz také docs/ui-design.md)

- Používej sdílené komponenty ze `shared/ui-common`:
    - `JTopBar`, `JSection`, `JActionBar`, `JTableHeaderRow`/`JTableHeaderCell`, `JTableRowCard`
    - View states: `JCenteredLoading`, `JErrorState(message, onRetry)`, `JEmptyState(message)`
    - Akce/util: `JRunTextButton`, `ConfirmDialog`, `RefreshIconButton`/`DeleteIconButton`/`EditIconButton`,
      `CopyableTextCard`
- Nahrazuj přímý `TopAppBar` → `JTopBar`. Stavové UI sjednoť na výše uvedené view‑state komponenty.
- Fail‑fast v UI: chyby zobrazuj, neukrývej. Spacing přes sdílené konstanty (JervisSpacing). Desktop je primární
  platforma; mobil je port.

### 8.1) UI notifikace a navigace

- Notifikace: používáme nenásilné snackbary v pravém horním rohu (Desktop). Vzor: `SnackbarHostState` + `SnackbarHost`
  zarovnaný `Alignment.TopEnd` v obsahu okna.
- Klávesa Tab: na Desktopu slouží pro přesun focusu (Next/Previous), nesmí vkládat tabulátory do textu. Přidat
  `onPreviewKeyEvent` na nejvyšší layout.

### 8.2) Connections – UI pravidla

- Přehled a správa připojení je dostupná v Settings (tab „Connections“) a v editacích Client/Project.
- Vlastnictví je exkluzivní: connectionDocument je přiděleno buď klientovi, nebo projektu. Pro re‑use použij
  „Duplicate“.
- Editace autorizace je typovaná (HTTP: NONE/BASIC/BEARER) – žádné generické `credentials` stringy.
- UI zobrazuje všechny hodnoty včetně „secrets“ (dev aplikace, žádné maskování).

## 9) RAG a indexace – Atomic Chunking Architecture (2025-12-07)

**Nová architektura:** Agent-driven atomic chunking s hybrid search (BM25 + Vector).

### 9.0) KnowledgeService API

**Atomické ukládání (PREFERRED):**

- `storeChunk(request: StoreChunkRequest): String` – Agent extrahuje entity context, service jen embeduje a ukládá
- Agent je zodpovědný za chunking a extrakci relevantního textu
- Service pouze generuje embedding a ukládá do Weaviate s BM25 indexací
- Vrací `chunkId` pro graph linking
- **Use case:** Strukturovaná data (Confluence, Email, Jira) kde agent ví co extrahovat

**Hybrid Search:**

- `searchHybrid(request: HybridSearchRequest): SearchResult` – Kombinuje BM25 (keyword) + Vector (semantic)
- `alpha` parametr: 0.0 = čistý BM25, 0.5 = hybrid, 1.0 = čistý vector
- **Use case:** Detekce duplicit před vytvořením nodu, vyhledávání přesných ID/kódů

**Legacy Batch API (pro velké dokumenty v SequentialIndexingTool):**

- `store(request: StoreRequest): StoreResult` – Automatický chunking velkých dokumentů (4000 chars, 200 overlap)
- Service dělá chunking, embedding i ukládání
- **Use case:** Pouze SequentialIndexingTool v KoogQualifierAgent pro velké dokumenty

**ZAKÁZÁNO:**

- ❌ Žádné orchestrátory předindexující data před agentem
- ❌ Agent sám rozhoduje co a kdy indexovat pomocí tools
- ❌ Context by byl příliš velký pokud předindexujeme vše dopředu

### Weaviate konfigurace

- Kolekce: `vectorizer="none"`, distance=cosine, HNSW parametry konzistentní
- Dimenze: 1024 (`bge-m3` embedding model)
- BM25 indexace: automatická na `content` field (tokenization=WORD)
- Properties: `content`, `node_key`, `documentId`, `projectId`, `entityTypes[]`, `graphRefs[]`, `metadata`

### 9.1) Per‑client OGDB + RAG (SSOT)

- Knowledge Graph: ArangoDB, per‑client kolekce (documents, entities, files, classes, methods, tickets, commits, emails,
  slack, requirements) + edge kolekce (mentions, defines, implements, modified_by, changes_ticket, affects, owned_by,
  concerns, describes). Vytváří se idempotentně na startu serveru (bootstrap).
- Weaviate RAG: per‑client třídy `{clientSlug}_rag_text` a `{clientSlug}_rag_code`. Properties: `content`, `projectId`,
  `sourcePath`, `chunkIndex`, `totalChunks`, `entityTypes[]`, `contentHash`, `graphRefs[]`. Vectorizer="none";
  distance=cosine; dimenze = 1024 (`bge-m3`).
- Cross‑links: Weaviate chunk nese `graphRefs[]`; Arango uzly nesou `ragChunks[]`. `RagMetadataUpdater` udržuje
  obousměrné odkazy (append; idempotentní klíče).
- Bootstrap: při startu serveru se pro každého klienta vytvoří per‑client OGDB+RAG zdroje a uloží se `ClientReadyStatus`
  do Mongo. Fail‑fast: pokud je klient aktivní a provisioning selže, start se ukončí s chybou.
- Health endpointy nevystavujeme; stav a provisioning řešíme idempotentními operacemi a logy.

## 9.2) Typová pravidla a identita (Kotlin‑first)

- Slug se nikde nepoužívá pro doménovou logiku. Všechny vazby a názvy odvozuj z ID (např. `clientId: ObjectId`).
- Zaveď `@JvmInline value class` pro hodnotové ID typy a používej je na hranicích API i v doméně:
    - `ClientId(val value: ObjectId)`, `ProjectId(val value: ObjectId)`, apod.
    - Maximalizuj typovou bezpečnost (žádná generická `String`/`ObjectId` tam, kde lze mít sémantický typ).
- Architektura: veřejné rozhraní vždy za `interface`; implementace uvnitř balíčku `*_internal` jako `internal`
  /package‑private. Doménové objekty:
    - pokud veřejné → na úrovni `interface` (API/kontrakty),
    - jinak do `*_internal` a nepřístupné vně balíčku.
- UI řeš vždy samostatně až po stabilizaci serveru (server‑first).

## 9.3) Auto‑provision a provoz

- OGDB (Arango): před jakoukoliv operací použij `ensureDatabase()`; `ensureSchema(clientId)` musí být idempotentní.
  První zápis smí implicitně vytvořit chybějící kolekce.
- RAG (Weaviate): třídy zakládej idempotentně dle `clientId` → jméno třídy odvoď deterministicky (např.
  `WeaviateClassNameUtil` z `ObjectId`). Při prvním `store/search` je povolen runtime guard.
- Embedding refresher: drž `keep_alive` přes `EmbeddingModelRefresher` (interval = `keep_alive * safetyFactor`), pro
  kompatibilitu posílej i `options.keep_alive` a `prompt|input`.

## 10) Modely (LLM/Embeddings) – provoz

- Ollama preloader běží asynchronně – start serveru na něj nečeká.
- Před prvním použitím chybějícího modelu klient provede blokující `pull` a počká (fail‑fast s jasnými logy). Pokud je
  model stažený ale „ne‑warmed“, první volání ho nahraje do paměti; používáme `keep_alive` (default 1h).
- Routing: `OLLAMA` → GPU (primary), `OLLAMA_QUALIFIER` → CPU (qualifier). Embeddingy běží na CPU Ollamě.
- Embeddingy: TEXT=`bge-m3` (1024), CODE=`bge-m3` (1024). U smíšených dotazů doporučen „dual retrieval + rank fusion" (
  sloučení top‑K z obou kolekcí).

---

### Backlog pro dorovnání kódu s guidelines

- Konfigurace: nahradit `@Value` → `@ConfigurationProperties`; přidat vlastní `@Qualifier` anotace pro WebClienty.
- Weaviate: pokud byla dříve Code kolekce v jiné dimenzi, provést reindex.
- UI: sjednotit na `JTopBar` + standardní view‑state komponenty, kde ještě nejsou.

### Specifika „Connections“ (vlastnictví, správa v UI)

- Connection entity smí patřit právě jednomu vlastníkovi: buď `Client`, nebo `Project` (ne oběma současně). Sdílení přes
  vícenásobné připojení se řeší duplikací.
- Správa připojení je součástí editace Klienta/Projektu (žádné samostatné „Connections“ okno). V UI zobrazovat kompletní
  hodnoty včetně secretů.
