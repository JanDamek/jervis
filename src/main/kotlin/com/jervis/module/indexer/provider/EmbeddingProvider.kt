package com.jervis.module.indexer.provider

/**
 * Interface for embedding providers.
 * This interface defines the contract for generating embeddings from text.
 */
interface EmbeddingProvider {
    /**
     * Get the dimension of the embeddings produced by this provider.
     * This is determined during initialization.
     *
     * @return The dimension of the embeddings
     */
    fun getDimension(): Int

    /**
     * Generate an embedding for the given text.
     *
     * @param text The text to generate an embedding for
     * @return The embedding as a list of floats
     */
    fun predict(text: String): List<Float>

    /**
     * Generate embeddings for a list of texts.
     *
     * @param texts The list of texts to generate embeddings for
     * @return A list of embeddings, one for each input text
     */
    fun predict(texts: List<String>): List<List<Float>> {
        // Default implementation calls predict for each text
        return texts.map { predict(it) }
    }
}