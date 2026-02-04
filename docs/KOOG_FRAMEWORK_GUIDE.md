# Koog Framework 0.6.0 - Best Practices Guide

**Version:** 0.6.0
**Status:** Production Documentation

## Overview

Koog is an open-source Kotlin framework for building AI agents with type-safe DSL. This guide outlines best practices for using Koog 0.6.0 in the JERVIS project.

## Core Concepts

### 1. Agent Structure

Agents are built using **nodes** and **edges** that define the processing flow:

```kotlin
val agentStrategy = strategy<String, OutputType>("Agent Name") {
    val nodeLLMRequest by nodeLLMRequest()
    val nodeExecuteTool by nodeExecuteTool()
    val nodeSendToolResult by nodeLLMSendToolResult()

    edge(nodeStart forwardTo nodeLLMRequest)
    edge((nodeLLMRequest forwardTo nodeExecuteTool).onToolCall { true })
    edge(nodeExecuteTool forwardTo nodeSendToolResult)
    edge((nodeSendToolResult forwardTo nodeLLMRequest).onToolCall { true })
}
```

### 2. Tool Registration

Tools must be properly annotated and NEVER mentioned by name in prompts:

```kotlin
@Tool
@LLMDescription(
    "Analyze and interpret user request to understand the goal. " +
    "Returns: NormalizedRequest with type, entities and outcome."
)
suspend fun interpretRequest(): String {
    // Implementation
}
```

**CRITICAL RULES:**
- ❌ NEVER mention tool names in prompts (e.g., "call interpretRequest()")
- ❌ NEVER say "Always call X after Y"
- ✅ Use generic descriptions: "Analyze the request type"
- ✅ Let LLM discover tools via @LLMDescription

### 3. Structured Output

Use `nodeLLMRequestStructured` for typed responses:

```kotlin
val nodeDecision by nodeLLMRequestStructured<DecisionType>(
    name = "decide-next-action",
    examples = listOf(
        DecisionType(type = "FINAL", answer = "Task completed")
    )
)
```

### 4. Event Handling

Add event listeners for monitoring and UI feedback:

```kotlin
val agentConfig = AIAgentConfig(
    prompt = ...,
    model = ...,
    features = features {
        install(EventHandler) {
            onToolCall { ctx: ToolCallContext ->
                logger.info { "TOOL_CALL | tool=${ctx.tool?.name}" }
                // Send progress to UI
            }

            onToolResult { ctx: ToolResultContext ->
                logger.info { "TOOL_RESULT | tool=${ctx.tool?.name} | success=${ctx.result != null}" }
            }
        }
    }
)
```

## Timeout Management

### HTTP Client Configuration

**CRITICAL:** LLM calls must have NO timeout as they can take arbitrarily long:

```kotlin
install(HttpTimeout) {
    requestTimeoutMillis = null // No timeout for LLM calls
    connectTimeoutMillis = connectTimeout.toLong()
    socketTimeoutMillis = null // No socket timeout for streaming
}
```

**Why?**
- Ollama models on ports 11434/11435 may take 15+ minutes for complex reasoning
- Timeouts cause `HttpRequestTimeoutException` and break agent workflow
- Streaming responses need unlimited socket timeout

## Prompt Engineering

### System Prompt Structure

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
- Use clear section headers (WORKFLOW, STOP RULES, etc.)
- Avoid concrete tool names
- Use visual markers (❌ ✅) for emphasis
- Be specific about what NOT to do

### Routing Prompts

For decision nodes, use separate transformation prompts:

```kotlin
val nodeTransformToDecision by node<String, String> { assistantMessage ->
    buildDecisionPrompt(assistantMessage)
}

edge((nodeLLMRequest forwardTo nodeTransformToDecision).onAssistantMessage { true })
edge(nodeTransformToDecision forwardTo nodeDecideNextAction)
```

## Agent Patterns

### 1. Orchestrator Pattern

