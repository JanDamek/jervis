package com.jervis.service.gateway.processing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.service.gateway.processing.domain.ParsedResponse
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class JsonParser {
    private val logger = KotlinLogging.logger {}

    // Use Jackson Kotlin module to properly deserialize Kotlin data classes (constructor params, default values)
    private val objectMapper =
        jacksonObjectMapper().apply {
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
                cleaned = cleaned.substring(0, startIndex) + cleaned.substring(endIndex + thinkEndTag.length)
                startIndex = cleaned.indexOf(thinkStartTag)
            } else {
                cleaned = cleaned.substring(0, startIndex) + cleaned.substring(startIndex + thinkStartTag.length)
                break
            }
        }

        return cleaned.trim()
    }

    private fun findFinalJsonBlock(rawResponse: String): String {
        val delimiter = "**START_JSON_OUTPUT**"
        val startIndex = rawResponse.indexOf(delimiter)

        return if (startIndex != -1) {
            rawResponse.substring(startIndex + delimiter.length).trim()
        } else {
            rawResponse.trim()
        }
    }

    private fun cleanJsonResponse(response: String): String {
        var cleaned = response.trim()

        if (cleaned.startsWith("```")) {
            val firstNewline = cleaned.indexOf('\n')
            if (firstNewline != -1) {
                cleaned = cleaned.substring(firstNewline + 1)
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length - 3).trim()
            }
        }

        val objectStart = cleaned.indexOf('{')
        val objectEnd = cleaned.lastIndexOf('}')
        val arrayStart = cleaned.indexOf('[')
        val arrayEnd = cleaned.lastIndexOf(']')

        val jsonStart: Int
        val jsonEnd: Int

        when {
            objectStart != -1 && (arrayStart == -1 || objectStart < arrayStart) -> {
                jsonStart = objectStart
                jsonEnd = objectEnd
                if (jsonEnd == -1) {
                    throw IllegalStateException("LLM returned malformed JSON object - missing closing brace.")
                }
            }

            arrayStart != -1 -> {
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

        val jsonOnly = cleaned.substring(jsonStart, jsonEnd + 1)

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

                insideString && char.code < 32 -> {
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

    fun <T : Any> validateAndParseWithThink(
        rawResponse: String,
        responseSchema: T,
        provider: ModelProviderEnum,
        model: String,
    ): ParsedResponse<T> {
        try {
            val thinkContent = extractThinkContent(rawResponse)
            val responseWithoutThink = removeThinkContent(rawResponse)
            val finalJson = findFinalJsonBlock(responseWithoutThink)
            val trimmedResponse = cleanJsonResponse(finalJson)

            val parsedResult =
                when (responseSchema) {
                    is Collection<*> -> {
                        if (responseSchema.isEmpty()) {
                            @Suppress("UNCHECKED_CAST")
                            responseSchema as T
                        } else {
                            val firstElement =
                                requireNotNull(responseSchema.firstOrNull()) {
                                    "Collection schema must have at least one element"
                                }
                            val elementType = firstElement::class.java
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
        provider: ModelProviderEnum,
        model: String,
    ): T {
        try {
            val finalJson = findFinalJsonBlock(rawResponse)
            val trimmedResponse = cleanJsonResponse(finalJson)

            return when (responseSchema) {
                is Collection<*> -> {
                    if (responseSchema.isEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        responseSchema as T
                    } else {
                        val firstElement =
                            requireNotNull(responseSchema.firstOrNull()) {
                                "Collection schema must have at least one element"
                            }
                        val elementType = firstElement::class.java
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
