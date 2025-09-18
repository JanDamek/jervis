package com.jervis.service.gateway.processing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.jervis.configuration.prompts.PromptConfig
import org.springframework.stereotype.Service

private const val USER_PROMPT_ = "{userPrompt}"
private const val STEP_CONTEXT_ = "{stepContext}"

/**
 * Service responsible for building system and user prompts for LLM requests.
 * Uses ObjectMapper with pretty printing for JSON example generation for better LLM readability.
 * Provides strict JSON validation preparation with enhanced formatting.
 */
@Service
class PromptBuilderService {
    private val prettyObjectMapper =
        ObjectMapper().apply {
            enable(SerializationFeature.INDENT_OUTPUT) // Pretty formatting for better LLM comprehension
            enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS) // Consistent field ordering
        }

    /**
     * Builds the complete user prompt by applying template replacement, mapping values, and language instructions.
     * Automatically appends {userPrompt} placeholder if missing from the template.
     * Automatically prepends {stepContext} placeholder if missing from the template.
     */
    fun buildUserPrompt(
        prompt: PromptConfig,
        mappingValues: Map<String, String>,
        userPrompt: String,
        outputLanguage: String?,
    ): String {
        val userPromptTemplate: String =
            prompt.userPrompt?.let { template ->
                // First handle stepContext - prepend if missing
                val templateWithStepContext =
                    if (STEP_CONTEXT_ in template) {
                        template
                    } else {
                        "{stepContext}\n\n$template"
                    }

                // Then handle userPrompt - append if missing
                if (USER_PROMPT_ in templateWithStepContext) {
                    templateWithStepContext
                } else {
                    "$templateWithStepContext\n{userPrompt}".trimStart()
                }
            } ?: "{stepContext}\n\n{userPrompt}"

        val templatedPrompt = userPromptTemplate.replace(USER_PROMPT_, userPrompt)
        val mappedPrompt = applyMappingValues(templatedPrompt, mappingValues)
        return appendLanguageInstruction(mappedPrompt, outputLanguage)
    }

    /**
     * Builds the complete system prompt by applying mapping values and JSON mode formatting if required.
     */
    fun buildSystemPrompt(
        prompt: PromptConfig,
        mappingValues: Map<String, String>,
        responseSchema: Any?,
    ): String {
        val mappedPrompt = applyMappingValues(prompt.systemPrompt, mappingValues)
        return appendJsonModeInstructions(mappedPrompt, prompt, responseSchema)
    }

    /**
     * Applies mapping value replacements to the prompt template.
     */
    private fun applyMappingValues(
        promptTemplate: String,
        mappingValues: Map<String, String>,
    ): String =
        mappingValues.entries.fold(promptTemplate) { acc, (key, value) ->
            acc.replace("{$key}", value)
        }

    /**
     * Appends language instruction to the prompt if output language is specified.
     */
    private fun appendLanguageInstruction(
        prompt: String,
        outputLanguage: String?,
    ): String =
        outputLanguage
            ?.takeUnless { it.isBlank() }
            ?.let { "$prompt\n\nPlease respond in language: $it" }
            ?: prompt

    /**
     * Appends JSON mode instructions and example format if JSON mode is enabled.
     * Uses ObjectMapper with pretty printing for better LLM readability.
     */
    private fun appendJsonModeInstructions(
        systemPrompt: String,
        prompt: PromptConfig,
        responseSchema: Any?,
    ): String =
        when (responseSchema) {
            null -> throw IllegalArgumentException(
                "responseSchema cannot be null - JSON mode is always enabled.",
            )

            else -> {
                val exampleJson = serializeToJsonExample(responseSchema)
                systemPrompt + buildStrictJsonModeInstruction(exampleJson)
            }
        }

    /**
     * Serializes response schema to pretty-formatted JSON string using ObjectMapper.
     * Uses pretty printing and consistent field ordering for better LLM comprehension.
     */
    private fun serializeToJsonExample(responseSchema: Any): String =
        try {
            prettyObjectMapper.writeValueAsString(responseSchema)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Cannot serialize response schema to JSON: ${responseSchema::class.simpleName}",
                e,
            )
        }

    /**
     * Builds ultra-strict JSON mode instruction text with pretty-formatted example.
     * Eliminates all fallback mechanisms - either perfect JSON or immediate failure.
     * Uses improved formatting for better LLM understanding.
     */
    private fun buildStrictJsonModeInstruction(exampleJson: String): String =
        """        
        CRITICAL JSON FORMAT REQUIREMENTS - NO FALLBACKS ALLOWED:
        - Return ONLY valid JSON in this EXACT structure
        - NO markdown formatting (```json, ```, etc.)
        - NO explanations, comments, or wrapper text
        - NO code blocks or additional formatting
        - Response must be syntactically perfect JSON
        - Any deviation will cause IMMEDIATE FAILURE
             
        VALIDATION RULES (ALL REQUIRED):
        ✓ Response starts with { or [
        ✓ Response ends with } or ]
        ✓ All strings properly quoted with double quotes
        ✓ Valid JSON syntax throughout
        ✓ No extra text or formatting
        ✓ Extra fields not allowed - causes immediate error
        ✓ Missing fields allowed if they can be null
        
        REQUIRED JSON STRUCTURE:
        $exampleJson
        
        FAILURE CONSEQUENCES:
        - Invalid JSON responses terminate the request immediately
        - No cleanup or retry attempts will be made
        - Error will be reported with full context for debugging
        """.trimIndent()
}
