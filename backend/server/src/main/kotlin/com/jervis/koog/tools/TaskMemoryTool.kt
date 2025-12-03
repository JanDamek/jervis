package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.domain.plan.Plan
import com.jervis.service.agent.TaskMemoryService
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

/**
 * TaskMemoryTool - Load context from Qualifier for Workflow agent.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - Used by KoogWorkflowAgent (GPU) to load structured context from Qualifier
 * - Avoids re-reading full documents - loads brief context summary
 * - Includes: key findings, action items, Graph references, RAG document IDs
 * - Enables efficient GPU usage by skipping redundant structuring work
 *
 * Context Loading:
 * - Call this tool at the START of workflow execution
 * - Returns: context summary, Graph node keys, RAG document IDs, structured metadata
 * - Use Graph/RAG tools with provided keys/IDs to access full content if needed
 * - Context summary contains all important findings from Qualifier
 */
@LLMDescription("Load task context from Qualifier agent for efficient workflow execution")
class TaskMemoryTool(
    private val plan: Plan,
    private val taskMemoryService: TaskMemoryService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription("""Load task context prepared by Qualifier agent.

Returns structured context from CPU-based qualification phase:
- Context summary: Brief overview with key findings and action items
- Graph node keys: References to structured entities in Knowledge Graph
- RAG document IDs: References to searchable content in RAG
- Structured metadata: Key-value data extracted during qualification

Use this at the START of workflow to understand what the Qualifier found.
Then use Graph/RAG tools with provided keys/IDs if you need full content.

This avoids redundant work - Qualifier already structured the data, you focus on analysis/actions.""")
    fun loadTaskContext(): String = runBlocking {
        try {
            val correlationId = plan.correlationId
            logger.info { "TASK_MEMORY_LOAD: correlationId=$correlationId" }

            val taskMemory = taskMemoryService.loadTaskMemory(correlationId)
                ?: return@runBlocking "No task context found for this workflow. This task may have been processed directly without qualification."

            buildString {
                appendLine("✓ Task context loaded from Qualifier")
                appendLine()
                appendLine("## Routing Information")
                appendLine("  Decision: ${taskMemory.routingDecision}")
                appendLine("  Reason: ${taskMemory.routingReason}")
                appendLine()
                appendLine("## Source Information")
                if (taskMemory.sourceType != null) {
                    appendLine("  Type: ${taskMemory.sourceType}")
                }
                if (taskMemory.sourceId != null) {
                    appendLine("  ID: ${taskMemory.sourceId}")
                }
                appendLine()
                appendLine("## Structured Data References")
                appendLine("  Graph Nodes: ${taskMemory.graphNodeKeys.size}")
                if (taskMemory.graphNodeKeys.isNotEmpty()) {
                    appendLine("  Node Keys:")
                    taskMemory.graphNodeKeys.take(10).forEach { key ->
                        appendLine("    - $key")
                    }
                    if (taskMemory.graphNodeKeys.size > 10) {
                        appendLine("    ... and ${taskMemory.graphNodeKeys.size - 10} more")
                    }
                }
                appendLine()
                appendLine("  RAG Documents: ${taskMemory.ragDocumentIds.size}")
                if (taskMemory.ragDocumentIds.isNotEmpty()) {
                    appendLine("  Document IDs:")
                    taskMemory.ragDocumentIds.take(5).forEach { id ->
                        appendLine("    - $id")
                    }
                    if (taskMemory.ragDocumentIds.size > 5) {
                        appendLine("    ... and ${taskMemory.ragDocumentIds.size - 5} more")
                    }
                }
                appendLine()
                if (taskMemory.structuredData.isNotEmpty()) {
                    appendLine("## Extracted Metadata")
                    taskMemory.structuredData.forEach { (key, value) ->
                        appendLine("  $key: $value")
                    }
                    appendLine()
                }
                appendLine("## Context Summary")
                appendLine()
                appendLine(taskMemory.contextSummary)
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("Use getNode() or getRelatedNodes() with above Graph keys to explore relationships.")
                appendLine("Use searchKnowledge() to find related content in RAG.")
                appendLine("Focus on analysis and actions - the data is already structured.")
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load task context" }
            throw IllegalStateException("TASK_MEMORY_LOAD_FAILED: ${e.message}", e)
        }
    }
}
