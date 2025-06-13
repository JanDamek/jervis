package com.jervis.module.indexer.tokenizer

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * A simple tokenizer that splits text by whitespace.
 * This is used as a fallback when more sophisticated tokenizers are not available.
 */
@Component
class WhitespaceTokenizer(
    @Value("\${tokenizer.max-tokens:1024}") private val maxTokens: Int
) : Tokenizer {
    
    override fun tokenize(text: String): List<String> {
        return text.split(SPLIT_PATTERN)
            .filter { it.isNotBlank() }
    }
    
    override fun countTokens(text: String): Int {
        return tokenize(text).size
    }
    
    override fun getMaxTokens(): Int {
        return maxTokens
    }
    
    companion object {
        // Pattern to split on whitespace and punctuation
        private val SPLIT_PATTERN = Regex("[\\s\\p{Punct}]+")
    }
}