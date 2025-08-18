package com.jervis.service.llm

import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelType
import com.jervis.service.setting.SettingService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Service for routing queries to the appropriate language model provider based on purpose.
 * This service decides which of the three providers to use based on ProviderType.
 */
@Service
class ModelRouterService(
    private val externalModelService: ExternalModelService,
    private val settingService: SettingService,
) {
    private val logger = KotlinLogging.logger {}

    // Providers will be created on demand since they require suspend calls
    private var simpleProvider: LlmModelProvider? = null
    private var complexProvider: LlmModelProvider? = null
    private var finalizationProvider: LlmModelProvider? = null

    /**
     * Process a query with a clearly defined purpose
     *
     * @param query User query
     * @param modelType Purpose of the query (SIMPLE, COMPLEX, FINALIZATION)
     * @param systemPrompt Optional system prompt to control model behavior
     * @return Language model response
     */
    suspend fun processQuery(
        query: String,
        modelType: ModelType,
        systemPrompt: String? = null,
    ): LlmResponse {
        val startTime = Instant.now()

        logger.info { "Processing query with selected model provider" }

        try {
            // Select provider based on purpose
            val provider = selectProvider(modelType)
            logger.info { "Selected provider ${provider.getName()}" }

            // Check provider availability
            if (!provider.isAvailable()) {
                logger.error { "Selected provider ${provider.getName()} is not available" }
                throw RuntimeException("No available language model provider")
            }

            // Process query with the appropriate provider
            val options =
                if (systemPrompt != null) {
                    mapOf("system_prompt" to systemPrompt)
                } else {
                    emptyMap()
                }

            val response = provider.processQuery(query, options)

            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.info {
                "Query processed by ${provider.getName()} (${modelType.name}) in $duration ms.\n" +
                    "Response: $response"
            }

            // Add provider information to the response
            return response.copy(
                answer =
                    "${response.answer}\n\n[Provider: ${provider.getName()} | Model: ${provider.getModel()}]",
            )
        } catch (e: Exception) {
            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.error(e) { "Error processing query after $duration ms: ${e.message}" }
            throw e
        }
    }

    /**
     * Process a simple query - quick interaction with indexing and RAG
     */
    suspend fun processSimpleQuery(
        query: String,
        systemPrompt: String? = null,
    ): LlmResponse = processQuery(query, ModelType.SIMPLE, systemPrompt)

    /**
     * Process a complex query - RAG for completion before finalization
     */
    suspend fun processComplexQuery(
        query: String,
        systemPrompt: String? = null,
    ): LlmResponse = processQuery(query, ModelType.COMPLEX, systemPrompt)

    /**
     * Process a finalization query - final request with merged data
     */
    suspend fun processFinalizationQuery(
        query: String,
        systemPrompt: String? = null,
    ): LlmResponse = processQuery(query, ModelType.FINALIZER, systemPrompt)

    /**
     * Select the appropriate provider based on query purpose
     */
    private suspend fun selectProvider(purpose: ModelType): LlmModelProvider =
        when (purpose) {
            ModelType.SIMPLE -> simpleProvider ?: createSimpleProvider().also { simpleProvider = it }
            ModelType.COMPLEX -> complexProvider ?: createComplexProvider().also { complexProvider = it }
            ModelType.FINALIZER -> finalizationProvider ?: createFinalizationProvider().also { finalizationProvider = it }
            else -> error("Invalid model type: $purpose")
        }

    /**
     * Create a provider for simple queries
     */
    private suspend fun createSimpleProvider(): LlmModelProvider {
        val (modelType, modelName) = settingService.getModelSimple()
        logger.info { "Creating simple provider with model: $modelType - $modelName" }
        return getModelProvider(ModelType.SIMPLE)
    }

    /**
     * Create a provider for complex queries
     */
    private suspend fun createComplexProvider(): LlmModelProvider {
        val (modelType, modelName) = settingService.getModelComplex()
        logger.info { "Creating complex provider with model: $modelType - $modelName" }
        return getModelProvider(ModelType.COMPLEX)
    }

    /**
     * Create a provider for finalization queries
     */
    private suspend fun createFinalizationProvider(): LlmModelProvider {
        val (modelType, modelName) = settingService.getModelFinalizing()
        logger.info { "Creating finalization provider with model: $modelType - $modelName" }
        return getModelProvider(ModelType.FINALIZER)
    }

    /**
     * Get a model provider by type
     */
    private fun getModelProvider(type: ModelType): LlmModelProvider =
        externalModelService.getModelProvider(type)
            ?: throw RuntimeException("No provider available for type: $type")
}
