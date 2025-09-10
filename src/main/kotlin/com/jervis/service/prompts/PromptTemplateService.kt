package com.jervis.service.prompts

import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.regex.Pattern

@Service
class PromptTemplateService {
    private val logger = KotlinLogging.logger {}

    companion object {
        private val TEMPLATE_VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}")
    }

    /**
     * Composes a final prompt by replacing template variables with provided values
     * @param template The prompt template containing ${key} placeholders
     * @param parameters Map of key-value pairs for substitution
     * @return The composed prompt with all placeholders replaced
     */
    suspend fun composePrompt(
        template: String,
        parameters: Map<String, String>,
    ): String {
        var result = template
        val matcher = TEMPLATE_VARIABLE_PATTERN.matcher(template)
        val missingParameters = mutableListOf<String>()

        // Find all template variables and replace them
        while (matcher.find()) {
            val variableName = matcher.group(1)
            val value = parameters[variableName]

            if (value != null) {
                result = result.replace("\${$variableName}", value)
                logger.debug { "Replaced template variable '$variableName' with value" }
            } else {
                missingParameters.add(variableName)
                logger.warn { "Missing value for template variable: $variableName" }
            }
        }

        if (missingParameters.isNotEmpty()) {
            throw IllegalArgumentException("Missing values for template variables: ${missingParameters.joinToString(", ")}")
        }

        return result
    }

    /**
     * Extracts all template variable names from a prompt template
     * @param template The prompt template to analyze
     * @return Set of variable names found in the template
     */
    suspend fun extractTemplateVariables(template: String): Set<String> {
        val variables = mutableSetOf<String>()
        val matcher = TEMPLATE_VARIABLE_PATTERN.matcher(template)

        while (matcher.find()) {
            val variableName = matcher.group(1)
            variables.add(variableName)
        }

        logger.debug { "Extracted ${variables.size} template variables: $variables" }
        return variables
    }

    /**
     * Validates that all required template variables have values
     * @param template The prompt template to validate
     * @param parameters Map of provided parameters
     * @return List of missing parameter names (empty if all are provided)
     */
    suspend fun validateTemplateParameters(
        template: String,
        parameters: Map<String, String>,
    ): List<String> {
        val requiredVariables = extractTemplateVariables(template)
        val missingParameters =
            requiredVariables.filter { variableName ->
                !parameters.containsKey(variableName) || parameters[variableName].isNullOrBlank()
            }

        logger.debug { "Validation result: ${missingParameters.size} missing parameters out of ${requiredVariables.size} required" }
        return missingParameters
    }

    /**
     * Creates a preview of the prompt with placeholder indicators for missing values
     * @param template The prompt template
     * @param parameters Map of available parameters
     * @return Preview string with available substitutions and placeholder indicators
     */
    suspend fun createPromptPreview(
        template: String,
        parameters: Map<String, String>,
    ): String {
        var result = template
        val matcher = TEMPLATE_VARIABLE_PATTERN.matcher(template)

        while (matcher.find()) {
            val variableName = matcher.group(1)
            val value = parameters[variableName]

            if (value != null && value.isNotBlank()) {
                result = result.replace("\${$variableName}", value)
            } else {
                result = result.replace("\${$variableName}", "[MISSING: $variableName]")
            }
        }

        return result
    }
}
