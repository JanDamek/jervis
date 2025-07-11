package com.jervis.module.llmcoordinator

import com.fasterxml.jackson.annotation.JsonProperty
import com.jervis.module.llm.ModelRouterService
import com.jervis.module.llm.ProviderType
import com.jervis.module.llm.Message
import com.jervis.service.SettingService
import mu.KotlinLogging
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

/**
 * Service for coordinating LLM (Large Language Model) operations.
 * This service decides on query decomposition, LLM selection, and prompt construction.
 */
@Service
class LlmCoordinator(
    private val settingService: SettingService,
    private val tokenRateLimiter: TokenRateLimiter,
    private val modelRouterService: ModelRouterService,
) {
    private val logger = KotlinLogging.logger {}
    private val restTemplate = RestTemplate()

    // Initialize default values for settings
    init {
        initializeApiEndpoints()
    }

    /**
     * Initialize API endpoints
     */
    private fun initializeApiEndpoints() {
        if (settingService.getStringValueBlocking(settingService.getAnthropicApiUrl()) == "none") {
            settingService.saveValueBlocking(
                settingService.getAnthropicApiUrl(),
                "https://api.anthropic.com/v1/messages",
            )
        }
        if (settingService.getStringValueBlocking(settingService.getAnthropicApiVersion()) == "none") {
            settingService.saveValueBlocking(settingService.getAnthropicApiVersion(), "2023-06-01")
        }
        if (settingService.getStringValueBlocking(settingService.getOpenaiApiUrl()) == "none") {
            settingService.saveValueBlocking(
                settingService.getOpenaiApiUrl(),
                "https://api.openai.com/v1/chat/completions",
            )
        }
    }

    /**
     * Process a query using the appropriate LLM
     *
     * @param query The user query
     * @param context The context for the query
     * @param options Additional options for processing
     * @return The LLM response
     */
    suspend fun processQuery(
        query: String,
        context: String,
        options: Map<String, Any> = emptyMap(),
    ): LlmResponse {
        // Check if we should use local LLM models
        val useLocalModels = options["use_local_models"] as? Boolean ?: true

        if (useLocalModels) {
            try {
                logger.info { "Using local LLM models for query" }

                // Format the user message with context if available
                val userMessage =
                    if (context.isNotBlank()) {
                        "Context:\n$context\n\nUser Query: $query"
                    } else {
                        query
                    }

                // Process the query using the model router service
                // Determine the provider type based on options or default to SIMPLE
                val providerType = ProviderType.SIMPLE
                val systemPrompt = options["system_prompt"] as? String
                return modelRouterService.processQuery(userMessage, providerType, systemPrompt)
            } catch (e: Exception) {
                logger.error(e) { "Error using local LLM models, falling back to remote APIs: ${e.message}" }
                // Fall back to remote APIs
            }
        }

        // If we're here, either useLocalModels is false or there was an error with local models

        // 1. Analyze the query to determine the best approach
        val queryType = analyzeQuery(query)

        // 2. Select the appropriate LLM based on the query type
        val llmProvider = selectLlmProvider(queryType, options)

        // 4. Generate the response using the selected LLM
        val temperature =
            options["temperature"] as? Float
                ?: settingService.getLlmTemperature()

        val maxTokens =
            (
                options["max_tokens"] as? Int
                    ?: settingService.getLlmDefaultMaxTokensValue()
            ).coerceAtLeast(1) // Ensure maxTokens is at least 1

        // Get the system prompt based on query type
        val systemPrompt =
            when (queryType) {
                QueryType.CODE -> settingService.getSystemPromptCodeValue()
                QueryType.EXPLANATION -> settingService.getSystemPromptExplanationValue()
                QueryType.SUMMARY -> settingService.getSystemPromptSummaryValue()
                QueryType.GENERAL -> settingService.getSystemPromptGeneralValue()
            }

        // Format the user message with context if available
        val userMessage =
            if (context.isNotBlank()) {
                "Context:\n$context\n\nUser Query: $query"
            } else {
                query
            }

        return when {
            // Check if the provider is Anthropic
            llmProvider.startsWith("anthropic.") -> {
                // Extract the model name from the provider string (e.g., "anthropic.claude-3-opus" -> "claude-3-opus")
                val modelName = llmProvider.substringAfter("anthropic.")
                processAnthropicQuery(modelName, systemPrompt, userMessage, maxTokens, temperature)
            }

            // Check if the provider is OpenAI
            llmProvider.startsWith("openai.") -> {
                // Extract the model name from the provider string (e.g., "openai.gpt-4o" -> "gpt-4o")
                val modelName = llmProvider.substringAfter("openai.")
                processOpenAiQuery(modelName, systemPrompt, userMessage, maxTokens, temperature)
            }

            // Unsupported provider
            else -> throw IllegalArgumentException("Unsupported LLM provider: $llmProvider")
        }
    }

    /**
     * Process a query using the Anthropic API
     *
     * @param model The model to use
     * @param systemPrompt The system prompt
     * @param userMessage The user message
     * @param maxTokens The maximum number of tokens to generate
     * @param temperature The temperature for generation
     * @return The LLM response
     */
    private fun processAnthropicQuery(
        model: String,
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
    ): LlmResponse {
        // Call the Anthropic API
        val response =
            callAnthropicApi(
                model = model,
                systemPrompt = systemPrompt,
                userMessage = userMessage,
                maxTokens = maxTokens,
                temperature = temperature,
            )

        return LlmResponse(
            answer = response.content.firstOrNull()?.text ?: "No response from LLM",
            model = response.model,
            promptTokens = response.usage.inputTokens,
            completionTokens = response.usage.outputTokens,
            totalTokens = response.usage.inputTokens + response.usage.outputTokens,
        )
    }

    /**
     * Process a query using the OpenAI API
     *
     * @param model The model to use
     * @param systemPrompt The system prompt
     * @param userMessage The user message
     * @param maxTokens The maximum number of tokens to generate
     * @param temperature The temperature for generation
     * @return The LLM response
     */
    private fun processOpenAiQuery(
        model: String,
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
    ): LlmResponse {
        // Call the OpenAI API
        val response =
            callOpenAiApi(
                model = model,
                systemPrompt = systemPrompt,
                userMessage = userMessage,
                maxTokens = maxTokens,
                temperature = temperature,
            )

        return LlmResponse(
            answer =
                response.choices
                    .firstOrNull()
                    ?.message
                    ?.content ?: "No response from LLM",
            model = response.model,
            promptTokens = response.usage.promptTokens,
            completionTokens = response.usage.completionTokens,
            totalTokens = response.usage.totalTokens,
            finishReason = response.choices.firstOrNull()?.finishReason ?: "unknown",
        )
    }

    /**
     * Call the Anthropic API
     *
     * @param model The model to use
     * @param systemPrompt The system prompt
     * @param userMessage The user message
     * @param maxTokens The maximum number of tokens to generate
     * @param temperature The temperature for generation
     * @return The Anthropic API response
     */
    private fun callAnthropicApi(
        model: String,
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
    ): AnthropicResponse {
        // Ensure maxTokens is at least 1
        val validMaxTokens = maxTokens.coerceAtLeast(1)
        val apiKey = settingService.getAnthropicApiKey()
        if (apiKey.isBlank() || apiKey == "none") {
            throw IllegalStateException("Anthropic API key not configured. Please set it in the settings.")
        }

        val apiUrl = settingService.getAnthropicApiUrlValue()
        val apiVersion = settingService.getAnthropicApiVersionValue()

        // Estimate token count for rate limiting
        // This is a simple estimation - in a production system, you might want to use a more accurate tokenizer
        val estimatedInputTokenCount = estimateTokenCount(systemPrompt, userMessage)

        // Estimate output token count - this is very rough, but we need some estimate
        // We'll use the validMaxTokens as an upper bound, but in practice it will often be less
        val estimatedOutputTokenCount = validMaxTokens

        // Check with rate limiter before proceeding for both input and output tokens
        if (!tokenRateLimiter.checkAndWaitForInput(estimatedInputTokenCount)) {
            throw RuntimeException("Input rate limit exceeded. Please try again later.")
        }

        if (!tokenRateLimiter.checkAndWaitForOutput(estimatedOutputTokenCount)) {
            throw RuntimeException("Output rate limit exceeded. Please try again later.")
        }

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.set("x-api-key", apiKey)
        headers.set("anthropic-version", apiVersion)

        val messages =
            listOf(
                Message(role = "user", content = userMessage),
            )

        val request =
            AnthropicRequest(
                model = model,
                system = systemPrompt,
                messages = messages,
                maxTokens = validMaxTokens,
                temperature = temperature,
            )

        val entity = HttpEntity(request, headers)

        val response =
            restTemplate.postForObject(apiUrl, entity, AnthropicResponse::class.java)
                ?: throw RuntimeException("Failed to get response from Anthropic API")

        // Record the actual output token usage
        tokenRateLimiter.recordOutputTokenUsage(response.usage.outputTokens)

        return response
    }

    /**
     * Call the OpenAI API
     *
     * @param model The model to use
     * @param systemPrompt The system prompt
     * @param userMessage The user message
     * @param maxTokens The maximum number of tokens to generate
     * @param temperature The temperature for generation
     * @return The OpenAI API response
     */
    private fun callOpenAiApi(
        model: String,
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
    ): OpenAiResponse {
        val apiKey = settingService.getOpenaiApiKey()
        if (apiKey.isBlank() || apiKey == "none") {
            throw IllegalStateException("OpenAI API key not configured. Please set it in the settings.")
        }

        val apiUrl = settingService.getOpenaiApiUrlValue()

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.setBearerAuth(apiKey)

        val messages =
            listOf(
                OpenAiMessage(role = "system", content = systemPrompt),
                OpenAiMessage(role = "user", content = userMessage),
            )

        val request =
            OpenAiRequest(
                model = model,
                messages = messages,
                maxTokens = maxTokens,
                temperature = temperature,
            )

        val entity = HttpEntity(request, headers)

        val response =
            restTemplate.postForObject(apiUrl, entity, OpenAiResponse::class.java)
                ?: throw RuntimeException("Failed to get response from OpenAI API")

        return response
    }

    /**
     * Analyze a query to determine its type
     *
     * @param query The query to analyze
     * @return The query type
     */
    private fun analyzeQuery(query: String): QueryType {
        // In a real implementation, this would use heuristics or a classifier
        return when {
            query.contains("code") || query.contains("function") || query.contains("class") -> QueryType.CODE
            query.contains("explain") || query.contains("how") -> QueryType.EXPLANATION
            query.contains("summarize") || query.contains("summary") -> QueryType.SUMMARY
            else -> QueryType.GENERAL
        }
    }

    /**
     * Select the appropriate LLM provider based on the query type
     *
     * @param queryType The type of query
     * @param options Additional options for selection
     * @return The selected LLM provider
     */
    private fun selectLlmProvider(
        queryType: QueryType,
        options: Map<String, Any>,
    ): String {
        // Check if a preferred provider is specified in the options
        val preferredProvider = options["preferred_provider"] as? String

        // Check if we should use OpenAI instead of Anthropic
        val useOpenAi = options["use_openai"] as? Boolean ?: false

        if (preferredProvider != null) {
            return preferredProvider
        }

        // If useOpenAi is true, return OpenAI provider
        if (useOpenAi) {
            return when (queryType) {
                QueryType.CODE -> "openai.${settingService.getOpenaiModelCodeValueBlocking()}"
                QueryType.EXPLANATION -> "openai.${settingService.getOpenaiModelExplanationValueBlocking()}"
                QueryType.SUMMARY -> "openai.${settingService.getOpenaiModelSummaryValueBlocking()}"
                QueryType.GENERAL -> "openai.${settingService.getOpenaiModelGeneralValueBlocking()}"
            }
        }

        // Default to Anthropic
        return when (queryType) {
            QueryType.CODE -> "anthropic.${settingService.getLlmModelCodeValueBlocking()}"
            QueryType.EXPLANATION -> "anthropic.${settingService.getLlmModelExplanationValueBlocking()}"
            QueryType.SUMMARY -> "anthropic.${settingService.getLlmModelSummaryValueBlocking()}"
            QueryType.GENERAL -> "anthropic.${settingService.getLlmModelGeneralValueBlocking()}"
        }
    }

    /**
     * Estimates the token count for a given text.
     * This is a simple estimation based on word count.
     * In a production system, you would use a more accurate tokenizer.
     *
     * @param systemPrompt The system prompt
     * @param userMessage The user message
     * @return The estimated token count
     */
    private fun estimateTokenCount(
        systemPrompt: String,
        userMessage: String,
    ): Int {
        // A very simple estimation: roughly 4 characters per token
        val totalChars = systemPrompt.length + userMessage.length
        return (totalChars / 4) + 20 // Adding a small buffer
    }

    /**
     * Verify if the Anthropic API key is valid by making a simple API call
     *
     * @param apiKey The API key to verify
     * @return True if the API key is valid, false otherwise
     */
    suspend fun verifyAnthropicApiKey(apiKey: String): Boolean {
        if (apiKey.isBlank()) {
            return false
        }

        val apiUrl = settingService.getAnthropicApiUrlValue()
        val apiVersion = settingService.getAnthropicApiVersionValue()

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers["x-api-key"] = apiKey
        headers["anthropic-version"] = apiVersion

        val messages =
            listOf(
                Message(role = "user", content = "Hello"),
            )

        val request =
            AnthropicRequest(
                model = "claude-3-haiku-20240307", // Use a known model for verification
                system = "You are a helpful assistant.",
                messages = messages,
                maxTokens = 10, // Minimal tokens for verification
                temperature = 0.0f,
            )

        val entity = HttpEntity(request, headers)

        return try {
            restTemplate.postForObject(apiUrl, entity, AnthropicResponse::class.java)
            true // If we get here, the API key is valid
        } catch (e: Exception) {
            logger.error { "Failed to verify Anthropic API key: ${e.message}" }
            false
        }
    }

    /**
     * Verify if the OpenAI API key is valid by making a simple API call
     *
     * @param apiKey The API key to verify
     * @return True if the API key is valid, false otherwise
     */
    suspend fun verifyOpenAiApiKey(apiKey: String): Boolean {
        if (apiKey.isBlank()) {
            return false
        }

        val apiUrl = settingService.getOpenaiApiUrlValue()

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers["Authorization"] = "Bearer $apiKey"

        val messages =
            listOf(
                OpenAiMessage(role = "user", content = "Hello"),
            )

        val request =
            OpenAiRequest(
                model = "gpt-3.5-turbo", // Use a known model for verification
                messages = messages,
                maxTokens = 10, // Minimal tokens for verification
                temperature = 0.0f,
            )

        val entity = HttpEntity(request, headers)

        return try {
            restTemplate.postForObject(apiUrl, entity, OpenAiResponse::class.java)
            true // If we get here, the API key is valid
        } catch (e: Exception) {
            logger.error { "Failed to verify OpenAI API key: ${e.message}" }
            false
        }
    }

    /**
     * Non-suspend version of processQuery that uses runBlocking
     *
     * @param query The user query
     * @param context The context for the query
     * @param options Additional options for processing
     * @return The LLM response
     */
    fun processQueryBlocking(
        query: String,
        context: String,
        options: Map<String, Any> = emptyMap(),
    ): LlmResponse =
        kotlinx.coroutines.runBlocking {
            processQuery(query, context, options)
        }
}

