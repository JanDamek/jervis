package com.jervis.controller

import com.jervis.dto.ChatCompletionRequest
import com.jervis.dto.ChatCompletionResponse
import com.jervis.dto.CompletionRequest
import com.jervis.dto.CompletionResponse
import com.jervis.dto.EmbeddingRequest
import com.jervis.dto.EmbeddingResponse
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
) {
    @GetMapping("/models", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getModels(): ResponseEntity<Map<String, Any>> {
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
        return ResponseEntity.ok(mapOf("data" to models, "object" to "list"))
    }

    @PostMapping(
        "/completions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getCompletion(
        @RequestBody request: CompletionRequest,
    ): ResponseEntity<CompletionResponse> {
        val response = completionService.complete(request)
        return ResponseEntity.ok(response)
    }

    @PostMapping(
        "/chat/completions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getChatCompletion(
        @RequestBody request: ChatCompletionRequest,
    ): ResponseEntity<ChatCompletionResponse> {
        val response = completionService.chatComplete(request)
        return ResponseEntity.ok(response)
    }

    @PostMapping(
        "/embeddings",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getEmbeddings(
        @RequestBody request: EmbeddingRequest,
    ): ResponseEntity<EmbeddingResponse> {
        val response = completionService.embeddings(request)
        return ResponseEntity.ok(response)
    }
}
