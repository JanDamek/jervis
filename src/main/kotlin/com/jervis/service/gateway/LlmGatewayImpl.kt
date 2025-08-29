package com.jervis.service.gateway

import com.jervis.configuration.EndpointProperties
import com.jervis.configuration.ModelsProperties
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Service
class LlmGatewayImpl(
    private val modelsProperties: ModelsProperties,
    private val endpoints: EndpointProperties,
    @Qualifier("lmStudioWebClient") private val lmStudioClient: WebClient,
    @Qualifier("ollamaWebClient") private val ollamaClient: WebClient,
    @Qualifier("openaiWebClient") private val openaiClient: WebClient,
    @Qualifier("anthropicWebClient") private val anthropicClient: WebClient,
) : LlmGateway {
    private val logger = KotlinLogging.logger {}

    override suspend fun callLlm(
        type: ModelType,
        userPrompt: String,
        systemPrompt: String?,
        outputLanguage: String?,
        quick: Boolean,
    ): LlmResponse {
        val base = modelsProperties.models[type].orEmpty()
        val candidates = if (quick) base.filter { it.quick }.ifEmpty { base } else base
        if (candidates.isEmpty()) error("No LLM candidates configured for $type")

        val finalUser = buildUserPrompt(userPrompt, outputLanguage)
        var lastError: Throwable? = null
        for ((_, candidate) in candidates.withIndex()) {
            val provider = candidate.provider ?: continue
            val timeout = candidate.timeoutMs
            logger.info { "Calling LLM type=$type provider=$provider model=${candidate.model}" }
            val startNs = System.nanoTime()
            try {
                val result =
                    if (timeout != null && timeout > 0) {
                        withTimeout(timeout) {
                            doCall(
                                provider,
                                candidate.model,
                                systemPrompt,
                                finalUser,
                                candidate,
                            )
                        }
                    } else {
                        doCall(
                            provider,
                            candidate.model,
                            systemPrompt,
                            finalUser,
                            candidate,
                        )
                    }
                val tookMs = (System.nanoTime() - startNs) / 1_000_000
                logger.info { "LLM call succeeded provider=$provider model=${candidate.model} in ${tookMs}ms" }
                if (result.answer.isNotBlank()) return result
            } catch (t: Throwable) {
                lastError = t
                val tookMs = (System.nanoTime() - startNs) / 1_000_000
                val errDetail =
                    when (t) {
                        is org.springframework.web.reactive.function.client.WebClientResponseException ->
                            "status=${t.statusCode.value()} body='${t.responseBodyAsString.take(500)}'"

                        else -> "${t::class.simpleName}: ${t.message}"
                    }
                logger.error { "LLM call failed provider=$provider model=${candidate.model} after ${tookMs}ms: $errDetail" }
            }
        }
        throw IllegalStateException("All LLM candidates failed for $type", lastError)
    }

    private suspend fun doCall(
        provider: ModelProvider,
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        candidate: ModelsProperties.ModelDetail,
    ): LlmResponse =
        when (provider) {
            ModelProvider.LM_STUDIO -> callLmStudio(model, systemPrompt, userPrompt, candidate)
            ModelProvider.OLLAMA -> callOllama(model, systemPrompt, userPrompt, candidate)
            ModelProvider.OPENAI -> callOpenAi(model, systemPrompt, userPrompt, candidate)
            ModelProvider.ANTHROPIC -> callAnthropic(model, systemPrompt, userPrompt, candidate)
        }

    private suspend fun callLmStudio(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        c: ModelsProperties.ModelDetail,
    ): LlmResponse {
        val messages = mutableListOf<Map<String, Any>>()
        if (!systemPrompt.isNullOrBlank()) messages += mapOf("role" to "system", "content" to systemPrompt)
        messages += mapOf("role" to "user", "content" to userPrompt)
        val body =
            mutableMapOf<String, Any>(
                "model" to model,
                "messages" to messages,
            )
        c.temperature?.let { body["temperature"] = it }
        c.topP?.let { body["top_p"] = it }
        c.maxTokens?.let { body["max_tokens"] = it }
        val resp: OpenAiStyleResponse =
            lmStudioClient
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .awaitBody()
        val answer =
            resp
                .choices
                .firstOrNull()
                ?.message
                ?.content
                .orEmpty()
        val finish = resp.choices.firstOrNull()?.finish_reason ?: "stop"
        val rModel = resp.model ?: model
        val p = resp.usage?.prompt_tokens ?: 0
        val cTok = resp.usage?.completion_tokens ?: 0
        val t = resp.usage?.total_tokens ?: (p + cTok)
        return LlmResponse(
            answer = answer,
            model = rModel,
            promptTokens = p,
            completionTokens = cTok,
            totalTokens = t,
            finishReason = finish,
        )
    }

    private suspend fun callOllama(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        c: ModelsProperties.ModelDetail,
    ): LlmResponse {
        val options = mutableMapOf<String, Any>()
        c.temperature?.takeIf { it > 0.0 }?.let { options["temperature"] = it }
        c.topP?.takeIf { it in 0.0..1.0 }?.let { options["top_p"] = it }
        c.maxTokens?.takeIf { it > 0 }?.let { options["num_predict"] = it }

        val body = mutableMapOf<String, Any>(
            "model" to model,
            "prompt" to userPrompt,
            "stream" to false,
        )
        if (!systemPrompt.isNullOrBlank()) body["system"] = systemPrompt
        if (options.isNotEmpty()) body["options"] = options

        logger.debug {
            val opts = if (options.isEmpty()) "none" else options.keys.joinToString(",")
            "OLLAMA_REQUEST: path=/api/generate model=$model stream=false options=$opts system=${!systemPrompt.isNullOrBlank()}"
        }
        try {
            val resp: OllamaGenerateResponse =
                ollamaClient
                    .post()
                    .uri("/api/generate")
                    .bodyValue(body)
                    .retrieve()
                    .awaitBody()
            val answer = resp.response.orEmpty()
            val rModel = resp.model ?: model
            val finish = resp.done_reason ?: "stop"
            val p = resp.prompt_eval_count ?: 0
            val cTok = resp.eval_count ?: 0
            val t = p + cTok
            return LlmResponse(
                answer = answer,
                model = rModel,
                promptTokens = p,
                completionTokens = cTok,
                totalTokens = t,
                finishReason = finish,
            )
        } catch (e: org.springframework.web.reactive.function.client.WebClientResponseException) {
            logger.info { "OLLAMA_FALLBACK: /api/generate failed (${e.statusCode.value()}), retrying via /api/chat" }
            return callOllamaChat(model, systemPrompt, userPrompt, options)
        }
    }

    private suspend fun callOllamaChat(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        options: Map<String, Any>,
    ): LlmResponse {
        val messages = mutableListOf<Map<String, Any>>()
        if (!systemPrompt.isNullOrBlank()) messages += mapOf("role" to "system", "content" to systemPrompt)
        messages += mapOf("role" to "user", "content" to userPrompt)
        val body = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messages,
            "stream" to false,
        )
        if (options.isNotEmpty()) body["options"] = options
        logger.debug {
            val opts = if (options.isEmpty()) "none" else options.keys.joinToString(",")
            "OLLAMA_REQUEST: path=/api/chat model=$model stream=false options=$opts system=${!systemPrompt.isNullOrBlank()}"
        }
        val resp: OllamaChatResponse =
            ollamaClient
                .post()
                .uri("/api/chat")
                .bodyValue(body)
                .retrieve()
                .awaitBody()
        val answer = resp.message?.content.orEmpty()
        val rModel = resp.model ?: model
        val p = resp.prompt_eval_count ?: 0
        val cTok = resp.eval_count ?: 0
        val t = p + cTok
        return LlmResponse(
            answer = answer,
            model = rModel,
            promptTokens = p,
            completionTokens = cTok,
            totalTokens = t,
            finishReason = "stop",
        )
    }

    private suspend fun callOpenAi(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        c: ModelsProperties.ModelDetail,
    ): LlmResponse {
        val messages = mutableListOf<Map<String, Any>>()
        if (!systemPrompt.isNullOrBlank()) messages += mapOf("role" to "system", "content" to systemPrompt)
        messages += mapOf("role" to "user", "content" to userPrompt)
        val body =
            mutableMapOf<String, Any>(
                "model" to model,
                "messages" to messages,
            )
        c.temperature?.let { body["temperature"] = it }
        c.topP?.let { body["top_p"] = it }
        c.maxTokens?.let { body["max_tokens"] = it }
        val resp: OpenAiStyleResponse =
            openaiClient
                .post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .awaitBody()
        val answer =
            resp
                .choices
                .firstOrNull()
                ?.message
                ?.content
                .orEmpty()
        val finish = resp.choices.firstOrNull()?.finish_reason ?: "stop"
        val rModel = resp.model ?: model
        val p = resp.usage?.prompt_tokens ?: 0
        val cTok = resp.usage?.completion_tokens ?: 0
        val t = resp.usage?.total_tokens ?: (p + cTok)
        return LlmResponse(
            answer = answer,
            model = rModel,
            promptTokens = p,
            completionTokens = cTok,
            totalTokens = t,
            finishReason = finish,
        )
    }

    private suspend fun callAnthropic(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        c: ModelsProperties.ModelDetail,
    ): LlmResponse {
        val messages = listOf(mapOf("role" to "user", "content" to userPrompt))
        val body =
            mutableMapOf<String, Any>(
                "model" to model,
                "messages" to messages,
                "max_tokens" to (c.maxTokens ?: 1024),
            )
        if (!systemPrompt.isNullOrBlank()) body["system"] = systemPrompt
        c.temperature?.let { body["temperature"] = it }
        val resp: AnthropicMessagesResponse =
            anthropicClient
                .post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .awaitBody()
        val answer =
            resp
                .content
                .firstOrNull()
                ?.text
                .orEmpty()
        val finish = resp.stop_reason ?: "stop"
        val rModel = resp.model ?: model
        val p = resp.usage?.input_tokens ?: 0
        val cTok = resp.usage?.output_tokens ?: 0
        val t = p + cTok
        return LlmResponse(
            answer = answer,
            model = rModel,
            promptTokens = p,
            completionTokens = cTok,
            totalTokens = t,
            finishReason = finish,
        )
    }

    private fun buildUserPrompt(
        userPrompt: String,
        outputLanguage: String?,
    ): String {
        if (outputLanguage.isNullOrBlank()) return userPrompt
        return "$userPrompt\n\nPlease respond in language: $outputLanguage"
    }
}

