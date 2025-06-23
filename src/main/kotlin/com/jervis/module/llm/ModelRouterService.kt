package com.jervis.module.llm

import com.jervis.module.llmcoordinator.LlmResponse
import com.jervis.service.SettingService
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

    // Lazy initialization of providers for better performance
    private val simpleProvider by lazy { createSimpleProvider() }
    private val complexProvider by lazy { createComplexProvider() }
    private val finalizationProvider by lazy { createFinalizationProvider() }

    /**
     * Process a query with a clearly defined purpose
     *
     * @param query User query
     * @param purpose Purpose of the query (SIMPLE, COMPLEX, FINALIZATION)
     * @param systemPrompt Optional system prompt to control model behavior
     * @return Language model response
     */
    suspend fun processQuery(
        query: String,
        purpose: ProviderType,
        systemPrompt: String? = null,
    ): LlmResponse {
        val startTime = Instant.now()

        logger.info { "Processing query with purpose: $purpose" }

        try {
            // Select provider based on purpose
            val provider = selectProvider(purpose)
            logger.info { "Selected ${provider.getName()} provider for ${purpose.name} purpose" }

            // Check provider availability
            if (!provider.isAvailable()) {
                logger.error { "Provider ${provider.getName()} for purpose $purpose is not available" }
                throw RuntimeException("Required language model provider for $purpose is not available")
            }

            // Process query with the appropriate provider
            val options = if (systemPrompt != null) {
                mapOf("system_prompt" to systemPrompt)
            } else {
                emptyMap()
            }

            val response = provider.processQuery(query, options)

            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.info { "Query processed by ${provider.getName()} (${purpose.name}) in $duration ms" }

            // Add provider information to the response
            return response.copy(
                answer = "${response.answer}\n\n[Processed by: ${provider.getName()} (${purpose.name}) using ${provider.getModel()}]",
            )
        } catch (e: Exception) {
            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.error(e) { "Error processing query with purpose $purpose after $duration ms: ${e.message}" }
            throw e
        }
    }

    /**
     * Process a simple query - quick interaction with indexing and RAG
     */
    suspend fun processSimpleQuery(
        query: String,
        systemPrompt: String? = null,
    ): LlmResponse = processQuery(query, ProviderType.SIMPLE, systemPrompt)

    /**
     * Process a complex query - RAG for completion before finalization
     */
    suspend fun processComplexQuery(
        query: String,
        systemPrompt: String? = null,
    ): LlmResponse = processQuery(query, ProviderType.COMPLEX, systemPrompt)

    /**
     * Process a finalization query - final request with merged data
     */
    suspend fun processFinalizationQuery(
        query: String,
        systemPrompt: String? = null,
    ): LlmResponse = processQuery(query, ProviderType.FINALIZATION, systemPrompt)

    /**
     * Select the appropriate provider based on query purpose
     */
    private fun selectProvider(purpose: ProviderType): LlmModelProvider =
        when (purpose) {
            ProviderType.SIMPLE -> simpleProvider
            ProviderType.COMPLEX -> complexProvider
            ProviderType.FINALIZATION -> finalizationProvider
        }

    /**
     * Create a provider for simple queries
     */
    private fun createSimpleProvider(): LlmModelProvider {
        val (modelType, modelName) = settingService.getModelSimple()
        logger.info { "Creating simple provider with model: $modelType - $modelName" }
        return getModelProvider(ModelProviderType.SIMPLE)
    }

    /**
     * Create a provider for complex queries
     */
    private fun createComplexProvider(): LlmModelProvider {
        val (modelType, modelName) = settingService.getModelComplex()
        logger.info { "Creating complex provider with model: $modelType - $modelName" }
        return getModelProvider(ModelProviderType.COMPLEX)
    }

    /**
     * Create a provider for finalization queries
     */
    private fun createFinalizationProvider(): LlmModelProvider {
        val (modelType, modelName) = settingService.getModelFinalizing()
        logger.info { "Creating finalization provider with model: $modelType - $modelName" }
        return getModelProvider(ModelProviderType.FINALIZATION)
    }

    /**
     * Get a model provider by type
     */
    private fun getModelProvider(type: ModelProviderType): LlmModelProvider {
        return externalModelService.getModelProvider(type) 
            ?: throw RuntimeException("No provider available for type: $type")
    }
}
