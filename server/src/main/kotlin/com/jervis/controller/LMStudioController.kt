package com.jervis.controller

import com.jervis.dto.ChatMessage
import com.jervis.dto.ChatRequestContext
import com.jervis.dto.Choice
import com.jervis.dto.Usage
import com.jervis.dto.completion.ChatCompletionRequest
import com.jervis.dto.completion.ChatCompletionResponse
import com.jervis.dto.completion.CompletionChoice
import com.jervis.dto.completion.CompletionRequest
import com.jervis.dto.completion.CompletionResponse
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.project.ProjectService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v0")
@CrossOrigin(
    origins = ["*"],
    allowedHeaders = ["*"],
    methods = [RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS],
)
class LMStudioController(
    private val agentOrchestrator: AgentOrchestratorService,
    private val projectService: ProjectService,
) {
    @GetMapping("/models", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getModels(): Map<String, Any> {
        val models =
            projectService.getAllProjects().map { project ->
                mapOf(
                    "id" to project.name,
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
        val userPrompt = request.prompt
        val project =
            projectService.getProjectByName(request.model)
        val response =
            agentOrchestrator.handle(
                text = userPrompt,
                ctx =
                    ChatRequestContext(
                        clientId =
                            project.clientId.toString(),
                        projectId = project.id.toString(),
                        autoScope = false,
                    ),
            )
        return CompletionResponse(
            id = "cmpl-${UUID.randomUUID()}",
            `object` = "text_completion",
            model = request.model ?: "unknown",
            created = Instant.now().epochSecond,
            choices =
                listOf(
                    CompletionChoice(
                        text = response.message,
                        index = 0,
                        logprobs = null,
                        finishReason = "stop",
                    ),
                ),
            usage = Usage(promptTokens = 0, completionTokens = 0, totalTokens = 0),
        )
    }

    @PostMapping(
        "/chat/completions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun getChatCompletion(
        @RequestBody chatRequest: ChatCompletionRequest,
    ): ChatCompletionResponse {
        val userPrompt = chatRequest.messages.lastOrNull()?.content ?: ""
        val defaultProject =
            projectService.getProjectByName(chatRequest.model)
        val response =
            agentOrchestrator.handle(
                text = userPrompt,
                ctx =
                    ChatRequestContext(
                        clientId = requireNotNull(defaultProject.clientId) { "ClientId is required for chat completion" }.toString(),
                        projectId = defaultProject.id.toString(),
                        autoScope = false,
                    ),
            )
        return ChatCompletionResponse(
            id = "chat-${UUID.randomUUID()}",
            `object` = "chat.completion",
            model = chatRequest.model ?: "unknown",
            created = Instant.now().epochSecond,
            choices =
                listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = response.message),
                        finishReason = "stop",
                    ),
                ),
            usage = Usage(promptTokens = 0, completionTokens = 0, totalTokens = 0),
        )
    }
}
