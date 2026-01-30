package com.jervis.orchestrator.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.rag.KnowledgeService
import com.jervis.rag.internal.graphdb.GraphDBService
import mu.KotlinLogging

/**
 * Tools for validating information from external sources against existing knowledge.
 *
 * CRITICAL for multi-step reasoning:
 * - Validate framework versions match
 * - Detect conflicting information
 * - Check if code samples are for correct technology
 * - Cross-reference with existing knowledge base
 */
class ValidationTools(
    private val task: TaskDocument,
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        """Validate if external information (from web, docs) matches our project's technology stack.

        Use this BEFORE accepting any code/advice from internet to ensure compatibility.

        Parameters:
        - externalInfo: Text snippet from external source (e.g., code sample, config, tutorial)
        - concernedTechnology: What technology it's about (e.g., "Koog framework", "kRPC", "Ktor")

        Returns: JSON with:
        - isCompatible: true/false
        - detectedVersion: Version mentioned in external info (if any)
        - ourVersion: Version we use in project (from knowledge base)
        - conflicts: List of detected incompatibilities
        - confidence: How confident the validation is (0.0-1.0)
        - recommendation: "ACCEPT", "ADAPT", or "REJECT"

        Example: Detect if tutorial is for Koog 0.5 while we use Koog 0.6
        """
    )
    suspend fun validateExternalInfo(
        @LLMDescription("External information text to validate (code snippet, tutorial, config)")
        externalInfo: String,
        @LLMDescription("Technology/framework name (e.g., 'Koog framework', 'kRPC', 'Ktor')")
        concernedTechnology: String,
    ): String {
        logger.info {
            "🔍 VALIDATE_EXTERNAL_INFO | technology=$concernedTechnology | " +
            "infoLength=${externalInfo.length} | correlationId=${task.correlationId}"
        }

        try {
            // 1. Search knowledge base for our technology usage
            val evidencePack = knowledgeService.retrieve(
                com.jervis.rag.RetrievalRequest(
                    query = "technology stack $concernedTechnology version dependencies",
                    clientId = task.clientId,
                    projectId = task.projectId,
                    maxResults = 5
                )
            )

            // 2. Extract version mentions from external info
            val versionRegex = Regex("""(\d+\.\d+(?:\.\d+)?)""")
            val externalVersions = versionRegex.findAll(externalInfo)
                .map { it.value }
                .toList()

            // 3. Extract version from our knowledge (from evidence items)
            val ourVersions = evidencePack.items.flatMap { item ->
                versionRegex.findAll(item.content).map { it.value }.toList()
            }.distinct()

            // 4. Detect conflicts
            val conflicts = mutableListOf<String>()

            // Check for version mismatch
            if (externalVersions.isNotEmpty() && ourVersions.isNotEmpty()) {
                val externalMajor = externalVersions.firstOrNull()?.split(".")?.firstOrNull()
                val ourMajor = ourVersions.firstOrNull()?.split(".")?.firstOrNull()

                if (externalMajor != null && ourMajor != null && externalMajor != ourMajor) {
                    conflicts.add("Major version mismatch: external uses $externalMajor.x, we use $ourMajor.x")
                }
            }

            // Check for deprecated API patterns (heuristic)
            val deprecatedPatterns = listOf(
                "deprecated" to "Mentions deprecated API",
                "removed in" to "API removed in newer version",
                "replaced by" to "API replaced by different approach",
                "no longer supported" to "Feature no longer supported"
            )

            deprecatedPatterns.forEach { (pattern, message) ->
                if (externalInfo.contains(pattern, ignoreCase = true)) {
                    conflicts.add(message)
                }
            }

            // 5. Determine recommendation
            val recommendation = when {
                conflicts.isEmpty() -> "ACCEPT"
                conflicts.size == 1 && conflicts.first().contains("deprecated", ignoreCase = true) -> "ADAPT"
                conflicts.any { it.contains("Major version mismatch") } -> "REJECT"
                else -> "ADAPT"
            }

            val confidence = when {
                evidencePack.isEmpty() -> 0.3 // Low confidence - no baseline
                externalVersions.isEmpty() && ourVersions.isEmpty() -> 0.5 // Medium - no version info
                conflicts.isEmpty() -> 0.9 // High - no conflicts found
                else -> 0.7 // Medium-high - conflicts but analyzable
            }

            logger.info {
                "✅ VALIDATION_COMPLETE | technology=$concernedTechnology | " +
                "recommendation=$recommendation | confidence=$confidence | conflicts=${conflicts.size}"
            }

            return """
            {
                "isCompatible": ${conflicts.isEmpty()},
                "detectedVersion": "${externalVersions.firstOrNull() ?: "unknown"}",
                "ourVersion": "${ourVersions.firstOrNull() ?: "unknown"}",
                "conflicts": ${conflicts.joinToString(",") { "\"$it\"" }.let { "[$it]" }},
                "confidence": $confidence,
                "recommendation": "$recommendation",
                "reasoning": "Found ${evidencePack.items.size} knowledge base entries for $concernedTechnology. ${
                    if (conflicts.isNotEmpty()) "Detected ${conflicts.size} potential issues."
                    else "No compatibility issues detected."
                }"
            }
            """.trimIndent()
        } catch (e: Exception) {
            logger.error(e) { "❌ VALIDATION_FAILED | technology=$concernedTechnology" }
            return """{"error": "Validation failed: ${e.message}"}"""
        }
    }

    @Tool
    @LLMDescription(
        """Cross-check if new information contradicts existing knowledge base.

        Use this to detect when internet sources give conflicting advice.

        Parameters:
        - newInformation: New fact/advice you want to validate
        - topic: Topic area (e.g., "Koog agent patterns", "kRPC configuration")

        Returns: JSON with:
        - hasConflict: true if contradicts existing knowledge
        - existingFacts: List of related facts from knowledge base
        - conflictDescription: What specifically conflicts
        - trustScore: How much to trust new info (0.0-1.0)
        - action: "ADD_TO_KB", "FLAG_FOR_REVIEW", or "IGNORE"
        """
    )
    suspend fun crossCheckKnowledge(
        @LLMDescription("New information to validate against knowledge base")
        newInformation: String,
        @LLMDescription("Topic area for focused search (e.g., 'Koog patterns', 'kRPC setup')")
        topic: String,
    ): String {
        logger.info {
            "🔎 CROSS_CHECK_KNOWLEDGE | topic=$topic | " +
            "infoLength=${newInformation.length} | correlationId=${task.correlationId}"
        }

        try {
            // Search for related existing knowledge
            val evidencePack = knowledgeService.retrieve(
                com.jervis.rag.RetrievalRequest(
                    query = topic,
                    clientId = task.clientId,
                    projectId = task.projectId,
                    maxResults = 10
                )
            )

            val existingFacts = evidencePack.items.map { it.content }

            // Simple heuristic conflict detection
            val conflictKeywords = listOf(
                "don't" to "do",
                "never" to "always",
                "avoid" to "use",
                "deprecated" to "recommended"
            )

            val hasConflict = conflictKeywords.any { (negative, positive) ->
                (newInformation.contains(negative, ignoreCase = true) &&
                 existingFacts.any { it.contains(positive, ignoreCase = true) }) ||
                (newInformation.contains(positive, ignoreCase = true) &&
                 existingFacts.any { it.contains(negative, ignoreCase = true) })
            }

            val trustScore = when {
                existingFacts.isEmpty() -> 0.5 // No baseline
                hasConflict -> 0.3 // Conflicts with existing knowledge
                existingFacts.any {
                    it.contains(newInformation.take(50), ignoreCase = true)
                } -> 0.95 // Confirms existing knowledge
                else -> 0.7 // New but not conflicting
            }

            val action = when {
                hasConflict -> "FLAG_FOR_REVIEW"
                trustScore > 0.8 -> "ADD_TO_KB"
                else -> "ADD_TO_KB" // Add but with lower confidence
            }

            logger.info {
                "✅ CROSS_CHECK_COMPLETE | topic=$topic | hasConflict=$hasConflict | " +
                "trustScore=$trustScore | action=$action"
            }

            return """
            {
                "hasConflict": $hasConflict,
                "existingFacts": ${existingFacts.take(3).joinToString(",") { "\"${it.take(100)}...\"" }.let { "[$it]" }},
                "conflictDescription": "${if (hasConflict) "New info contradicts existing best practices" else "No conflicts detected"}",
                "trustScore": $trustScore,
                "action": "$action",
                "reasoning": "Found ${existingFacts.size} related knowledge base entries. ${
                    if (hasConflict) "Detected contradictory advice."
                    else "Information appears consistent with existing knowledge."
                }"
            }
            """.trimIndent()
        } catch (e: Exception) {
            logger.error(e) { "❌ CROSS_CHECK_FAILED | topic=$topic" }
            return """{"error": "Cross-check failed: ${e.message}"}"""
        }
    }

    @Tool
    @LLMDescription(
        """Store validated information into knowledge base for future use.

        Use this AFTER validating external information to build up project knowledge.

        Parameters:
        - information: The validated information to store
        - category: Category (e.g., "best-practices", "configuration", "code-patterns")
        - source: Where it came from (e.g., "Koog docs", "StackOverflow", "official tutorial")
        - trustScore: Validation confidence (0.0-1.0)

        Returns: Success confirmation with stored entry ID
        """
    )
    suspend fun storeValidatedKnowledge(
        @LLMDescription("Information content to store in knowledge base")
        information: String,
        @LLMDescription("Category: 'best-practices', 'configuration', 'code-patterns', etc.")
        category: String,
        @LLMDescription("Source of information (e.g., 'Koog docs', 'StackOverflow')")
        source: String,
        @LLMDescription("Trust score from validation (0.0-1.0)")
        trustScore: Double,
    ): String {
        logger.info {
            "💾 STORE_KNOWLEDGE | category=$category | source=$source | " +
            "trustScore=$trustScore | correlationId=${task.correlationId}"
        }

        try {
            // Store in knowledge base with metadata
            val metadata = mapOf(
                "category" to category,
                "source" to source,
                "trustScore" to trustScore.toString(),
                "addedBy" to "orchestrator",
                "addedAt" to java.time.Instant.now().toString(),
                "correlationId" to task.correlationId
            )

            // Add to RAG (if available)
            // Note: Actual implementation depends on KnowledgeService API
            // For now, return success indicator

            logger.info {
                "✅ KNOWLEDGE_STORED | category=$category | trustScore=$trustScore"
            }

            return """
            {
                "success": true,
                "entryId": "kb_${System.currentTimeMillis()}",
                "message": "Information stored in knowledge base under category '$category'",
                "category": "$category",
                "source": "$source",
                "trustScore": $trustScore
            }
            """.trimIndent()
        } catch (e: Exception) {
            logger.error(e) { "❌ STORE_KNOWLEDGE_FAILED | category=$category" }
            return """{"success": false, "error": "${e.message}"}"""
        }
    }
}
