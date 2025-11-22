package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.entity.UnsafeLinkPatternDocument
import com.jervis.repository.UnsafeLinkPatternMongoRepository
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * MCP Tool for managing link safety patterns - banned and allowed URL patterns.
 * Allows agent to add/update/disable unsafe link patterns discovered during scraping.
 *
 * Actions:
 * - add: Add new UNSAFE pattern (regex)
 * - disable: Disable pattern if causing false positives
 * - enable: Re-enable disabled pattern
 * - list: List all patterns (enabled or all)
 * - test: Test URL against current patterns
 */
@Service
class ManageLinkSafetyTool(
    private val unsafeLinkPatternRepository: UnsafeLinkPatternMongoRepository,
    override val promptRepository: PromptRepository,
) : McpTool<ManageLinkSafetyTool.ManageLinkSafetyParams> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name = ToolTypeEnum.SYSTEM_MANAGE_LINK_SAFETY_TOOL

    /**
     * Supported actions:
     * - ADD: Add new UNSAFE pattern (regex)
     * - DISABLE: Disable pattern if causing false positives
     * - ENABLE: Re-enable disabled pattern
     * - LIST: List all patterns (enabled-only toggle)
     * - TEST: Test URL against current patterns
     */
    @Serializable
    enum class Action {
        ADD,
        DISABLE,
        ENABLE,
        LIST,
        TEST,
    }

    @Serializable
    data class ManageLinkSafetyParams(
        val action: Action = Action.LIST,
        val pattern: String? = null,
        val description: String? = null,
        val exampleUrl: String? = null,
        val patternId: String? = null,
        val testUrl: String? = null,
        val listOnlyEnabled: Boolean = true,
    )

    override val descriptionObject =
        ManageLinkSafetyParams(
            action = Action.ADD, // Allowed values: ADD, DISABLE, ENABLE, LIST, TEST
            pattern = "https?://.*\\bunsubscribe\\b.*",
            description = "Block unsubscribe links to prevent accidental indexing",
            exampleUrl = "https://email.example.com/unsubscribe?id=123",
            patternId = null,
            testUrl = null,
            listOnlyEnabled = true,
        )

    override suspend fun execute(
        plan: Plan,
        request: ManageLinkSafetyParams,
    ): ToolResult =
        try {
            val params = request
            logger.info { "Executing link safety management: $params" }
            when (params.action) {
                Action.ADD -> handleAddPattern(params)
                Action.DISABLE -> handleDisablePattern(params)
                Action.ENABLE -> handleEnablePattern(params)
                Action.LIST -> handleListPatterns(params)
                Action.TEST -> handleTestUrl(params)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error managing link safety patterns" }
            ToolResult.error("Failed to manage link safety: ${e.message}")
        }

    private suspend fun handleAddPattern(params: ManageLinkSafetyParams): ToolResult {
        val pattern = params.pattern ?: return ToolResult.error("Pattern is required for add action")
        val description = params.description ?: return ToolResult.error("Description is required for add action")
        val exampleUrl = params.exampleUrl ?: return ToolResult.error("Example URL is required for add action")

        // Validate regex
        val regex =
            try {
                Regex(pattern)
            } catch (e: Exception) {
                return ToolResult.error("Invalid regex pattern: ${e.message}")
            }

        // Check if pattern already exists
        unsafeLinkPatternRepository.findByPattern(pattern)?.let { existing ->
            return ToolResult.error("Pattern already exists with ID: ${existing.id} (enabled: ${existing.enabled})")
        }

        // TODO: RAG INTEGRATION - Before creating, query RAG for similar patterns
        // - Search: "patterns matching: $description"
        // - If similar found → suggest reuse instead of creating duplicate
        // - Categories: calendar_response, unsubscribe, confirmation, tracking, etc.

        // Create new pattern
        val patternDoc =
            UnsafeLinkPatternDocument(
                pattern = pattern,
                description = description,
                exampleUrl = exampleUrl,
                matchCount = 0,
                createdAt = Instant.now(),
                lastMatchedAt = Instant.now(),
                enabled = true,
            )

        val saved = unsafeLinkPatternRepository.save(patternDoc)

        // TODO: RAG INTEGRATION - Store in RAG after MongoDB save
        // - Store with metadata: pattern, description, category, examples
        // - Index for semantic search by description and category
        // - Link to MongoDB ID for consistency
        // - Trigger cache invalidation in LinkSafetyQualifier

        logger.info { "Added new unsafe link pattern: $pattern (ID: ${saved.id})" }

        return ToolResult.ok(
            """
            Successfully added UNSAFE link pattern:
            - ID: ${saved.id}
            - Pattern: $pattern
            - Description: $description
            - Example: $exampleUrl
            - Enabled: true
            """.trimIndent(),
        )
    }

    private suspend fun handleDisablePattern(params: ManageLinkSafetyParams): ToolResult {
        val patternId = params.patternId ?: return ToolResult.error("Pattern ID is required for disable action")

        val objectId =
            try {
                ObjectId(patternId)
            } catch (e: IllegalArgumentException) {
                return ToolResult.error("Invalid pattern ID format: $patternId")
            }

        val pattern =
            unsafeLinkPatternRepository.findById(objectId)
                ?: return ToolResult.error("Pattern not found with ID: $patternId")

        if (!pattern.enabled) {
            return ToolResult.error("Pattern is already disabled")
        }

        val updated = pattern.copy(enabled = false)
        unsafeLinkPatternRepository.save(updated)

        logger.info { "Disabled unsafe link pattern: ${pattern.pattern} (ID: $patternId)" }

        return ToolResult.ok(
            """
            Successfully disabled pattern:
            - ID: $patternId
            - Pattern: ${pattern.pattern}
            - Description: ${pattern.description}
            """.trimIndent(),
        )
    }

    private suspend fun handleEnablePattern(params: ManageLinkSafetyParams): ToolResult {
        val patternId = params.patternId ?: return ToolResult.error("Pattern ID is required for enable action")

        val objectId =
            try {
                ObjectId(patternId)
            } catch (e: IllegalArgumentException) {
                return ToolResult.error("Invalid pattern ID format: $patternId")
            }

        val pattern =
            unsafeLinkPatternRepository.findById(objectId)
                ?: return ToolResult.error("Pattern not found with ID: $patternId")

        if (pattern.enabled) {
            return ToolResult.error("Pattern is already enabled")
        }

        val updated = pattern.copy(enabled = true)
        unsafeLinkPatternRepository.save(updated)

        logger.info { "Enabled unsafe link pattern: ${pattern.pattern} (ID: $patternId)" }

        return ToolResult.ok(
            """
            Successfully enabled pattern:
            - ID: $patternId
            - Pattern: ${pattern.pattern}
            - Description: ${pattern.description}
            """.trimIndent(),
        )
    }

    private suspend fun handleListPatterns(params: ManageLinkSafetyParams): ToolResult {
        val patterns =
            if (params.listOnlyEnabled) {
                unsafeLinkPatternRepository.findByEnabledTrue().toList()
            } else {
                val all = mutableListOf<UnsafeLinkPatternDocument>()
                unsafeLinkPatternRepository.findAll().collect { all.add(it) }
                all
            }

        if (patterns.isEmpty()) {
            return ToolResult.ok("No patterns found.")
        }

        val output =
            buildString {
                appendLine("Found ${patterns.size} pattern(s):")
                appendLine()
                patterns.forEachIndexed { index, pattern ->
                    appendLine("${index + 1}. Pattern: ${pattern.pattern}")
                    appendLine("   ID: ${pattern.id}")
                    appendLine("   Description: ${pattern.description}")
                    appendLine("   Example URL: ${pattern.exampleUrl}")
                    appendLine("   Match count: ${pattern.matchCount}")
                    appendLine("   Enabled: ${pattern.enabled}")
                    appendLine("   Created: ${pattern.createdAt}")
                    appendLine()
                }
            }

        return ToolResult.ok(output)
    }

    private suspend fun handleTestUrl(params: ManageLinkSafetyParams): ToolResult {
        val testUrl = params.testUrl ?: return ToolResult.error("Test URL is required for test action")

        val patterns = unsafeLinkPatternRepository.findByEnabledTrue().toList()

        if (patterns.isEmpty()) {
            return ToolResult.ok("No enabled patterns to test against.")
        }

        val matches = mutableListOf<UnsafeLinkPatternDocument>()

        patterns.forEach { pattern ->
            try {
                val regex = Regex(pattern.pattern)
                if (regex.containsMatchIn(testUrl)) {
                    matches.add(pattern)
                }
            } catch (e: Exception) {
                logger.warn { "Invalid regex pattern ${pattern.pattern}: ${e.message}" }
            }
        }

        return if (matches.isEmpty()) {
            ToolResult.ok("URL does NOT match any enabled patterns. URL would be evaluated by other rules.")
        } else {
            val output =
                buildString {
                    appendLine("URL matches ${matches.size} pattern(s) - would be marked UNSAFE:")
                    appendLine()
                    matches.forEach { pattern ->
                        appendLine("- Pattern: ${pattern.pattern}")
                        appendLine("  Description: ${pattern.description}")
                        appendLine("  ID: ${pattern.id}")
                        appendLine()
                    }
                }
            ToolResult.ok(output)
        }
    }
}
