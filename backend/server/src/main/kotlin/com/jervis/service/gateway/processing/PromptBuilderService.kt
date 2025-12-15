package com.jervis.service.gateway.processing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.jervis.configuration.prompts.PromptConfig
import org.springframework.stereotype.Service

/**
 * Service responsible for building system and user prompts for LLM requests.
 * Uses ObjectMapper with pretty printing for JSON example generation for better LLM readability.
 * Provides strict JSON validation preparation with enhanced formatting.
 */
@Service
class PromptBuilderService {
    private val prettyObjectMapper =
        ObjectMapper().apply {
            enable(SerializationFeature.INDENT_OUTPUT)
            enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        }

    /**
     * Builds the complete user prompt by applying template replacement, mapping values, language instructions, and JSON instructions.
     * Uses only placeholders explicitly defined in the YAML prompt configuration.
     */
    fun buildUserPrompt(
        prompt: PromptConfig,
        mappingValues: Map<String, String>,
        outputLanguage: String?,
        responseSchema: Any?,
    ): String {
        val userPromptTemplate: String = prompt.userPrompt ?: ""
        val mappedPrompt = applyMappingValues(userPromptTemplate, mappingValues)
        val languagePrompt = appendLanguageInstruction(mappedPrompt, outputLanguage)
        return appendJsonModeInstructions(languagePrompt, responseSchema)
    }

    /**
     * Builds the complete system prompt by applying mapping values only.
     * JSON mode instructions are now handled in userPrompt.
     */
    fun buildSystemPrompt(
        prompt: PromptConfig,
        mappingValues: Map<String, String>,
    ): String = applyMappingValues(prompt.systemPrompt, mappingValues)

    /**
     * Render arbitrary template string with placeholders using provided mapping values.
     * Placeholders syntax: {placeholder}
     * - Fail-fast: throws IllegalStateException if any placeholder in template is missing in mapping.
     * - No escaping/quoting is performed. The template is responsible for quotes around string values.
     */
    fun render(
        template: String,
        mappingValues: Map<String, String>,
    ): String = applyMappingValues(template, mappingValues)

    /**
     * Render template using values taken from a data object (converted to Map) merged with extra mapping.
     * - Object properties are converted to strings using toString(); null becomes literal "null".
     * - extraMapping overrides object-derived values when keys overlap.
     * - Fail-fast when the template contains a placeholder without a provided value.
     * - The template must control quoting: put quotes in the template when you expect a JSON string.
     */
    fun renderFromObject(
        template: String,
        data: Any,
        extraMapping: Map<String, String> = emptyMap(),
    ): String {
        val baseMap: Map<String, String> = objectToStringMap(data)
        val merged = baseMap + extraMapping
        return applyMappingValues(template, merged)
    }

    private fun objectToStringMap(data: Any): Map<String, String> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val raw: Map<String, Any?> =
                prettyObjectMapper.convertValue(data, Map::class.java) as Map<String, Any?>
            raw.mapValues { (_, v) -> v?.toString() ?: "null" }
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot convert object to mapping for templating: ${data::class.simpleName}", e)
        }
    }

    /**
     * Applies mapping value replacements to the prompt template.
     * Validates placeholders BEFORE replacement to avoid false positives from content.
     */
    private fun applyMappingValues(
        promptTemplate: String,
        mappingValues: Map<String, String>,
    ): String {
        validatePlaceholdersBeforeReplacement(promptTemplate, mappingValues)

        val result =
            mappingValues.entries.fold(promptTemplate) { acc, (key, value) ->
                acc.replace("{$key}", value)
            }
        return result
    }

    /**
     * Validates that all placeholders in the template have corresponding values.
     * Extracts placeholders from the template BEFORE replacement to avoid confusion with content.
     * Only matches valid placeholder identifiers (alphanumeric and underscores), not code or JSON structures.
     * Throws IllegalStateException if any required placeholders are missing values.
     */
    private fun validatePlaceholdersBeforeReplacement(
        promptTemplate: String,
        mappingValues: Map<String, String>,
    ) {
        val placeholderPattern = Regex("""\{([a-zA-Z_][a-zA-Z0-9_]*)\}""")
        val templatePlaceholders =
            placeholderPattern
                .findAll(promptTemplate)
                .map { it.groupValues[1] }
                .toSet()

        val missingPlaceholders =
            templatePlaceholders.filter { placeholder ->
                !mappingValues.containsKey(placeholder)
            }

        if (missingPlaceholders.isNotEmpty()) {
            throw IllegalStateException(
                "Missing values for placeholders: ${missingPlaceholders.joinToString(", ") { "{$it}" }}",
            )
        }
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
    fun appendJsonModeInstructions(
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
