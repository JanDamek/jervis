package com.jervis.service.llm.anthropic

import com.jervis.domain.llm.ContentBlock
import com.jervis.domain.llm.Usage

/**
 * Anthropic API response
 */
data class AnthropicResponse(
    val id: String,
    val model: String,
    val content: List<ContentBlock>,
    val usage: Usage,
)
