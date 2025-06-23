package com.jervis.module.llm

import com.jervis.module.llmcoordinator.LlmResponse

/**
 * Interface for language model providers.
 * This interface defines the contract for different language model providers
 * that can be used by the application to process queries.
 */
interface LlmModelProvider {
    /**
     * Process a query using the language model.
     *
     * @param query The user query
     * @param options Additional options for processing
     * @return The language model response
     */
    suspend fun processQuery(
        query: String,
        options: Map<String, Any> = emptyMap(),
    ): LlmResponse

    /**
     * Check if the provider is available.
     *
     * @return True if the provider is available, false otherwise
     */
    suspend fun isAvailable(): Boolean

    /**
     * Get the name of the provider.
     *
     * @return The name of the provider
     */
    fun getName(): String

    /**
     * Get the model used by this provider.
     *
     * @return The model name
     */
    fun getModel(): String

    /**
     * Get the type of the provider.
     *
     * @return The provider type
     */
    fun getType(): ModelProviderType
}
