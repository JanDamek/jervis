package com.jervis.configuration.prompts

/**
 * Extraction goal configuration for KoogQualifierAgent.
 * Defines schema and one-shot example for a specific task type.
 *
 * Architecture:
 * - Schema: What to extract (entities, edges, node key patterns)
 * - Example: One-shot learning - shows model EXACTLY what to do
 * - Model learns from example and applies a pattern to actual task content
 */
data class ExtractionGoal(
    /**
     * Schema definition - what to extract and how.
     * Examples: "Extract Concepts, Definitions, Rules", "Extract sender, recipients, subject"
     */
    val schema: String,
    /**
     * Example input for one-shot learning.
     * Should be representative of the actual data agent will process.
     */
    val exampleInput: String? = null,
    /**
     * Example output (tool calls) for one-shot learning.
     * Shows model the exact sequence of tools to call and arguments format.
     */
    val exampleOutput: String? = null,
)
