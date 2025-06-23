package com.jervis.module.indexer

/**
 * Enum representing supported multilingual embedding models.
 */
enum class EmbeddingModel {
    /**
     * Primary embedding model: intfloat/multilingual-e5-large
     * - Dimensions: 1024
     * - Max tokens: 512
     * - Tokenizer: XLM-RoBERTa
     * - Language support: 90+ languages (including Czech)
     * - DJL compatible (HuggingFace ModelZoo)
     */
    E5_MULTILINGUAL_LARGE,

    /**
     * Fallback embedding model: sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2
     * - Dimensions: 384
     * - Very low memory footprint
     * - Suitable for low-resource environments
     */
    PARAPHRASE_MULTILINGUAL_MINI_L12_V2
}