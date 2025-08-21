package com.jervis.controller

import com.jervis.dto.completion.ChatCompletionRequest
import com.jervis.dto.completion.ChatCompletionResponse
import com.jervis.dto.completion.CompletionRequest
import com.jervis.dto.completion.CompletionResponse
import com.jervis.dto.embedding.EmbeddingRequest
import com.jervis.dto.embedding.EmbeddingResponse
import com.jervis.service.project.ProjectService
import org.springframework.http.MediaType
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
    ): CompletionResponse = completionService.complete(request)

    @PostMapping(
        "/chat/completions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun getChatCompletion(
        @RequestBody chatRequest: ChatCompletionRequest,
    ): ChatCompletionResponse = completionService.chatComplete(chatRequest)

    @PostMapping(
        "/embeddings",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun getEmbeddings(
        @RequestBody request: EmbeddingRequest,
    ): EmbeddingResponse = completionService.embeddings(request)
}
