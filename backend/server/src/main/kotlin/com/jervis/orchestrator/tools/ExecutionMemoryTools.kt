package com.jervis.orchestrator.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-term execution memory for multi-step reasoning.
 *
 * Allows orchestrator to:
 * - Store intermediate findings during execution
 * - Build up context across multiple tool calls
 * - Track dependencies between sub-goals
 * - Remember what was already validated/searched
 *
 * Memory is per-correlationId and cleared after task completion.
 */
class ExecutionMemoryTools(
    private val task: TaskDocument,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}

        // In-memory storage per correlationId
        private val executionMemory = ConcurrentHashMap<String, MutableMap<String, String>>()

        /**
         * Restore execution memory from checkpoint.
         * Called by OrchestratorAgent on session resume.
         */
        fun restoreMemory(correlationId: String, memory: Map<String, String>) {
            if (memory.isNotEmpty()) {
                executionMemory[correlationId] = memory.toMutableMap()
                logger.info {
                    "EXECUTION_MEMORY_RESTORED | correlationId=$correlationId | entries=${memory.size}"
                }
            }
        }

        /**
         * Get current execution memory for checkpoint serialization.
         * Called by OrchestratorAgent when saving checkpoint.
         */
        fun getMemory(correlationId: String): Map<String, String> {
            return executionMemory[correlationId]?.toMap() ?: emptyMap()
        }
    }

    @Tool
    @LLMDescription(
        """Store intermediate finding or result during multi-step execution.

        Use this to remember:
        - Sub-goal results that other sub-goals depend on
        - Validated information you'll need later
        - Technology versions discovered
        - File paths or class names found

        Parameters:
        - key: Memory key (e.g., "koog_best_practices", "found_class_OrganizationAgent")
        - value: Content to remember (can be JSON, text, code snippet)
        - description: What this memory entry is for (for logging/debugging)

        Returns: Confirmation with memory size

        Example: Store "best practices found on web" before using them in next step
        """
    )
    suspend fun rememberIntermediate(
        @LLMDescription("Memory key (e.g., 'koog_patterns', 'class_location')")
        key: String,
        @LLMDescription("Content to store (text, JSON, code, path)")
        value: String,
        @LLMDescription("Human-readable description of what this stores")
        description: String,
    ): String {
        val memory = executionMemory.getOrPut(task.correlationId) { ConcurrentHashMap() }
        memory[key] = value

        logger.info {
            "💭 REMEMBER_INTERMEDIATE | key=$key | valueLength=${value.length} | " +
            "description=$description | memorySize=${memory.size} | correlationId=${task.correlationId}"
        }

        return """
        {
            "success": true,
            "key": "$key",
            "memorySize": ${memory.size},
            "message": "Stored: $description"
        }
        """.trimIndent()
    }

    @Tool
    @LLMDescription(
        """Recall intermediate finding stored earlier in execution.

        Use this when:
        - Next sub-goal depends on previous sub-goal result
        - Need to reference discovered information
        - Want to check what was already validated

        Parameters:
        - key: Memory key used in rememberIntermediate()

        Returns: Stored value or error if not found

        Example: Recall "koog_best_practices" before applying them to code
        """
    )
    suspend fun recallIntermediate(
        @LLMDescription("Memory key to retrieve (same as used in rememberIntermediate)")
        key: String,
    ): String {
        val memory = executionMemory[task.correlationId]
        val value = memory?.get(key)

        if (value != null) {
            logger.info {
                "💭 RECALL_INTERMEDIATE | key=$key | valueLength=${value.length} | " +
                "correlationId=${task.correlationId}"
            }
            return value
        } else {
            logger.warn {
                "⚠️ RECALL_FAILED | key=$key | memorySize=${memory?.size ?: 0} | " +
                "correlationId=${task.correlationId}"
            }
            return """{"error": "Key '$key' not found in execution memory"}"""
        }
    }

    @Tool
    @LLMDescription(
        """List all intermediate findings stored during this execution.

        Use this to:
        - Review what you've discovered so far
        - Check if information was already gathered
        - Avoid redundant searches/validations

        Returns: JSON with all memory keys and descriptions
        """
    )
    suspend fun listIntermediateMemory(): String {
        val memory = executionMemory[task.correlationId] ?: emptyMap()

        logger.info {
            "💭 LIST_MEMORY | memorySize=${memory.size} | correlationId=${task.correlationId}"
        }

        val entries = memory.entries.joinToString(",\n") { (key, value) ->
            """  "$key": "${value.take(100)}${if (value.length > 100) "..." else ""}""""
        }

        return """
        {
            "memorySize": ${memory.size},
            "entries": {
                $entries
            }
        }
        """.trimIndent()
    }

    @Tool
    @LLMDescription(
        """Clear execution memory after task completion or when starting fresh.

        Use this:
        - When all sub-goals are complete
        - Before starting completely new task
        - To free up memory

        Returns: Confirmation of cleared entries count
        """
    )
    suspend fun clearExecutionMemory(): String {
        val memory = executionMemory.remove(task.correlationId)
        val clearedCount = memory?.size ?: 0

        logger.info {
            "💭 CLEAR_MEMORY | clearedCount=$clearedCount | correlationId=${task.correlationId}"
        }

        return """
        {
            "success": true,
            "clearedEntries": $clearedCount,
            "message": "Execution memory cleared"
        }
        """.trimIndent()
    }
}
