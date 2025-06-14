package com.jervis.module.assistantapi

import com.jervis.dto.ChatCompletionRequest
import com.jervis.dto.CompletionRequest
import com.jervis.dto.CompletionResponse
import com.jervis.dto.EmbeddingRequest
import com.jervis.dto.EmbeddingResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/**
 * Controller for handling Assistant API requests.
 * This controller exposes endpoints for the Assistant API service.
 */
@RestController
@RequestMapping("/assistant/v1")
@CrossOrigin(
    origins = ["*"],
    allowedHeaders = ["*"],
    methods = [RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS],
)
class AssistantApiController(
    private val assistantApiService: AssistantApiService,
) {
    /**
     * Get available models
     *
     * @return List of available models
     */
    @GetMapping("/models", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getModels(): ResponseEntity<Map<String, Any>> {
        val models = assistantApiService.getAvailableModels()
        return ResponseEntity.ok(mapOf("data" to models, "object" to "list"))
    }

    /**
     * Process a completion request
     *
     * @param request The completion request
     * @return The completion response
     */
    @PostMapping(
        "/completions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getCompletion(
        @RequestBody request: CompletionRequest,
    ): ResponseEntity<CompletionResponse> {
        val response = assistantApiService.processCompletion(request)
        return ResponseEntity.ok(response)
    }

    /**
     * Process a chat completion request
     *
     * @param request The chat completion request
     * @return The completion response
     */
    @PostMapping(
        "/chat/completions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getChatCompletion(
        @RequestBody request: ChatCompletionRequest,
    ): ResponseEntity<CompletionResponse> {
        val response = assistantApiService.processChatCompletion(request)
        return ResponseEntity.ok(response)
    }

    /**
     * Process an embeddings request
     *
     * @param request The embeddings request
     * @return The embeddings response
     */
    @PostMapping(
        "/embeddings",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getEmbeddings(
        @RequestBody request: EmbeddingRequest,
    ): ResponseEntity<EmbeddingResponse> {
        val response = assistantApiService.processEmbeddings(request)
        return ResponseEntity.ok(response)
    }
}