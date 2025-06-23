package com.jervis.controller

import com.jervis.dto.ChatCompletionRequest
import com.jervis.dto.ChatCompletionResponse
import com.jervis.dto.CompletionRequest
import com.jervis.dto.CompletionResponse
import com.jervis.dto.EmbeddingRequest
import com.jervis.dto.EmbeddingResponse
import com.jervis.module.mcp.McpLlmCoordinator
import com.jervis.service.CompletionService
import com.jervis.service.ProjectService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v0")
@CrossOrigin(
    origins = ["*"],
    allowedHeaders = ["*"],
    methods = [RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS],
)
class LMStudioController(
    private val completionService: CompletionService,
    private val projectService: ProjectService,
    private val mcpLlmCoordinator: McpLlmCoordinator,
) {
    @GetMapping("/models", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getModels(): Map<String, Any> {
        val models =
            projectService.getAllProjects().map { project ->
                mapOf(
                    "id" to (project.name ?: project.id.toString()),
                    "object" to "model",
                    "type" to "llm",
                    "publisher" to "jervis-local",
                    "arch" to "springboot-kotlin",
                    "compatibility_type" to "custom",
                    "quantization" to "none",
                    "state" to "loaded",
                    "max_context_length" to 8192,
                    "loaded_context_length" to 8192,
                )
            }
        return mapOf("data" to models, "object" to "list")
    }

    @PostMapping(
        "/completions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun getCompletion(
        @RequestBody request: CompletionRequest,
    ): CompletionResponse {
        return completionService.complete(request)
    }

    @PostMapping(
        "/chat/completions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun getChatCompletion(
        @RequestBody request: ChatCompletionRequest,
    ): ChatCompletionResponse {
        // Always use MCP for chat completions
        val project = projectService.getAllProjects().find { it.name == request.model }
        val projectId = project?.id

        // Create options map from request properties
        val options = mutableMapOf<String, Any>()
        request.temperature?.let { options["temperature"] = it.toFloat() }
        request.maxTokens?.let { options["max_tokens"] = it }

        // Add the entire messages list to options
        options["messages"] = request.messages

        // Add projectId to options if available
        projectId?.let { options["projectId"] = it }

        // Use an empty query since we're processing the entire messages list
        // The actual processing of messages happens in KoogMcpService
        val mcpResponse = mcpLlmCoordinator.processQueryWithMcp(
            query = "",
            context = "",
            options = options
        )

        // Convert MCP response to ChatCompletionResponse
        return ChatCompletionResponse(
            id = "chat-mcp-${java.util.UUID.randomUUID()}",
            `object` = "chat.completion",
            model = mcpResponse.model,
            created = java.time.Instant.now().epochSecond,
            choices = listOf(
                com.jervis.dto.Choice(
                    index = 0,
                    message = com.jervis.dto.ChatMessage(
                        role = "assistant",
                        content = mcpResponse.answer
                    ),
                    finishReason = "stop"
                )
            ),
            usage = com.jervis.dto.Usage(
                promptTokens = mcpResponse.promptTokens,
                completionTokens = mcpResponse.completionTokens,
                totalTokens = mcpResponse.totalTokens
            )
        )
    }

    @PostMapping(
        "/embeddings",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun getEmbeddings(
        @RequestBody request: EmbeddingRequest,
    ): EmbeddingResponse {
        return completionService.embeddings(request)
    }
}
