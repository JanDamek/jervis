package com.jervis.domain.llm

/**
 * Content block in Anthropic API response
 */
data class ContentBlock(
    val type: String,
    val text: String,
)
