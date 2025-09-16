package com.jervis.service.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.reactive.awaitFirst
import mu.KotlinLogging
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class LlmGateway(
    private val modelsProperties: ModelsProperties,
    private val promptRepository: PromptRepository,
    private val objectMapper: ObjectMapper,
    private val clients: List<ProviderClient>,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Estimates token count for text (rough approximation: 1 token ≈ 4 characters)
     */
    private fun estimateTokens(text: String): Int {
        return (text.length / 4.0).toInt() + 10 // Add buffer for safety
    }

    /**
     * Estimates total tokens needed for a prompt (system + user + response buffer)
     */
    private fun estimateTotalTokensNeeded(
        systemPrompt: String?,
        userPrompt: String,
    ): Int {
        val systemTokens = systemPrompt?.let { estimateTokens(it) } ?: 0
        val userTokens = estimateTokens(userPrompt)
        val responseBuffer = 500 // Buffer for response tokens
        return systemTokens + userTokens + responseBuffer
    }

    suspend fun <T : Any> callLlm(
        type: PromptTypeEnum,
        userPrompt: String,
        quick: Boolean,
        exampleInstance: T,
        mappingValue: Map<String, String> = emptyMap(),
        outputLanguage: String = "EN",
    ): T {
        val prompt = promptRepository.getPrompt(type)
        val systemPrompt = buildSystemPrompt(prompt, mappingValue, exampleInstance)
        val finalUser = buildUserPrompt(prompt, mappingValue, userPrompt, outputLanguage)
        val estimatedTokens = estimateTotalTokensNeeded(systemPrompt, finalUser)
        
        logger.debug { "Estimated tokens needed: $estimatedTokens for prompt type: $type" }

        return getCandidates(prompt.modelParams.modelType, quick, estimatedTokens)
            .switchIfEmpty(Mono.error(IllegalStateException("No LLM candidates configured for $type")))
            .concatMap { candidate -> attemptLlmCall(candidate, systemPrompt, finalUser, prompt, type) }
            .next()
            .map { response -> parseResponse(response.answer, exampleInstance) }
            .awaitFirst()
    }

    private fun getCandidates(
        modelType: com.jervis.domain.model.ModelType,
        quick: Boolean,
        estimatedTokens: Int
    ): Flux<ModelsProperties.ModelDetail> {
        val baseModels = modelsProperties.models[modelType] ?: emptyList()
        val quickFiltered = baseModels.asSequence()
            .filter { !quick || it.quick }
            .filter { candidate -> candidate.maxTokens?.let { it >= estimatedTokens } ?: true }
            .sortedBy { it.maxTokens ?: Int.MAX_VALUE }
            .toList()

        return Flux.fromIterable(quickFiltered)
    }

    private fun attemptLlmCall(
        candidate: ModelsProperties.ModelDetail,
        systemPrompt: String,
        finalUser: String,
        prompt: PromptConfig,
        type: PromptTypeEnum
    ): Mono<com.jervis.domain.llm.LlmResponse> {
        val provider = candidate.provider 
            ?: return Mono.error(IllegalStateException("Provider not specified for candidate"))
        
        val client = clients.find { it.provider == provider }
            ?: return Mono.error(IllegalStateException("No client found for provider $provider"))

        logger.info { "Calling LLM type=$type provider=$provider model=${candidate.model}" }
        val startNs = System.nanoTime()

        return Mono.fromCallable {
            kotlinx.coroutines.runBlocking {
                logger.debug { "LLM Request - systemPrompt=$systemPrompt, userPrompt=$finalUser" }
                client.call(candidate.model, systemPrompt, finalUser, candidate, prompt)
            }
        }
        .doOnSuccess { result ->
            val tookMs = (System.nanoTime() - startNs) / 1_000_000
            logger.info { "LLM call succeeded provider=$provider model=${candidate.model} in ${tookMs}ms" }
            logger.debug { "LLM Response - $result" }
        }
        .filter { it.answer.isNotBlank() }
        .switchIfEmpty(Mono.error(IllegalStateException("Empty response from $provider")))
        .onErrorMap { throwable ->
            val tookMs = (System.nanoTime() - startNs) / 1_000_000
            val errDetail = when (throwable) {
                is org.springframework.web.reactive.function.client.WebClientResponseException -> 
                    "status=${throwable.statusCode.value()} body='${throwable.responseBodyAsString.take(500)}'"
                else -> "${throwable::class.simpleName}: ${throwable.message}"
            }
            logger.error { "LLM call failed provider=$provider model=${candidate.model} after ${tookMs}ms: $errDetail" }
            IllegalStateException("LLM call failed for $provider: $errDetail", throwable)
        }
    }

    private fun buildUserPrompt(
        prompt: PromptConfig,
        mappingValue: Map<String, String>,
        userPrompt: String,
        outputLanguage: String?,
    ): String {
        val finalUserPrompt = applyMappingValues(
            prompt.userPrompt.replace("{userPrompt}", userPrompt),
            mappingValue
        )
        return appendLanguageInstruction(finalUserPrompt, outputLanguage)
    }

    private fun applyMappingValues(
        userPrompt: String,
        mappingValue: Map<String, String>
    ): String =
        mappingValue.entries.fold(userPrompt) { acc, (key, value) ->
            acc.replace("{$key}", value)
        }

    private fun appendLanguageInstruction(
        userPrompt: String,
        outputLanguage: String?
    ): String =
        outputLanguage
            ?.takeUnless { it.isBlank() }
            ?.let { "$userPrompt\n\nPlease respond in language: $it" }
            ?: userPrompt

    private fun buildSystemPrompt(
        prompt: PromptConfig,
        mappingValue: Map<String, String>,
        exampleInstance: Any?,
    ): String {
        var systemPrompt = prompt.systemPrompt
        mappingValue.forEach { (key, value) ->
            systemPrompt = systemPrompt.replace("{$key}", value)
        }
        if (prompt.modelParams.jsonMode) {
            require(exampleInstance != null) {
                "When JSON output is set true, can not be exampleInstance NULL."
            }
            val exampleJson = objectMapper.writeValueAsString(exampleInstance)
            systemPrompt +=
                """ 
                Please respond with valid JSON in this exact format:
                $exampleJson
                
                IMPORTANT: Return ONLY the JSON object without any markdown formatting, code blocks, or ```json``` tags. Just the pure JSON response.                
                """.trimIndent()
        }
        return systemPrompt
    }

    private fun cleanJsonResponse(response: String): String {
        val trimmed = response.trim()
        
        val cleaningStrategies = listOf(
            JsonCleaningStrategy("```json", "```") { it.removePrefix("```json").removeSuffix("```").trim() },
            JsonCleaningStrategy("```", "```") { it.removePrefix("```").removeSuffix("```").trim() }
        )
        
        return cleaningStrategies
            .firstOrNull { it.matches(trimmed) }
            ?.clean?.invoke(trimmed)
            ?: trimmed
    }

    private data class JsonCleaningStrategy(
        val prefix: String,
        val suffix: String,
        val clean: (String) -> String
    ) {
        fun matches(text: String): Boolean = text.startsWith(prefix) && text.endsWith(suffix)
    }

    private fun <T : Any> parseResponse(
        answer: String,
        exampleInstance: T,
    ): T {
        val cleanedAnswer = cleanJsonResponse(answer)
        return parseJsonWithStrategy(cleanedAnswer, exampleInstance)
    }

    private fun <T : Any> parseJsonWithStrategy(
        cleanedAnswer: String,
        exampleInstance: T,
    ): T {
        val strategy = createParsingStrategy(exampleInstance)
        return strategy.parse(cleanedAnswer, objectMapper)
    }

    private fun <T : Any> createParsingStrategy(exampleInstance: T): JsonParsingStrategy<T> {
        val strategyFactories = listOf<StrategyFactory<T>>(
            StrategyFactory({ it is Collection<*> && it.isNotEmpty() }, ::CollectionWithElementsParsingStrategy),
            StrategyFactory({ it is Collection<*> }, ::EmptyCollectionParsingStrategy)
        )
        
        return strategyFactories
            .firstOrNull { it.predicate(exampleInstance) }
            ?.factory?.invoke(exampleInstance)
            ?: SingleObjectParsingStrategy(exampleInstance)
    }

    private data class StrategyFactory<T : Any>(
        val predicate: (T) -> Boolean,
        val factory: (T) -> JsonParsingStrategy<T>
    )

    private interface JsonParsingStrategy<T : Any> {
        fun parse(cleanedAnswer: String, objectMapper: ObjectMapper): T
    }

    private class CollectionWithElementsParsingStrategy<T : Any>(
        private val exampleInstance: T
    ) : JsonParsingStrategy<T> {
        @Suppress("UNCHECKED_CAST")
        override fun parse(cleanedAnswer: String, objectMapper: ObjectMapper): T {
            val collection = exampleInstance as Collection<*>
            val elementType = collection.first()!!::class.java
            val collectionType = objectMapper.typeFactory.constructCollectionType(
                ArrayList::class.java,
                elementType,
            )
            return objectMapper.readValue(cleanedAnswer, collectionType) as T
        }
    }

    private class EmptyCollectionParsingStrategy<T : Any>(
        private val exampleInstance: T
    ) : JsonParsingStrategy<T> {
        @Suppress("UNCHECKED_CAST")
        override fun parse(cleanedAnswer: String, objectMapper: ObjectMapper): T {
            val listType = objectMapper.typeFactory.constructCollectionType(
                ArrayList::class.java,
                Any::class.java,
            )
            return objectMapper.readValue(cleanedAnswer, listType) as T
        }
    }

    private class SingleObjectParsingStrategy<T : Any>(
        private val exampleInstance: T
    ) : JsonParsingStrategy<T> {
        @Suppress("UNCHECKED_CAST")
        override fun parse(cleanedAnswer: String, objectMapper: ObjectMapper): T =
            objectMapper.readValue(cleanedAnswer, exampleInstance::class.java) as T
    }
}
