package com.jervis.service.gateway

import com.jervis.configuration.EndpointProperties
import com.jervis.configuration.ModelsProperties
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

@Service
class LlmGatewayImpl(
    private val modelsProperties: ModelsProperties,
    private val endpoints: EndpointProperties,
) : LlmGateway {
    private val logger = KotlinLogging.logger {}
    private val restTemplate = RestTemplate()
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    override suspend fun callLlm(
        type: ModelType,
        userPrompt: String,
        systemPrompt: String?,
        outputLanguage: String?,
    ): LlmResponse {
        val candidates = modelsProperties.models[type].orEmpty()
        if (candidates.isEmpty()) error("No LLM candidates configured for $type")

        val finalUser = buildUserPrompt(userPrompt, outputLanguage)
        var lastError: Throwable? = null
        for ((idx, candidate) in candidates.withIndex()) {
            val provider = candidate.provider ?: continue
            val key = semaphoreKey(type, idx)
            val capacity = candidate.concurrency ?: defaultConcurrency(provider)
            val semaphore = semaphores.computeIfAbsent(key) { Semaphore(capacity) }
            val timeout = candidate.timeoutMs
            logger.info { "Calling LLM type=$type provider=$provider model=${candidate.model}" }
            val startNs = System.nanoTime()
            try {
                val result =
                    if (timeout != null && timeout > 0) {
                        withTimeout(timeout) {
                            withPermit(semaphore) {
                                doCall(
                                    provider,
                                    candidate.model,
                                    systemPrompt,
                                    finalUser,
                                    candidate,
                                )
                            }
                        }
                    } else {
                        withPermit(semaphore) {
                            doCall(
                                provider,
                                candidate.model,
                                systemPrompt,
                                finalUser,
                                candidate,
                            )
                        }
                    }
                val tookMs = (System.nanoTime() - startNs) / 1_000_000
                logger.info { "LLM call succeeded provider=$provider model=${candidate.model} in ${tookMs}ms" }
                if (result.answer.isNotBlank()) return result
            } catch (t: Throwable) {
                lastError = t
                val tookMs = (System.nanoTime() - startNs) / 1_000_000
                logger.error { "LLM call failed provider=$provider model=${candidate.model} after ${tookMs}ms: ${t.message}" }
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
        withContext(Dispatchers.IO) {
            when (provider) {
                ModelProvider.LM_STUDIO -> callLmStudio(model, systemPrompt, userPrompt, candidate)
                ModelProvider.OLLAMA -> callOllama(model, systemPrompt, userPrompt, candidate)
                ModelProvider.OPENAI -> callOpenAi(model, systemPrompt, userPrompt, candidate)
                ModelProvider.ANTHROPIC -> callAnthropic(model, systemPrompt, userPrompt, candidate)
            }
        }

    private fun callLmStudio(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        c: ModelsProperties.ModelDetail,
    ): LlmResponse {
        val url = "${endpoints.lmStudio.baseUrl?.trimEnd('/') ?: "http://localhost:1234"}/v1/chat/completions"
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
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
        val resp = restTemplate.postForObject(url, HttpEntity(body, headers), OpenAiStyleResponse::class.java)
        val answer =
            resp
                ?.choices
                ?.firstOrNull()
                ?.message
                ?.content
                .orEmpty()
        val finish = resp?.choices?.firstOrNull()?.finish_reason ?: "stop"
        val rModel = resp?.model ?: model
        val p = resp?.usage?.prompt_tokens ?: 0
        val cTok = resp?.usage?.completion_tokens ?: 0
        val t = resp?.usage?.total_tokens ?: (p + cTok)
        return LlmResponse(
            answer = answer,
            model = rModel,
            promptTokens = p,
            completionTokens = cTok,
            totalTokens = t,
            finishReason = finish,
        )
    }

    private fun callOllama(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        c: ModelsProperties.ModelDetail,
    ): LlmResponse {
        // Use /generate (single-turn). Include system prompt inline.
        val url = OllamaUrl.buildApiUrl(endpoints.ollama.baseUrl ?: "http://localhost:11434", "/generate")
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val fullPrompt = if (!systemPrompt.isNullOrBlank()) "${systemPrompt}\n\n$userPrompt" else userPrompt
        val options = mutableMapOf<String, Any>()
        c.temperature?.let { options["temperature"] = it }
        c.topP?.let { options["top_p"] = it }
        c.maxTokens?.let { options["num_predict"] = it }
        val body =
            mapOf(
                "model" to model,
                "prompt" to fullPrompt,
                "options" to options,
                "stream" to false,
            )
        val resp = restTemplate.postForObject(url, HttpEntity(body, headers), OllamaGenerateResponse::class.java)
        val answer = resp?.response.orEmpty()
        val rModel = resp?.model ?: model
        val finish = resp?.done_reason ?: "stop"
        val p = resp?.prompt_eval_count ?: 0
        val cTok = resp?.eval_count ?: 0
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

    private fun callOpenAi(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        c: ModelsProperties.ModelDetail,
    ): LlmResponse {
        val base = endpoints.openai.baseUrl?.trimEnd('/') ?: "https://api.openai.com/v1"
        val url = "$base/chat/completions"
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set("Authorization", "Bearer ${endpoints.openai.apiKey.orEmpty()}")
            }
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
        val resp = restTemplate.postForObject(url, HttpEntity(body, headers), OpenAiStyleResponse::class.java)
        val answer =
            resp
                ?.choices
                ?.firstOrNull()
                ?.message
                ?.content
                .orEmpty()
        val finish = resp?.choices?.firstOrNull()?.finish_reason ?: "stop"
        val rModel = resp?.model ?: model
        val p = resp?.usage?.prompt_tokens ?: 0
        val cTok = resp?.usage?.completion_tokens ?: 0
        val t = resp?.usage?.total_tokens ?: (p + cTok)
        return LlmResponse(
            answer = answer,
            model = rModel,
            promptTokens = p,
            completionTokens = cTok,
            totalTokens = t,
            finishReason = finish,
        )
    }

    private fun callAnthropic(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        c: ModelsProperties.ModelDetail,
    ): LlmResponse {
        val base = endpoints.anthropic.baseUrl?.trimEnd('/') ?: "https://api.anthropic.com"
        val url = "$base/v1/messages"
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set("x-api-key", endpoints.anthropic.apiKey.orEmpty())
                set("anthropic-version", "2023-06-01")
            }
        val messages = listOf(mapOf("role" to "user", "content" to userPrompt))
        val body =
            mutableMapOf<String, Any>(
                "model" to model,
                "messages" to messages,
                "max_tokens" to (c.maxTokens ?: 1024),
            )
        if (!systemPrompt.isNullOrBlank()) body["system"] = systemPrompt
        c.temperature?.let { body["temperature"] = it }
        val resp = restTemplate.postForObject(url, HttpEntity(body, headers), AnthropicMessagesResponse::class.java)
        val answer =
            resp
                ?.content
                ?.firstOrNull()
                ?.text
                .orEmpty()
        val finish = resp?.stop_reason ?: "stop"
        val rModel = resp?.model ?: model
        val p = resp?.usage?.input_tokens ?: 0
        val cTok = resp?.usage?.output_tokens ?: 0
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

    private fun semaphoreKey(
        type: ModelType,
        index: Int,
    ) = "${type.name}#$index"

    private fun defaultConcurrency(provider: ModelProvider): Int =
        when (provider) {
            ModelProvider.LM_STUDIO -> 2
            ModelProvider.OLLAMA -> 4
            ModelProvider.OPENAI -> 10
            ModelProvider.ANTHROPIC -> 10
        }

    private suspend fun <T> withPermit(
        sem: Semaphore,
        block: suspend () -> T,
    ): T =
        try {
            withContext(Dispatchers.IO) { sem.acquire() }
            block()
        } finally {
            sem.release()
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
