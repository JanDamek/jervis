package com.jervis.service.gateway.processing

import com.fasterxml.jackson.databind.ObjectMapper
import com.jervis.domain.model.ModelProvider
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Simple JSON parser that converts JSON string directly to target class.
 * No validation, no fallbacks - just direct conversion.
 */
@Service
class JsonParser {
    private val logger = KotlinLogging.logger {}

    private val objectMapper =
        ObjectMapper().apply {
            // Allow extra fields - no strict validation
            configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // Allow missing fields
            configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
            // Allow single values to be treated as single-element arrays
            configure(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
        }

    /**
     * Cleans LLM response by removing markdown code blocks and other formatting.
     * Handles common formatting issues where LLMs wrap JSON in ```json...``` blocks.
     * Validates that the response appears to be JSON format.
     */
    private fun cleanJsonResponse(response: String): String {
        var cleaned = response.trim()

        // Remove markdown code blocks (```json...``` or ```...```)
        if (cleaned.startsWith("```")) {
            // Find the first newline after opening ```
            val firstNewline = cleaned.indexOf('\n')
            if (firstNewline != -1) {
                cleaned = cleaned.substring(firstNewline + 1)
            }

            // Remove closing ```
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length - 3).trim()
            }
        }

        // Additional cleanup for common formatting issues
        cleaned = cleaned.trim()

        // Validate that the cleaned response looks like JSON
        if (!cleaned.startsWith("{") && !cleaned.startsWith("[")) {
            throw IllegalStateException(
                "LLM returned non-JSON response. Expected JSON format but got plain text starting with: " +
                "${cleaned.take(100)}..."
            )
        }

        return cleaned
    }

    /**
     * Simply converts JSON string to target class without any validation.
     * Handles both single objects and collections properly.
     * Automatically strips markdown code blocks if present.
     */
    fun <T : Any> validateAndParse(
        rawResponse: String,
        responseSchema: T,
        provider: ModelProvider,
        model: String,
    ): T {
        return try {
            val trimmedResponse = cleanJsonResponse(rawResponse.trim())

            // If responseSchema is a collection, parse as array and return the same type
            when (responseSchema) {
                is Collection<*> -> {
                    if (responseSchema.isEmpty()) {
                        // Empty collection - can't determine element type, return as-is
                        @Suppress("UNCHECKED_CAST")
                        return responseSchema as T
                    }

                    // Get the element type from the first element
                    val elementType = responseSchema.first()!!::class.java

                    // Use ArrayList to avoid singleton collection deserialization issues
                    val listType =
                        objectMapper.typeFactory.constructCollectionType(
                            ArrayList::class.java,
                            elementType,
                        )

                    @Suppress("UNCHECKED_CAST")
                    objectMapper.readValue(trimmedResponse, listType) as T
                }

                else -> {
                    // Single object parsing
                    objectMapper.readValue(trimmedResponse, responseSchema::class.java)
                }
            }
        } catch (e: Exception) {
            logger.error { "JSON parsing failed for $provider/$model: ${e.message}" }
            logger.error { "Raw response: $rawResponse" }
            throw e
        }
    }
}
