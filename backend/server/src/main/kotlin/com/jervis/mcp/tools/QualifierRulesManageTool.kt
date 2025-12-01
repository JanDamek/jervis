package com.jervis.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.qualifier.QualifierRule
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.mcp.McpTool
import com.jervis.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.qualifier.QualifierRuleService
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * MCP Tool for managing qualifier rules.
 * Allows agent to create, list, update, and delete rules that control task pre-filtering.
 *
 * Agent describes intent in natural language → Tool Reasoning converts to JSON → Tool executes.
 *
 * Use cases:
 * - Agent notices repeated spam patterns → creates discard rule
 * - Agent wants to prioritize certain emails → creates delegate rule
 * - Agent wants to remove outdated rule → deletes rule
 */
@Service
class QualifierRulesManageTool(
    private val qualifierRuleService: QualifierRuleService,
    override val promptRepository: PromptRepository,
) : McpTool<QualifierRulesManageTool.QualifierRulesParams> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name = ToolTypeEnum.SYSTEM_QUALIFIER_RULES_MANAGE_TOOL

    @Serializable
    data class QualifierRulesParams(
        val operation: String = "add", // "add", "list", "delete", "update", "listAll"
        val qualifierType: String? = "EMAIL_PROCESSING", // PendingTaskTypeEnum name
        val ruleText: String? = "Always discard automated emails with 'successful' in subject", // For add/update
        val ruleId: String? = "673e5f8a9b2c1a0012345678", // MongoDB ObjectId for delete/update
    )

    override val descriptionObject =
        QualifierRulesParams(
            operation = "add",
            qualifierType = "EMAIL_PROCESSING",
            ruleText = "Discard emails that contain 'unsubscribe' in body",
            ruleId = null,
        )

    override suspend fun execute(
        plan: Plan,
        request: QualifierRulesParams,
    ): ToolResult =
        try {
            logger.info { "Executing qualifier rules management: $request" }
            handleQualifierRules(request)
        } catch (e: Exception) {
            logger.error(e) { "Error managing qualifier rules" }
            ToolResult.error("Failed to manage qualifier rules: ${e.message}")
        }

    private suspend fun handleQualifierRules(params: QualifierRulesParams): ToolResult =
        when (params.operation.lowercase()) {
            "add" -> handleAdd(params)
            "list" -> handleList(params)
            "listall" -> handleListAll()
            "delete" -> handleDelete(params)
            "update" -> handleUpdate(params)
            else -> ToolResult.error("Invalid operation: ${params.operation}. Valid: add, list, listAll, delete, update")
        }

    private suspend fun handleAdd(params: QualifierRulesParams): ToolResult {
        val qualifierTypeStr =
            params.qualifierType
                ?: return ToolResult.error("qualifierType is required for add operation")

        val qualifierType =
            try {
                PendingTaskTypeEnum.valueOf(qualifierTypeStr.uppercase())
            } catch (e: IllegalArgumentException) {
                return ToolResult.error("Invalid qualifierType: $qualifierTypeStr")
            }

        val ruleText = params.ruleText?.trim()
        if (ruleText.isNullOrBlank()) {
            return ToolResult.error("ruleText is required for add operation")
        }

        val rule = qualifierRuleService.addRule(qualifierType, ruleText)

        logger.info { "Added qualifier rule: ${rule.id} for $qualifierType" }
        return ToolResult.success(
            toolName = name.name,
            summary = "Added qualifier rule for $qualifierType",
            content =
                """
                Successfully added qualifier rule:
                - ID: ${rule.id}
                - Type: ${rule.qualifierType}
                - Rule: ${rule.ruleText}
                """.trimIndent(),
        )
    }

    private suspend fun handleList(params: QualifierRulesParams): ToolResult {
        val qualifierTypeStr =
            params.qualifierType
                ?: return ToolResult.error("qualifierType is required for list operation")

        val qualifierType =
            try {
                PendingTaskTypeEnum.valueOf(qualifierTypeStr.uppercase())
            } catch (e: IllegalArgumentException) {
                return ToolResult.error("Invalid qualifierType: $qualifierTypeStr")
            }

        val rules = qualifierRuleService.listRules(qualifierType)

        if (rules.isEmpty()) {
            return ToolResult.ok("No qualifier rules found for $qualifierType")
        }

        val content =
            buildString {
                appendLine("Qualifier rules for $qualifierType (${rules.size} rules):")
                appendLine()
                rules.forEachIndexed { index, rule ->
                    appendLine("${index + 1}. ID: ${rule.id}")
                    appendLine("   Rule: ${rule.ruleText}")
                    appendLine()
                }
            }.trim()

        return ToolResult.success(
            toolName = name.name,
            summary = "Listed ${rules.size} qualifier rules for $qualifierType",
            content = content,
        )
    }

    private suspend fun handleListAll(): ToolResult {
        val rules = qualifierRuleService.listAllRules()

        if (rules.isEmpty()) {
            return ToolResult.ok("No qualifier rules found")
        }

        val rulesByType: Map<PendingTaskTypeEnum, List<QualifierRule>> = rules.groupBy { it.qualifierType }
        val content =
            buildString {
                appendLine("All qualifier rules (${rules.size} total):")
                appendLine()
                rulesByType.forEach { (type, typeRules) ->
                    appendLine("=== $type (${typeRules.size} rules) ===")
                    typeRules.forEachIndexed { index, rule ->
                        appendLine("${index + 1}. ID: ${rule.id}")
                        appendLine("   Rule: ${rule.ruleText}")
                        appendLine()
                    }
                }
            }.trim()

        return ToolResult.success(
            toolName = name.name,
            summary = "Listed ${rules.size} qualifier rules across all types",
            content = content,
        )
    }

    private suspend fun handleDelete(params: QualifierRulesParams): ToolResult {
        val ruleId = params.ruleId?.trim()
        if (ruleId.isNullOrBlank()) {
            return ToolResult.error("ruleId is required for delete operation")
        }

        val deleted = qualifierRuleService.deleteRule(ruleId)

        return if (deleted) {
            logger.info { "Deleted qualifier rule: $ruleId" }
            ToolResult.success(
                toolName = name.name,
                summary = "Deleted qualifier rule",
                content = "Successfully deleted qualifier rule: $ruleId",
            )
        } else {
            ToolResult.error("Qualifier rule not found: $ruleId")
        }
    }

    private suspend fun handleUpdate(params: QualifierRulesParams): ToolResult {
        val ruleId = params.ruleId?.trim()
        if (ruleId.isNullOrBlank()) {
            return ToolResult.error("ruleId is required for update operation")
        }

        val newRuleText = params.ruleText?.trim()
        if (newRuleText.isNullOrBlank()) {
            return ToolResult.error("ruleText is required for update operation")
        }

        val updated = qualifierRuleService.updateRule(ruleId, newRuleText)

        return if (updated != null) {
            logger.info { "Updated qualifier rule: $ruleId" }
            ToolResult.success(
                toolName = name.name,
                summary = "Updated qualifier rule",
                content =
                    """
                    Successfully updated qualifier rule:
                    - ID: ${updated.id}
                    - Type: ${updated.qualifierType}
                    - New rule: ${updated.ruleText}
                    """.trimIndent(),
            )
        } else {
            ToolResult.error("Qualifier rule not found: $ruleId")
        }
    }
}
