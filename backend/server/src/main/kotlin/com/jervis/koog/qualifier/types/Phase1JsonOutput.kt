package com.jervis.koog.qualifier.types

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * Structured output for Phase 1 document intake and semantic segmentation.
 * LLM works with text, not numbers - keeps it simple.
 */
@Serializable
@LLMDescription("Document intake plan with semantic blocks")
data class Phase1JsonOutput(
    @LLMDescription("Brief 1-3 sentence summary of document content for base document node")
    val baseInfo: String,
    @LLMDescription(
        """List of semantic text blocks from the document.
        Each block should be 500-3000 tokens, semantically coherent.
        Copy exact text from document, do not summarize.""",
    )
    val blocks: List<String> = emptyList(),
)