Coordinates multiple sub-agents:

```kotlin
// Main orchestrator with tool registry
val toolRegistry = ToolRegistry {
    tools(InternalAgentTools(...))  // Wraps sub-agents as tools
    tools(KnowledgeStorageTools(...))
    tools(CodingTools(...))
}

// Sub-agents are called via tools, NOT directly
@Tool
suspend fun interpretRequest(): String {
    val request = interpreterAgent.run(task)
    return json.encodeToString(request)
}
```

### 2. Planner Pattern

Uses GOAP or sequential planning:

```kotlin
goap<StateType>(stateType) {
    action(
        name = "ActionName",
        precondition = { state -> !state.done },
        belief = { state -> state.copy(done = true) },
        cost = { 1.0 }
    ) { ctx, state ->
        // Execute action
        ctx.requestLLM("prompt")
        // Return new state
    }
}
```

### 3. Specialist Pattern

Single-purpose agents with focused prompts:

```kotlin
strategy<String, NormalizedRequest>("Interpret Request") {
    val nodeInterpret by nodeLLMRequestStructured<NormalizedRequest>(
        examples = examples
    ).transform { it.getOrThrow().data }

    edge(nodeStart forwardTo nodeInterpret)
    edge(nodeInterpret forwardTo nodeFinish)
}
```

## Session & History Management

### Chat Session Persistence

```kotlin
// Find or create session
val session = chatSessionRepository.findAll().toList()
    .find { it.clientId == task.clientId && it.projectId == task.projectId }
    ?: ChatSessionDocument(
        sessionId = sessionKey,
        clientId = task.clientId,
        projectId = task.projectId
    )

// Append messages
val updatedSession = session.copy(
    messages = session.messages + ChatMessageDocument(...)
)
chatSessionRepository.save(updatedSession)
```

### History Compression

Use `nodeLLMCompressHistory` when context grows too large:

```kotlin
val nodeCompressHistory by nodeLLMCompressHistory<ReceivedToolResult>()

edge((nodeExecuteTool forwardTo nodeCompressHistory).onCondition {
    llm.readSession {
        prompt.messages.size > 200 ||
        prompt.messages.sumOf { it.content.length } > 200_000
    }
})
edge(nodeCompressHistory forwardTo nodeSendToolResult)
```

## UI Integration

### Progress Updates

Emit progress to UI using RPC streams:

```kotlin
if (task.type == TaskTypeEnum.USER_INPUT_PROCESSING) {
    agentOrchestratorRpc.emitToChatStream(
        clientId = task.clientId.toString(),
        projectId = task.projectId?.toString(),
        response = ChatResponseDto(
            message = "Analyzing request...",
            type = ChatResponseType.EXECUTING,
            metadata = mapOf("step" to "analysis")
        )
    )
}
```

### Final Response

**CRITICAL:** Always emit FINAL response after saving session:

```kotlin
// Save session
chatSessionRepository.save(finalSession)

// Emit FINAL response to UI
if (task.type == TaskTypeEnum.USER_INPUT_PROCESSING) {
    agentOrchestratorRpc.emitToChatStream(
        clientId = task.clientId.toString(),
        projectId = task.projectId?.toString(),
        response = ChatResponseDto(
            message = output,
            type = ChatResponseType.FINAL,
            metadata = mapOf("correlationId" to task.correlationId)
        )
    )
}
```

**Why?** Without FINAL emit, UI will show "Processing..." indefinitely.

## Error Handling

### Agent Errors

Koog automatically retries on structured output failures. For other errors:

```kotlin
try {
    val result = agent.run(userInput)
    return result
} catch (e: Exception) {
    logger.error(e) { "AGENT_ERROR | correlationId=${task.correlationId}" }

    // Emit error to UI
    if (task.type == TaskTypeEnum.USER_INPUT_PROCESSING) {
        agentOrchestratorRpc.emitToChatStream(
            clientId = task.clientId.toString(),
            projectId = task.projectId?.toString(),
            response = ChatResponseDto(
                message = "Error: ${e.message}",
                type = ChatResponseType.FINAL,
                metadata = mapOf("error" to "true")
            )
        )
    }

    throw e
}
```

