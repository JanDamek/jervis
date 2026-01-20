# Plán: Odstranění A2A a návrat ke kRPC

## Důvod
A2A integrace je příliš komplikovaná a nefunguje správně. Uživatel chce vrátit se ke standardnímu Ktor kRPC přístupu, který funguje pro všechny ostatní služby.

## Co odstranit

### 1. OrchestratorAgent.kt
- Odstranit všechny A2A importy (řádky 3-15)
- Odstranit A2A klienty (aiderClient, openHandsClient, junieClient)
- Odstranit A2A node z strategy
- Odstranit createA2AClient a toA2aUrl funkce

### 2. Coding Tools - vrátit jako Tool Registry
Místo A2A delegace vytvořit tools:
- `AiderTool` - volá Aider přes kRPC
- `OpenHandsTool` - volá OpenHands přes kRPC
- `JunieTool` - volá Junie přes kRPC

### 3. Odstranit A2A modely
- `A2ADelegationSpec.kt`
- Vše v `orchestrator/model/` co je A2A related

### 4. Upravit InternalAgentTools
- Odstranit A2A related funkce
- Zachovat jen standardní tool based approach

### 5. Build dependencies
- Odstranit Koog A2A dependencies z `build.gradle.kts`
- Zachovat jen core Koog agents

## Co zachovat

- Všechny ostatní Tools (KnowledgeStorage, Jira, Confluence, atd.)
- Standard Ktor kRPC komunikaci
- Orchestrator strategie (bez A2A nodů)
- Všechny agenty (Context, Planner, SolutionArchitect, atd.)

## Implementace

### Krok 1: Vytvořit Coding Tools s kRPC

```kotlin
// backend/server/src/main/kotlin/com/jervis/koog/tools/coding/CodingTools.kt

class CodingTools(
    private val aiderClient: HttpClient,
    private val openHandsClient: HttpClient,
    private val junieClient: HttpClient
) {

    @LLMDescription("Execute coding task using Aider - for small, localized changes")
    suspend fun executeAider(
        @LLMDescription("Files to modify") files: List<String>,
        @LLMDescription("Coding instructions") instructions: String
    ): String {
        // kRPC volání na Aider
    }

    @LLMDescription("Execute coding task using OpenHands - for complex, multi-file changes")
    suspend fun executeOpenHands(
        @LLMDescription("Coding instructions") instructions: String
    ): String {
        // kRPC volání na OpenHands
    }

    @LLMDescription("Execute coding task using Junie - expensive but very fast, for time-critical tasks")
    suspend fun executeJunie(
        @LLMDescription("Coding instructions") instructions: String
    ): String {
        // kRPC volání na Junie
    }
}
```

### Krok 2: Zaregistrovat Tools do Orchestratora

V `OrchestratorAgent.kt`:

```kotlin
val codingTools = CodingTools(
    aiderClient = ktorClientFactory.getHttpClient("aider"),
    openHandsClient = ktorClientFactory.getHttpClient("coding"),
    junieClient = ktorClientFactory.getHttpClient("junie")
)

val tools = ToolRegistry().apply {
    register(codingTools)
    register(knowledgeTools)
    // ... ostatní tools
}
```

### Krok 3: Odstranit A2A z strategy

Místo A2A nodů použít standardní tool execution:

```kotlin
strategy("JERVIS Orchestrator") {
    val nodeLLMRequest by nodeLLMRequest()
    val nodeExecuteTool by nodeExecuteTool()  // <-- použije coding tools
    val nodeSendToolResult by nodeLLMSendToolResult()

    // ... standard Koog agent flow bez A2A
}
```

## Výhody tohoto přístupu

1. ✅ Konzistentní s ostatními službami (Jira, Confluence, Tika, atd.)
2. ✅ Jednodušší - žádné A2A agent cards, resolvers, atd.
3. ✅ Funguje s existující infrastrukturou
4. ✅ LLM rozhoduje kdy použít který coding tool (místo orchestrator strategie)
5. ✅ Snadnější debugging - standardní kRPC logy
