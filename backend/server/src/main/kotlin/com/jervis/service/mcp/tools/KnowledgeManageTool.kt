package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.rag.KnowledgeSeverity
import com.jervis.domain.rag.KnowledgeType
import com.jervis.service.knowledge.KnowledgeManagementService
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Knowledge Management Tool for CRUD operations on knowledge fragments.
 *
 * Operations:
 * - search: Find knowledge by query/filters
 * - delete: Remove knowledge by knowledgeId
 * - list_rules: List all active rules
 * - list_memories: List recent memories
 *
 * This tool enables the agent to manage its own knowledge base.
 */
@Service
class KnowledgeManageTool(
    private val knowledgeManagementService: KnowledgeManagementService,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.KNOWLEDGE_MANAGE_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "KNOWLEDGE_MANAGE_START: taskDescription='$taskDescription'" }

        // Parse operation from taskDescription
        // Expected format: "operation: parameters"
        // Examples:
        //   "search: kotlin architecture"
        //   "delete: rule-uuid-123"
        //   "list_rules"
        //   "list_memories"

        val parts = taskDescription.split(":", limit = 2).map { it.trim() }
        val operation = parts.getOrNull(0)?.lowercase() ?: return ToolResult.error("Invalid format. Use 'operation: parameters'")
        val params = parts.getOrNull(1) ?: ""

        return when (operation) {
            "search" -> handleSearch(params, plan)
            "delete" -> handleDelete(params, plan)
            "list_rules" -> handleListRules(plan)
            "list_memories" -> handleListMemories(plan)
            else -> ToolResult.error("Unknown operation: $operation. Supported: search, delete, list_rules, list_memories")
        }
    }

    private suspend fun handleSearch(
        query: String,
        plan: Plan,
    ): ToolResult {
        if (query.isBlank()) {
            return ToolResult.error("Search query cannot be empty")
        }

        logger.info { "Searching knowledge: query='$query'" }

        val results =
            knowledgeManagementService
                .searchKnowledge(
                    query = query,
                    clientId = plan.clientDocument.id,
                    projectId = plan.projectDocument?.id,
                    limit = 10,
                ).getOrElse { error ->
                    logger.error { "Search failed: ${error.message}" }
                    return ToolResult.error("Search failed: ${error.message}")
                }

        logger.info { "Found ${results.size} knowledge fragments" }

        return ToolResult.success(
            toolName = name.name,
            summary = "Found ${results.size} knowledge fragments",
            content =
                buildString {
                    appendLine("Search Results for '$query':")
                    appendLine()
                    if (results.isEmpty()) {
                        appendLine("No knowledge found matching query.")
                    } else {
                        results.forEachIndexed { idx, fragment ->
                            appendLine("${idx + 1}. [${fragment.type}] ${fragment.knowledgeId}")
                            appendLine("   Severity: ${fragment.severity}")
                            appendLine("   Tags: ${fragment.tags.joinToString(", ")}")
                            appendLine("   Text: ${fragment.text.take(200)}${if (fragment.text.length > 200) "..." else ""}")
                            appendLine()
                        }
                    }
                },
        )
    }

    private suspend fun handleDelete(
        knowledgeId: String,
        plan: Plan,
    ): ToolResult {
        if (knowledgeId.isBlank()) {
            return ToolResult.error("Knowledge ID cannot be empty")
        }

        logger.info { "Deleting knowledge: knowledgeId='$knowledgeId'" }

        val deleted =
            knowledgeManagementService
                .deleteKnowledge(
                    knowledgeId = knowledgeId,
                    clientId = plan.clientDocument.id,
                ).getOrElse { error ->
                    logger.error { "Delete failed: ${error.message}" }
                    return ToolResult.error("Delete failed: ${error.message}")
                }

        return if (deleted) {
            logger.info { "Knowledge deleted successfully: $knowledgeId" }
            ToolResult.success(
                toolName = name.name,
                summary = "Knowledge deleted: $knowledgeId",
                content = "Successfully deleted knowledge fragment: $knowledgeId",
            )
        } else {
            logger.warn { "Knowledge not found: $knowledgeId" }
            ToolResult.error("Knowledge not found: $knowledgeId")
        }
    }

    private suspend fun handleListRules(plan: Plan): ToolResult {
        logger.info { "Listing all rules" }

        val rules =
            knowledgeManagementService
                .getAllRules(
                    clientId = plan.clientDocument.id,
                    projectId = plan.projectDocument?.id,
                ).getOrElse { error ->
                    logger.error { "List rules failed: ${error.message}" }
                    return ToolResult.error("List rules failed: ${error.message}")
                }

        logger.info { "Found ${rules.size} rules" }

        return ToolResult.success(
            toolName = name.name,
            summary = "Listed ${rules.size} rules",
            content =
                buildString {
                    appendLine("Active Rules (${rules.size}):")
                    appendLine()
                    if (rules.isEmpty()) {
                        appendLine("No rules defined.")
                    } else {
                        // Group by severity
                        val bySeverity = rules.groupBy { it.severity }

                        bySeverity[KnowledgeSeverity.MUST]?.let { mustRules ->
                            appendLine("=== MUST (${mustRules.size}) ===")
                            mustRules.forEach { rule ->
                                appendLine("- ${rule.knowledgeId}")
                                appendLine("  ${rule.text}")
                                appendLine("  Tags: ${rule.tags.joinToString(", ")}")
                                appendLine()
                            }
                        }

                        bySeverity[KnowledgeSeverity.SHOULD]?.let { shouldRules ->
                            appendLine("=== SHOULD (${shouldRules.size}) ===")
                            shouldRules.forEach { rule ->
                                appendLine("- ${rule.knowledgeId}")
                                appendLine("  ${rule.text}")
                                appendLine("  Tags: ${rule.tags.joinToString(", ")}")
                                appendLine()
                            }
                        }

                        bySeverity[KnowledgeSeverity.INFO]?.let { infoRules ->
                            appendLine("=== INFO (${infoRules.size}) ===")
                            infoRules.forEach { rule ->
                                appendLine("- ${rule.knowledgeId}")
                                appendLine("  ${rule.text}")
                                appendLine("  Tags: ${rule.tags.joinToString(", ")}")
                                appendLine()
                            }
                        }
                    }
                },
        )
    }

    private suspend fun handleListMemories(plan: Plan): ToolResult {
        logger.info { "Listing recent memories" }

        val memories =
            knowledgeManagementService
                .getAllMemories(
                    clientId = plan.clientDocument.id,
                    projectId = plan.projectDocument?.id,
                    limit = 20,
                ).getOrElse { error ->
                    logger.error { "List memories failed: ${error.message}" }
                    return ToolResult.error("List memories failed: ${error.message}")
                }

        logger.info { "Found ${memories.size} memories" }

        return ToolResult.success(
            toolName = name.name,
            summary = "Listed ${memories.size} memories",
            content =
                buildString {
                    appendLine("Recent Memories (${memories.size}):")
                    appendLine()
                    if (memories.isEmpty()) {
                        appendLine("No memories stored.")
                    } else {
                        memories.forEachIndexed { idx, memory ->
                            appendLine("${idx + 1}. ${memory.knowledgeId}")
                            appendLine("   Tags: ${memory.tags.joinToString(", ")}")
                            appendLine("   ${memory.text}")
                            appendLine()
                        }
                    }
                },
        )
    }
}
