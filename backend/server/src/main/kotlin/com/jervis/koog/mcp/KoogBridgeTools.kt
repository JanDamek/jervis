package com.jervis.koog.mcp

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.mcp.McpTool
import com.jervis.mcp.McpToolRegistry
import com.jervis.mcp.tools.CommunicationUserDialogTool
import com.jervis.mcp.tools.KnowledgeSearchTool
import com.jervis.mcp.tools.KnowledgeStoreTool
import com.jervis.mcp.tools.ScheduleTaskTool
import com.jervis.mcp.tools.TaskCreateUserTaskTool
import com.jervis.mcp.domain.ToolResult
import kotlinx.coroutines.runBlocking

/**
 * KoogBridgeTools exposes selected JERVIS MCP tools to the Koog ToolRegistry.
 * It forwards calls to the underlying McpTool implementations and returns their textual outputs.
 *
 * Failure semantics for Koog:
 *  - ToolResult.Ok    -> returns the output String to Koog
 *  - ToolResult.Error -> throws IllegalStateException with an informative message (Koog treats it as tool error)
 *  - ToolResult.Ask/Stop/InsertStep -> throws IllegalStateException (not supported in Koog tool context)
 */
@LLMDescription(
    "JERVIS MCP bridge toolset. Provides RAG search/index, task scheduling, user task creation, and interactive user dialog. " +
        "Each method throws on failure so Koog can recognize and handle tool errors."
)
class KoogBridgeTools(
    private val plan: Plan,
    private val registry: McpToolRegistry,
) : ToolSet {

    private fun unwrap(result: ToolResult): String =
        when (result) {
            is ToolResult.Ok -> result.output
            is ToolResult.Error -> throw IllegalStateException(
                buildString {
                    append("MCP_TOOL_FAILED: ")
                    append(result.errorMessage ?: "Unknown error")
                    if (result.output.isNotBlank()) {
                        append("\n"); append(result.output)
                    }
                }
            )
            is ToolResult.Ask -> throw IllegalStateException(
                "MCP_TOOL_ASK_NOT_SUPPORTED_IN_KOOG: ${result.output}"
            )
            is ToolResult.Stop -> throw IllegalStateException(
                "MCP_TOOL_STOP: ${result.reason}\n${result.output}"
            )
            is ToolResult.InsertStep -> throw IllegalStateException(
                "MCP_TOOL_INSERT_STEP_NOT_SUPPORTED_IN_KOOG: InsertStep cannot be handled by Koog bridge."
            )
        }

    @Tool
    @LLMDescription("Search knowledge base for relevant information. Returns raw chunks; no synthesis. Throws on failure.")
    fun ragSearch(
        @LLMDescription("Search query string")
        query: String,

        @LLMDescription("Maximum number of results to return")
        maxResult: Int = 25,

        @LLMDescription("Minimum similarity score (0..1)")
        minScore: Double = 0.65,
    ): String {
        val tool = registry.byName(ToolTypeEnum.KNOWLEDGE_SEARCH_TOOL) as McpTool<KnowledgeSearchTool.Description>
        val req = KnowledgeSearchTool.Description(query, maxResult, minScore, null)
        val result = runBlocking { tool.execute(plan, req) }
        return unwrap(result)
    }

    @Tool
    @LLMDescription("Store plain text into knowledge base as MEMORY document. Throws on failure.")
    fun ragIndex(
        @LLMDescription("Plain text content to index as MEMORY")
        content: String,
    ): String {
        val tool = registry.byName(ToolTypeEnum.KNOWLEDGE_STORE_TOOL) as McpTool<KnowledgeStoreTool.Description>
        val req = KnowledgeStoreTool.Description(content)
        val result = runBlocking { tool.execute(plan, req) }
        return unwrap(result)
    }

    @Tool
    @LLMDescription("Schedule or cancel a system task. Throws on failure.")
    fun scheduleTask(
        @LLMDescription("Task content or command to execute")
        content: String,

        @LLMDescription("When to run: exact 'yyyy-MM-dd HH:mm' or natural text like 'tomorrow 9:00'")
        scheduledDateTime: String? = null,

        @LLMDescription("Human-friendly task name")
        taskName: String? = null,

        @LLMDescription("CRON expression to schedule recurring tasks")
        cronExpression: String? = null,

        @LLMDescription("Action to perform: 'schedule' or 'cancel'")
        action: String = "schedule",

        @LLMDescription("Task ID for cancellation")
        taskId: String? = null,
    ): String {
        val tool = registry.byName(ToolTypeEnum.SYSTEM_SCHEDULE_TASK_TOOL) as McpTool<ScheduleTaskTool.ScheduleParams>
        val req = ScheduleTaskTool.ScheduleParams(
            content = content,
            projectId = plan.projectDocument?.id?.toHexString(),
            scheduledDateTime = scheduledDateTime,
            taskName = taskName,
            cronExpression = cronExpression,
            action = action,
            taskId = taskId,
            correlationId = plan.correlationId,
        )
        val result = runBlocking { tool.execute(plan, req) }
        return unwrap(result)
    }

    @Tool
    @LLMDescription("Create a user-facing task to request approval or action. Throws on failure.")
    fun createUserTask(
        @LLMDescription("Short actionable title")
        title: String,

        @LLMDescription("Detailed instructions or context")
        description: String? = null,

        @LLMDescription("Priority: LOW | MEDIUM | HIGH")
        priority: String? = null,

        @LLMDescription("Due date in ISO-8601, e.g., 2025-12-31T17:00:00Z")
        dueDate: String? = null,

        @LLMDescription("Source type: EMAIL | AGENT_SUGGESTION | ...")
        sourceType: String = "AGENT_SUGGESTION",

        @LLMDescription("Optional source URI, e.g., email://<accountId>/<messageId>")
        sourceUri: String? = null,

        @LLMDescription("Additional metadata as key=value lines (optional)")
        metadata: String? = null,
    ): String {
        val tool = registry.byName(ToolTypeEnum.TASK_CREATE_USER_TASK_TOOL) as McpTool<TaskCreateUserTaskTool.CreateUserTaskRequest>
        val meta: Map<String, String> = metadata
            ?.lines()
            ?.mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }
            ?.toMap()
            ?: emptyMap()
        val req = TaskCreateUserTaskTool.CreateUserTaskRequest(
            title = title,
            description = description,
            priority = priority,
            dueDate = dueDate,
            sourceType = sourceType,
            sourceUri = sourceUri,
            metadata = meta,
        )
        val result = runBlocking { tool.execute(plan, req) }
        return unwrap(result)
    }

    @Tool
    @LLMDescription("Ask the user a question via interactive dialog. Not available in background mode. Throws on failure.")
    fun userDialog(
        @LLMDescription("Question to ask the user")
        question: String,

        @LLMDescription("Proposed default answer")
        proposedAnswer: String,
    ): String {
        val tool = registry.byName(ToolTypeEnum.COMMUNICATION_USER_DIALOG_TOOL) as McpTool<CommunicationUserDialogTool.UserDialogParams>
        val req = CommunicationUserDialogTool.UserDialogParams(question, proposedAnswer)
        val result = runBlocking { tool.execute(plan, req) }
        return unwrap(result)
    }
}
