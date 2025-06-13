package com.jervis.module.indexer.tokenizer

import org.springframework.stereotype.Component
import mu.KotlinLogging

/**
 * Factory for creating and managing tokenizers.
 * This class selects the appropriate tokenizer based on availability.
 */
@Component
class TokenizerFactory(
    private val huggingFaceTokenizer: HuggingFaceTokenizerImpl,
    private val whitespaceTokenizer: WhitespaceTokenizer
) {
    private val logger = KotlinLogging.logger {}
    
    /**
     * Get the best available tokenizer
     *
     * @return The best available tokenizer implementation
     */
    fun getTokenizer(): Tokenizer {
        return if (huggingFaceTokenizer.isAvailable()) {
            logger.debug { "Using HuggingFace tokenizer" }
            huggingFaceTokenizer
        } else {
            logger.debug { "Using fallback WhitespaceTokenizer" }
            whitespaceTokenizer
        }
    }
}