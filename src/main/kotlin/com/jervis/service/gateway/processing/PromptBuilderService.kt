package com.jervis.service.gateway.processing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.jervis.configuration.prompts.PromptConfigBase
import org.springframework.stereotype.Service

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
     * Builds the complete user prompt by applying template replacement, mapping values, language instructions, and JSON instructions.
     * Automatically appends {userPrompt} placeholder if missing from the template.
     * Automatically prepends {stepContext} placeholder if missing from the template.
     */
    fun buildUserPrompt(
        prompt: PromptConfigBase,
        mappingValues: Map<String, String>,
        outputLanguage: String?,
        responseSchema: Any?,
    ): String {
        val userPromptTemplate: String =
            prompt.userPrompt?.let { template ->
                if (STEP_CONTEXT_ in template) {
                    template
                } else {
                    "=== PREVIOUS STEP CONTEXT ===\n" +
                        "$STEP_CONTEXT_\n" +
                        "=== END CONTEXT ===\n\n" +
                        template
                }
            } ?: (
                "=== PREVIOUS STEP CONTEXT ===\n" +
                    "$STEP_CONTEXT_\n" +
                    "=== END CONTEXT ===\n\n"
            )

        val mappedPrompt = applyMappingValues(userPromptTemplate, mappingValues)
        val languagePrompt = appendLanguageInstruction(mappedPrompt, outputLanguage)
        return appendJsonModeInstructions(languagePrompt, responseSchema)
    }

    /**
     * Builds the complete system prompt by applying mapping values only.
     * JSON mode instructions are now handled in userPrompt.
     */
    fun buildSystemPrompt(
        prompt: PromptConfigBase,
        mappingValues: Map<String, String>,
    ): String = applyMappingValues(prompt.systemPrompt, mappingValues)

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
     * Appends language instruction to the prompt if the output language is specified.
     */
    private fun appendLanguageInstruction(
        prompt: String,
        outputLanguage: String?,
    ): String =
        outputLanguage
            ?.takeUnless { it.isBlank() }
            ?.let {
                "$prompt\nPlease respond in language: $it\n"
            }
            ?: prompt

    /**
     * Appends JSON mode instructions and example format if JSON mode is enabled.
     * Uses ObjectMapper with pretty printing for better LLM readability.
     */
    private fun appendJsonModeInstructions(
        systemPrompt: String,
        responseSchema: Any?,
    ): String =
        when (responseSchema) {
            null ->
                error {
                    "responseSchema cannot be null - JSON mode is always enabled."
                }

            else -> {
                val exampleJson = serializeToJsonExample(responseSchema)
                systemPrompt + buildStrictJsonModeInstruction(exampleJson)
            }
        }

    /**
     * Serializes response schema to a pretty-formatted JSON string using ObjectMapper.
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
     * Builds ultra-simplified JSON mode instruction - optimized for minimal token usage.
     */
    private fun buildStrictJsonModeInstruction(exampleJson: String): String =
        """        
        
OUTPUT CONTRACT (STRICT):
- Return ONLY raw JSON that matches the schema below.
- NO markdown, NO code fences, NO comments, NO trailing text.
- If you cannot populate a field, set it to null (do not invent).
- Do not change field names or types. 
- In String fields of JSON always escape double quotes.
Response in JSON: 
$exampleJson
        """.trimIndent()
}
