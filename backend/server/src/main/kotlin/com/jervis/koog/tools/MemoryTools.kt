package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.domain.agent.AgentMemoryDocument
import com.jervis.domain.plan.Plan
import com.jervis.service.agent.AgentMemoryService
import kotlinx.coroutines.runBlocking

/**
 * MemoryTools – Direct Koog ToolSet for long-term persistent agent memory.
 * Provides: MemoryWrite, MemoryRead, MemorySearch with full audit trail support.
 *
 * Memory characteristics:
 * - PERMANENT: Nothing is ever deleted
 * - AUDIT TRAIL: Every memory contains WHY (reason), WHAT (content), WHEN (timestamp), RESULT, CONTEXT
 * - SEARCHABLE: Multi-dimensional search by time, topic, project, entity, correlation
 * - CLIENT ISOLATED: All memories are per-client with optional project filtering
 *
 * Usage in KoogWorkflowAgent:
 * ```kotlin
 * tools(MemoryTools(plan = plan, memoryService = memoryService))
 * ```
 */
@LLMDescription("Long-term persistent agent memory with full audit trail. Stores WHY agent did something, WHAT was done, WHEN, RESULT, and CONTEXT. Nothing is ever deleted.")
class MemoryTools(
    private val plan: Plan,
    private val memoryService: AgentMemoryService,
) : ToolSet {

    @Tool
    @LLMDescription("Write permanent memory with audit trail. Stores WHY you did something, context, and results. NEVER deleted.")
    fun memoryWrite(
        @LLMDescription("Type of action performed (FILE_EDIT, TASK_CREATE, DECISION, ANALYSIS, SHELL_EXEC, etc.)")
        actionType: String,

        @LLMDescription("What you did or learned (main content)")
        content: String,

        @LLMDescription("WHY you did this - reasoning and justification (audit trail)")
        reason: String,

        @LLMDescription("Context in which this happened (user request, previous steps, etc.)")
        context: String? = null,

        @LLMDescription("Result of the operation (success/error details)")
        result: String? = null,

        @LLMDescription("Type of entity affected (FILE, CLASS, METHOD, TASK, COMMIT, BRANCH, etc.)")
        entityType: String? = null,

        @LLMDescription("Key of entity (file path, task ID, commit hash, etc.)")
        entityKey: String? = null,

        @LLMDescription("Tags for easier searching (comma-separated)")
        tags: String? = null,
    ): String {
        try {
            val tagsList = tags?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

            val memory = AgentMemoryDocument(
                clientId = plan.clientDocument.id.toHexString(),
                projectId = plan.projectDocument?.id?.toHexString(),
                correlationId = plan.correlationId,
                actionType = actionType,
                content = content,
                reason = reason,
                context = context,
                result = result,
                entityType = entityType,
                entityKey = entityKey,
                tags = tagsList,
            )

            val saved = runBlocking { memoryService.write(memory) }

            return buildString {
                appendLine("MEMORY_WRITTEN: Permanent memory stored")
                appendLine("ID: ${saved.id.toHexString()}")
                appendLine("Action: $actionType")
                appendLine("Entity: ${entityType ?: "N/A"} :: ${entityKey ?: "N/A"}")
                appendLine("Tags: ${tagsList.joinToString(", ").ifEmpty { "none" }}")
                appendLine()
                appendLine("This memory is PERMANENT and will be available for long-term audit and recall.")
            }
        } catch (e: Exception) {
            throw IllegalStateException("MEMORY_WRITE_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription("Read recent memories. Returns last N memories with full audit trail.")
    fun memoryRead(
        @LLMDescription("Maximum number of memories to return (default 20)")
        limit: Int = 20,

        @LLMDescription("Optional: filter by project ID")
        projectId: String? = null,

        @LLMDescription("Optional: filter by correlation ID (specific session/workflow)")
        correlationId: String? = null,
    ): String {
        try {
            val clientId = plan.clientDocument.id.toHexString()

            val memories = runBlocking {
                when {
                    correlationId != null -> memoryService.readByCorrelation(clientId, correlationId)
                    projectId != null -> memoryService.readByProject(clientId, projectId, limit)
                    else -> memoryService.read(clientId, limit)
                }
            }

            if (memories.isEmpty()) {
                return "MEMORY_EMPTY: No memories found matching your criteria."
            }

            return buildString {
                appendLine("MEMORY_READ: Found ${memories.size} memories")
                appendLine()
                memories.forEachIndexed { idx, mem ->
                    appendLine("─────────────────────────────────────────")
                    appendLine("Memory #${idx + 1} [${mem.id.toHexString()}]")
                    appendLine("Timestamp: ${mem.timestamp}")
                    appendLine("Action: ${mem.actionType}")
                    if (mem.entityType != null) appendLine("Entity: ${mem.entityType} :: ${mem.entityKey}")
                    if (mem.tags.isNotEmpty()) appendLine("Tags: ${mem.tags.joinToString(", ")}")
                    appendLine()
                    appendLine("Content: ${mem.content}")
                    appendLine()
                    appendLine("Reason (WHY): ${mem.reason}")
                    if (mem.context != null) {
                        appendLine()
                        appendLine("Context: ${mem.context}")
                    }
                    if (mem.result != null) {
                        appendLine()
                        appendLine("Result: ${mem.result}")
                    }
                    appendLine()
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("MEMORY_READ_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription("Search memories by various criteria. Supports text search, tags, action type, entity, time range.")
    fun memorySearch(
        @LLMDescription("Search mode: 'text', 'tags', 'actionType', 'entity', 'lastDays'")
        searchMode: String,

        @LLMDescription("Search query (depends on mode: text query, comma-separated tags, action type, etc.)")
        query: String,

        @LLMDescription("For 'entity' mode: entity type (FILE, CLASS, METHOD, etc.)")
        entityType: String? = null,

        @LLMDescription("For 'lastDays' mode: number of days to look back")
        days: Int? = null,

        @LLMDescription("Maximum results to return")
        limit: Int = 20,
    ): String {
        try {
            val clientId = plan.clientDocument.id.toHexString()

            val memories = runBlocking {
                when (searchMode.lowercase()) {
                    "text" -> memoryService.searchByText(clientId, query, limit)
                    "tags" -> {
                        val tagsList = query.split(",").map { it.trim() }
                        memoryService.searchByTags(clientId, tagsList, limit)
                    }
                    "actiontype" -> memoryService.searchByActionType(clientId, query, limit)
                    "entity" -> {
                        if (entityType == null) {
                            throw IllegalStateException("MEMORY_SEARCH_INVALID: entityType required for 'entity' search mode")
                        }
                        memoryService.searchByEntity(clientId, entityType, query, limit)
                    }
                    "lastdays" -> {
                        val daysCount: Long = when {
                            days != null -> days.toLong()
                            else -> query.toLongOrNull() ?: throw IllegalStateException("MEMORY_SEARCH_INVALID: days must be a number")
                        }
                        memoryService.searchLastDays(clientId, daysCount, limit)
                    }
                    else -> throw IllegalStateException("MEMORY_SEARCH_INVALID: Unknown searchMode '$searchMode'. Use: text, tags, actionType, entity, lastDays")
                }
            }

            if (memories.isEmpty()) {
                return "MEMORY_SEARCH_EMPTY: No memories found for query: $query (mode: $searchMode)"
            }

            return buildString {
                appendLine("MEMORY_SEARCH: Found ${memories.size} memories (mode: $searchMode, query: $query)")
                appendLine()
                memories.forEachIndexed { idx, mem ->
                    appendLine("─────────────────────────────────────────")
                    appendLine("Result #${idx + 1} [${mem.id.toHexString()}]")
                    appendLine("${mem.timestamp} | ${mem.actionType}")
                    if (mem.entityType != null) appendLine("Entity: ${mem.entityType} :: ${mem.entityKey}")
                    appendLine()
                    appendLine(mem.content.take(200) + if (mem.content.length > 200) "..." else "")
                    appendLine()
                    appendLine("Reason: ${mem.reason.take(150) + if (mem.reason.length > 150) "..." else ""}")
                    appendLine()
                }
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("MEMORY_SEARCH_FAILED: ${e.message}", e)
        }
    }
}