// Provider DTOs (subset)
private data class OpenAiStyleResponse(
    val id: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

private data class OpenAiChoice(
    val index: Int = 0,
    val message: OpenAiMessage = OpenAiMessage(),
    val finish_reason: String? = null,
)

private data class OpenAiMessage(
    val role: String = "assistant",
    val content: String = "",
)

private data class OpenAiUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null,
)

private data class OllamaGenerateResponse(
    val model: String? = null,
    val response: String = "",
    val done_reason: String? = null,
    val prompt_eval_count: Int? = null,
    val eval_count: Int? = null,
)

private data class OllamaChatResponse(
    val model: String? = null,
    val message: OllamaChatMessage? = null,
    val prompt_eval_count: Int? = null,
    val eval_count: Int? = null,
)

private data class OllamaChatMessage(
    val role: String? = null,
    val content: String = "",
)

private data class AnthropicMessagesResponse(
    val id: String? = null,
    val model: String? = null,
    val content: List<AnthropicContent> = emptyList(),
    val stop_reason: String? = null,
    val usage: AnthropicUsage? = null,
)

private data class AnthropicContent(
    val type: String = "text",
    val text: String = "",
)

private data class AnthropicUsage(
    val input_tokens: Int? = null,
    val output_tokens: Int? = null,
)