/**
 * Types of queries that can be processed
 */
enum class QueryType {
    CODE,
    EXPLANATION,
    SUMMARY,
    GENERAL,
}

/**
 * Response from an LLM
 */
data class LlmResponse(
    val answer: String,
    val model: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val finishReason: String = "stop",
)

/**
 * Anthropic API request
 */
data class AnthropicRequest(
    val model: String,
    val system: String,
    val messages: List<Message>,
    @JsonProperty("max_tokens")
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
) {
    init {
        require(maxTokens >= 1) { "max_tokens must be greater than or equal to 1" }
    }
}

/**
 * Anthropic API response
 */
data class AnthropicResponse(
    val id: String,
    val model: String,
    val content: List<ContentBlock>,
    val usage: Usage,
)

/**
 * Content block in Anthropic API response
 */
data class ContentBlock(
    val type: String,
    val text: String,
)

/**
 * Usage information in Anthropic API response
 */
data class Usage(
    @JsonProperty("input_tokens")
    val inputTokens: Int,
    @JsonProperty("output_tokens")
    val outputTokens: Int,
)

/**
 * OpenAI API request
 */
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @JsonProperty("max_tokens")
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
)

/**
 * Message for OpenAI API
 */
data class OpenAiMessage(
    val role: String,
    val content: String,
)

/**
 * OpenAI API response
 */
data class OpenAiResponse(
    val id: String,
    val model: String,
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage,
)

/**
 * Choice from OpenAI API
 */
data class OpenAiChoice(
    val index: Int,
    val message: OpenAiMessage,
    @JsonProperty("finish_reason")
    val finishReason: String,
)

/**
 * Usage information from OpenAI API
 */
data class OpenAiUsage(
    @JsonProperty("prompt_tokens")
    val promptTokens: Int,
    @JsonProperty("completion_tokens")
    val completionTokens: Int,
    @JsonProperty("total_tokens")
    val totalTokens: Int,
)