### Tool Errors

Tool failures are handled by Koog framework. Use `onToolResult` to log:

```kotlin
onToolResult { ctx: ToolResultContext ->
    if (ctx.result == null) {
        logger.error { "TOOL_FAILED | tool=${ctx.tool?.name}" }
    }
}
```

## Common Anti-Patterns

### ❌ DON'T: Mention tool names in prompts

```kotlin
// BAD
system("First call interpretRequest(), then call getContext()")

// GOOD
system("First analyze the request type, then gather project context")
```

### ❌ DON'T: Use timeouts for LLM calls

```kotlin
// BAD
requestTimeoutMillis = 900000 // 15 minutes

// GOOD
requestTimeoutMillis = null // No limit
```

### ❌ DON'T: Forget to emit FINAL response

```kotlin
// BAD
chatSessionRepository.save(finalSession)
return output

// GOOD
chatSessionRepository.save(finalSession)
agentOrchestratorRpc.emitToChatStream(..., ChatResponseDto(..., type = FINAL))
return output
```

### ❌ DON'T: Use blocking calls in event handlers

```kotlin
// BAD
onToolCall { ctx ->
    runBlocking { emitToChatStream(...) } // Blocks agent execution
}

// GOOD - if needed, use background scope
onToolCall { ctx ->
    kotlinx.coroutines.runBlocking { // Acceptable for quick emissions
        emitToChatStream(...)
    }
}
```

## Testing Agents

### Unit Testing

```kotlin
@Test
fun `test agent processes request correctly`() = runBlocking {
    val task = TaskDocument(...)
    val agent = orchestratorAgent.create(task)

    val result = agent.run("test input")

    assertNotNull(result)
    assertTrue(result.contains("expected output"))
}
```

### Integration Testing

```kotlin
@Test
fun `test full orchestrator workflow`() = runBlocking {
    val task = createTask(type = TaskTypeEnum.USER_INPUT_PROCESSING)

    val output = orchestratorAgent.run(
        task = task,
        userInput = "Najdi co jsem koupil za notebooky v alze",
        onProgress = { msg, meta ->
            logger.info { "PROGRESS: $msg | $meta" }
        }
    )

    assertNotNull(output)
    // Verify session was updated
    val session = chatSessionRepository.findById(...)
    assertEquals(2, session.messages.size) // user + assistant
}
```

## Performance Optimization

### 1. Model Selection

Use `SmartModelSelector` to choose appropriate model based on context:

```kotlin
val model = smartModelSelector.selectModelBlocking(
    baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
    inputContent = task.content,
    projectId = task.projectId
)
```

### 2. Parallel Tool Calls

Koog can execute independent tools in parallel. Structure your strategy to allow this:

```kotlin
// Tools called in sequence will execute in parallel if possible
edge(nodeLLMRequest forwardTo nodeExecuteTool)
```

### 3. Context Management

Compress history early to avoid token limits:

```kotlin
val nodeCompressHistory by nodeLLMCompressHistory<ReceivedToolResult>()

// Compress when messages > 200 OR total content > 200KB
edge((nodeExecuteTool forwardTo nodeCompressHistory).onCondition {
    llm.readSession {
        prompt.messages.size > 200 ||
        prompt.messages.sumOf { it.content.length } > 200_000
    }
})
```

## References

- [Koog Official Docs](https://docs.koog.ai)
- [Koog GitHub](https://github.com/koog-ai/koog)
- [Complex Workflow Agents](https://docs.koog.ai/complex-workflow-agents/)

## Version History

- **2026-01-27**: Initial guide based on Koog 0.6.0 and JERVIS implementation
