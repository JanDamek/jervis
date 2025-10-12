package com.jervis.service.gateway.processing

import com.fasterxml.jackson.databind.ObjectMapper
import com.jervis.domain.model.ModelProvider
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Data class to hold both think content and parsed JSON result
 */
data class ParsedResponse<T>(
    val thinkContent: String?,
    val result: T,
)

@Service
class JsonParser {
    private val logger = KotlinLogging.logger {}

    private val objectMapper =
        ObjectMapper().apply {
            configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
            configure(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
        }

    /**
     * Extracts think content from <think></think> tags
     */
    private fun extractThinkContent(rawResponse: String): String? {
        val thinkStartTag = "<think>"
        val thinkEndTag = "</think>"

        val startIndex = rawResponse.indexOf(thinkStartTag)
        if (startIndex == -1) return null

        val contentStart = startIndex + thinkStartTag.length
        val endIndex = rawResponse.indexOf(thinkEndTag, contentStart)
        if (endIndex == -1) return null

        return rawResponse.substring(contentStart, endIndex).trim()
    }

    /**
     * Removes think content from response to get clean content for JSON parsing
     */
    private fun removeThinkContent(rawResponse: String): String {
        val thinkStartTag = "<think>"
        val thinkEndTag = "</think>"

        var cleaned = rawResponse
        var startIndex = cleaned.indexOf(thinkStartTag)

        while (startIndex != -1) {
            val endIndex = cleaned.indexOf(thinkEndTag, startIndex)
            if (endIndex != -1) {
                // Remove the entire think block including tags
                cleaned = cleaned.substring(0, startIndex) + cleaned.substring(endIndex + thinkEndTag.length)
                startIndex = cleaned.indexOf(thinkStartTag)
            } else {
                // Malformed think tag, just remove the start tag
                cleaned = cleaned.substring(0, startIndex) + cleaned.substring(startIndex + thinkStartTag.length)
                break
            }
        }

        return cleaned.trim()
    }

    // Nová metoda pro nalezení finálního JSON bloku
    private fun findFinalJsonBlock(rawResponse: String): String {
        val delimiter = "**START_JSON_OUTPUT**"
        val startIndex = rawResponse.indexOf(delimiter)

        return if (startIndex != -1) {
            // Pokud oddělovač existuje, vrátí text po něm
            rawResponse.substring(startIndex + delimiter.length).trim()
        } else {
            // Jinak vrátí celý text a spoléhá na existující logiku čištění
            rawResponse.trim()
        }
    }

    private fun cleanJsonResponse(response: String): String {
        var cleaned = response.trim()

        // Odstranění markdownu, pokud je stále přítomen
        if (cleaned.startsWith("```")) {
            val firstNewline = cleaned.indexOf('\n')
            if (firstNewline != -1) {
                cleaned = cleaned.substring(firstNewline + 1)
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length - 3).trim()
            }
        }

        // Vyčištění textu před parsováním - handle both objects {} and arrays []
        val objectStart = cleaned.indexOf('{')
        val objectEnd = cleaned.lastIndexOf('}')
        val arrayStart = cleaned.indexOf('[')
        val arrayEnd = cleaned.lastIndexOf(']')

        // Determine if we have an object or array and which comes first
        val jsonStart: Int
        val jsonEnd: Int

        when {
            objectStart != -1 && (arrayStart == -1 || objectStart < arrayStart) -> {
                // Object comes first or only object exists
                jsonStart = objectStart
                jsonEnd = objectEnd
                if (jsonEnd == -1) {
                    throw IllegalStateException("LLM returned malformed JSON object - missing closing brace.")
                }
            }

            arrayStart != -1 -> {
                // Array comes first or only array exists
                jsonStart = arrayStart
                jsonEnd = arrayEnd
                if (jsonEnd == -1) {
                    throw IllegalStateException("LLM returned malformed JSON array - missing closing bracket.")
                }
            }

            else -> {
                throw IllegalStateException("LLM returned non-JSON response - no valid JSON found.")
            }
        }

        // Extrahování pouze JSON bloku
        val jsonOnly = cleaned.substring(jsonStart, jsonEnd + 1)

        // Sanitizace speciálních znaků
        return sanitizeControlCharacters(jsonOnly)
    }

