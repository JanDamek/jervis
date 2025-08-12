package com.jervis.service.agent

import com.jervis.domain.rag.RagDocumentFilter
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.service.llm.LlmCoordinator
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.regex.Pattern

/**
 * Service for generating intelligent filters from natural language queries.
 * Uses LLM to analyze queries and extract filtering criteria.
 */
@Service
class FilterGeneratorService(
    private val llmCoordinator: LlmCoordinator
) {
    private val logger = KotlinLogging.logger {}
    
    /**
     * Generate a filter from a natural language query.
     * Falls back to basic keyword matching if LLM fails.
     */
    suspend fun generateFilter(query: String, projectId: ObjectId): RagDocumentFilter {
        return try {
            logger.debug { "Generating filter for query: $query" }
            
            // Try LLM-based filter generation first
            val llmFilter = generateLlmFilter(query, projectId)
            if (llmFilter != null) {
                logger.debug { "Generated LLM filter: $llmFilter" }
                llmFilter
            } else {
                // Fall back to basic filter generation
                logger.debug { "LLM filter generation failed, using basic filter" }
                createBasicFilter(query, projectId)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Error generating filter, falling back to basic filter: ${e.message}" }
            createBasicFilter(query, projectId)
        }
    }
    
    /**
     * Generate filter using LLM analysis of the query.
     */
    private suspend fun generateLlmFilter(query: String, projectId: ObjectId): RagDocumentFilter? {
        return try {
            val prompt = buildPrompt(query)
            val llmResponse = llmCoordinator.processQueryBlocking(prompt, "")
            
            if (llmResponse.answer.isNotEmpty()) {
                parseFilterResponse(llmResponse.answer, projectId)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "LLM filter generation failed: ${e.message}" }
            null
        }
    }
    
    /**
     * Build prompt for LLM to analyze the query and extract filter criteria.
     */
    private fun buildPrompt(query: String): String {
        return """
            Analyze the following search query and extract filtering criteria for document search.
            
            Query: "$query"
            
            Available document types: CODE, TEXT, MEETING, NOTE, GIT_HISTORY, DEPENDENCY, TODO, TODO_DEPENDENCY, TODO_DEPENDENCY_DESCRIPTION, DEPENDENCY_DESCRIPTION, CLASS_SUMMARY, ACTION, DECISION, PLAN
            
            Available source types: LLM, FILE, GIT, ANALYSIS, CLASS, AGENT
            
            Extract the following information if present in the query:
            1. Document type (from the list above)
            2. Time period (in days ago, e.g., "last week" = 7 days, "yesterday" = 1 day, "last month" = 30 days)
            3. Source type (from the list above)
            4. Any other relevant criteria
            
            Respond in the following JSON format:
            {
                "documentType": "TYPE_NAME or null",
                "daysAgo": number or null,
                "sourceType": "SOURCE_NAME or null",
                "keywords": ["keyword1", "keyword2"]
            }
            
            Examples:
            - "najdi meetingy z minulého týdne" -> {"documentType": "MEETING", "daysAgo": 7, "sourceType": null, "keywords": ["meetingy"]}
            - "ukař mi kód ze včerejška" -> {"documentType": "CODE", "daysAgo": 1, "sourceType": null, "keywords": ["kód"]}
            - "todo úkoly z posledního měsíce" -> {"documentType": "TODO", "daysAgo": 30, "sourceType": null, "keywords": ["todo", "úkoly"]}
            - "git historie z včerejška" -> {"documentType": "GIT_HISTORY", "daysAgo": 1, "sourceType": "GIT", "keywords": ["git", "historie"]}
            
            Only respond with the JSON, no additional text.
        """.trimIndent()
    }
    
    /**
     * Parse LLM response and create RagDocumentFilter.
     */
    private fun parseFilterResponse(response: String, projectId: ObjectId): RagDocumentFilter? {
        return try {
            logger.debug { "Parsing LLM response: $response" }
            
            // Extract JSON from response
            val jsonMatch = Pattern.compile("\\{[^}]*\\}").matcher(response)
            if (!jsonMatch.find()) {
                logger.warn { "No JSON found in LLM response" }
                return null
            }
            
            val jsonStr = jsonMatch.group()
            logger.debug { "Extracted JSON: $jsonStr" }
            
            // Simple JSON parsing (could use Jackson for more robust parsing)
            val documentType = extractJsonValue(jsonStr, "documentType")?.let { type ->
                if (type != "null") {
                    try {
                        RagDocumentType.valueOf(type)
                    } catch (e: IllegalArgumentException) {
                        logger.warn { "Invalid document type: $type" }
                        null
                    }
                } else null
            }
            
            val daysAgo = extractJsonValue(jsonStr, "daysAgo")?.let { days ->
                if (days != "null") {
                    try {
                        days.toInt()
                    } catch (e: NumberFormatException) {
                        logger.warn { "Invalid days ago: $days" }
                        null
                    }
                } else null
            }
            
            val sourceType = extractJsonValue(jsonStr, "sourceType")?.let { type ->
                if (type != "null") {
                    try {
                        RagSourceType.valueOf(type)
                    } catch (e: IllegalArgumentException) {
                        logger.warn { "Invalid source type: $type" }
                        null
                    }
                } else null
            }
            
            // Create filter based on extracted criteria
            val filter = if (daysAgo != null) {
                RagDocumentFilter.fromTimePeriod(projectId, daysAgo, documentType)
            } else {
                RagDocumentFilter(
                    projectId = projectId,
                    documentType = documentType,
                    ragSourceType = sourceType
                )
            }
            
            logger.debug { "Created filter: $filter" }
            filter
            
        } catch (e: Exception) {
            logger.warn(e) { "Error parsing LLM response: ${e.message}" }
            null
        }
    }
    
    /**
     * Extract value from simple JSON string.
     */
    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = Pattern.compile("\"$key\"\\s*:\\s*\"?([^,}\"]+)\"?")
        val matcher = pattern.matcher(json)
        return if (matcher.find()) {
            matcher.group(1).trim()
        } else null
    }
    
    /**
     * Create basic filter using keyword matching as fallback.
     */
    private fun createBasicFilter(query: String, projectId: ObjectId): RagDocumentFilter {
        val lowerQuery = query.lowercase()
        
        // Detect document type from keywords
        val documentType = when {
            lowerQuery.contains("meeting") || lowerQuery.contains("schůzka") || lowerQuery.contains("meetingy") -> RagDocumentType.MEETING
            lowerQuery.contains("code") || lowerQuery.contains("kód") || lowerQuery.contains("kod") -> RagDocumentType.CODE
            lowerQuery.contains("todo") || lowerQuery.contains("úkol") || lowerQuery.contains("ukol") -> RagDocumentType.UNKNOWN
            lowerQuery.contains("git") || lowerQuery.contains("historie") -> RagDocumentType.GIT_HISTORY
            lowerQuery.contains("note") || lowerQuery.contains("poznámka") || lowerQuery.contains("poznamka") -> RagDocumentType.NOTE
            lowerQuery.contains("decision") || lowerQuery.contains("rozhodnutí") || lowerQuery.contains("rozhodnuti") -> RagDocumentType.DECISION
            lowerQuery.contains("plan") || lowerQuery.contains("plán") -> RagDocumentType.PLAN
            else -> null
        }
        
        // Detect time period from keywords
        val daysAgo = when {
            lowerQuery.contains("včera") || lowerQuery.contains("vcera") || lowerQuery.contains("yesterday") -> 1
            lowerQuery.contains("minulý týden") || lowerQuery.contains("minuly tyden") || lowerQuery.contains("last week") -> 7
            lowerQuery.contains("minulý měsíc") || lowerQuery.contains("minuly mesic") || lowerQuery.contains("last month") -> 30
            lowerQuery.contains("dnes") || lowerQuery.contains("today") -> 0
            else -> null
        }
        
        // Detect source type from keywords
        val sourceType = when {
            lowerQuery.contains("git") -> RagSourceType.GIT
            lowerQuery.contains("file") || lowerQuery.contains("soubor") -> RagSourceType.FILE
            lowerQuery.contains("agent") -> RagSourceType.AGENT
            lowerQuery.contains("analysis") || lowerQuery.contains("analýza") || lowerQuery.contains("analyza") -> RagSourceType.ANALYSIS
            else -> null
        }
        
        val filter = if (daysAgo != null) {
            RagDocumentFilter.fromTimePeriod(projectId, daysAgo, documentType)
        } else {
            RagDocumentFilter(
                projectId = projectId,
                documentType = documentType,
                ragSourceType = sourceType
            )
        }
        
        logger.debug { "Created basic filter: $filter" }
        return filter
    }
}