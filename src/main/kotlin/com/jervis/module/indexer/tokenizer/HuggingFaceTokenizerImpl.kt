package com.jervis.module.indexer.tokenizer

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.nio.file.Paths

/**
 * Implementation of Tokenizer using DJL's HuggingFaceTokenizer.
 * This tokenizer provides accurate token counting for embedding models.
 */
@Component
class HuggingFaceTokenizerImpl(
    @Value("\${tokenizer.model:gpt2}") private val tokenizerModel: String,
    @Value("\${tokenizer.max-tokens:1024}") private val maxTokens: Int
) : Tokenizer {
    private val logger = KotlinLogging.logger {}
    private var tokenizer: HuggingFaceTokenizer? = null

    @PostConstruct
    fun initialize() {
        try {
            logger.info { "Initializing HuggingFace tokenizer with model: $tokenizerModel" }
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerModel)
            logger.info { "HuggingFace tokenizer initialized successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize HuggingFace tokenizer: ${e.message}" }
            logger.warn { "Will fall back to WhitespaceTokenizer" }
        }
    }

    @PreDestroy
    fun cleanup() {
        try {
            logger.info { "Closing HuggingFace tokenizer" }
            tokenizer?.close()
            logger.info { "HuggingFace tokenizer closed successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Error closing HuggingFace tokenizer: ${e.message}" }
        }
    }

    override fun tokenize(text: String): List<String> {
        if (tokenizer == null) {
            throw IllegalStateException("HuggingFace tokenizer is not initialized")
        }
        
        return try {
            val encoding = tokenizer!!.encode(text)
            val tokens = encoding.tokens
            tokens.toList()
        } catch (e: Exception) {
            logger.error(e) { "Error tokenizing text with HuggingFace tokenizer: ${e.message}" }
            throw e
        }
    }

    override fun countTokens(text: String): Int {
        if (tokenizer == null) {
            throw IllegalStateException("HuggingFace tokenizer is not initialized")
        }
        
        return try {
            val encoding = tokenizer!!.encode(text)
            encoding.ids.size
        } catch (e: Exception) {
            logger.error(e) { "Error counting tokens with HuggingFace tokenizer: ${e.message}" }
            throw e
        }
    }

    override fun getMaxTokens(): Int {
        return maxTokens
    }
    
    /**
     * Check if the tokenizer is available
     *
     * @return True if the tokenizer is initialized and ready to use
     */
    fun isAvailable(): Boolean {
        return tokenizer != null
    }
}