package com.jervis.module.indexer.tokenizer

/**
 * Interface for tokenizers that convert text to tokens.
 * This abstraction allows for different tokenization strategies.
 */
interface Tokenizer {
    /**
     * Tokenize the given text into tokens
     *
     * @param text The text to tokenize
     * @return A list of tokens
     */
    fun tokenize(text: String): List<String>
    
    /**
     * Count the number of tokens in the given text
     *
     * @param text The text to count tokens in
     * @return The number of tokens
     */
    fun countTokens(text: String): Int
    
    /**
     * Get the maximum token limit for this tokenizer
     *
     * @return The maximum number of tokens supported
     */
    fun getMaxTokens(): Int
}