    private fun sanitizeControlCharacters(json: String): String {
        val result = StringBuilder()
        var i = 0
        var insideString = false
        var escapeNext = false

        while (i < json.length) {
            val char = json[i]

            when {
                escapeNext -> {
                    result.append(char)
                    escapeNext = false
                }

                char == '\\' && insideString -> {
                    result.append(char)
                    escapeNext = true
                }

                char == '"' -> {
                    result.append(char)
                    insideString = !insideString
                }

                insideString && char.code < 32 -> { // Uvnitř řetězce, sanitizuje řídící znaky
                    when (char) {
                        '\n' -> result.append("\\n")
                        '\r' -> result.append("\\r")
                        '\t' -> result.append("\\t")
                        '\b' -> result.append("\\b")
                        '\u000C' -> result.append("\\f")
                        else -> result.append("\\u").append(String.format("%04x", char.code))
                    }
                }

                else -> {
                    result.append(char)
                }
            }
            i++
        }
        return result.toString()
    }

    /**
     * New method that extracts think content and parses JSON
     */
    fun <T : Any> validateAndParseWithThink(
        rawResponse: String,
        responseSchema: T,
        provider: ModelProvider,
        model: String,
    ): ParsedResponse<T> {
        try {
            // Fáze 1: Extrakce think content
            val thinkContent = extractThinkContent(rawResponse)

            // Fáze 2: Odstranění think content pro čištění JSON
            val responseWithoutThink = removeThinkContent(rawResponse)

            // Fáze 3: Nalezení a extrakce JSON bloku
            val finalJson = findFinalJsonBlock(responseWithoutThink)

            // Fáze 4: Vyčištění a parsování
            val trimmedResponse = cleanJsonResponse(finalJson)

            // Pokus o parsování
            val parsedResult =
                when (responseSchema) {
                    is Collection<*> -> {
                        if (responseSchema.isEmpty()) {
                            @Suppress("UNCHECKED_CAST")
                            responseSchema as T
                        } else {
                            val elementType = responseSchema.first()!!::class.java
                            val listType =
                                objectMapper.typeFactory.constructCollectionType(ArrayList::class.java, elementType)
                            @Suppress("UNCHECKED_CAST")
                            objectMapper.readValue(trimmedResponse, listType) as T
                        }
                    }

                    else -> {
                        objectMapper.readValue(trimmedResponse, responseSchema::class.java)
                    }
                }

            return ParsedResponse(thinkContent, parsedResult)
        } catch (e: Exception) {
            logger.error { "JSON parsing with think extraction failed for $provider/$model: ${e.message}" }
            logger.error { "Raw response: $rawResponse" }
            throw e
        }
    }

    fun <T : Any> validateAndParse(
        rawResponse: String,
        responseSchema: T,
        provider: ModelProvider,
        model: String,
    ): T {
        try {
            // Fáze 1: Nalezení a extrakce JSON bloku
            val finalJson = findFinalJsonBlock(rawResponse)

            // Fáze 2: Vyčištění a parsování
            val trimmedResponse = cleanJsonResponse(finalJson)

            // Pokus o parsování
            return when (responseSchema) {
                is Collection<*> -> {
                    if (responseSchema.isEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        responseSchema as T
                    } else {
                        val elementType = responseSchema.first()!!::class.java
                        val listType =
                            objectMapper.typeFactory.constructCollectionType(ArrayList::class.java, elementType)
                        @Suppress("UNCHECKED_CAST")
                        objectMapper.readValue(trimmedResponse, listType) as T
                    }
                }

                else -> {
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
