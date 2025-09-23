package com.jervis.service.agent.execution

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.execution.*
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.StepStatus
import com.jervis.service.mcp.McpToolRegistry
import com.jervis.service.mcp.domain.ToolResult
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Tree-based executor that handles ExecutionNode trees with parallel execution,
 * dependency resolution, and context merging.
 */
@Service
class TreeExecutor(
    private val mcpToolRegistry: McpToolRegistry,
) {
    private val logger = KotlinLogging.logger {}
    
    // Cache to store completed nodes and their outputs for dependency resolution
    private val nodeCache = ConcurrentHashMap<ObjectId, ExecutionNode>()

    suspend fun executeTree(
        context: TaskContext,
        plan: Plan,
        executionTree: List<ExecutionNode>,
    ): Boolean {
        if (executionTree.isEmpty()) {
            logger.warn { "TREE_EXECUTOR_EMPTY: No execution nodes to process" }
            return true
        }

        logger.info { "TREE_EXECUTOR_START: Processing ${executionTree.size} root execution nodes" }
        
        try {
            // Clear cache for new execution
            nodeCache.clear()
            
            // Execute all root nodes (potentially in parallel)
            val rootResults = coroutineScope {
                executionTree.map { rootNode ->
                    async { executeNode(context, plan, rootNode) }
                }.awaitAll()
            }
            
            val allSuccessful = rootResults.all { it }
            
            logger.info { "TREE_EXECUTOR_COMPLETE: All nodes processed. Success: $allSuccessful" }
            return allSuccessful
            
        } catch (e: Exception) {
            logger.error(e) { "TREE_EXECUTOR_ERROR: Tree execution failed" }
            return false
        }
    }

    private suspend fun executeNode(
        context: TaskContext,
        plan: Plan,
        node: ExecutionNode,
    ): Boolean {
        logger.debug { "TREE_EXECUTOR_NODE_START: Processing node ${node.id} type ${node::class.simpleName}" }
        
        return when (node) {
            is TaskStep -> executeTaskStep(context, plan, node)
            is ParallelGroup -> executeParallelGroup(context, plan, node)
            is MergeNode -> executeMergeNode(context, plan, node)
            is SequentialGroup -> executeSequentialGroup(context, plan, node)
            is PlanningNode -> executePlanningNode(context, plan, node)
        }
    }

    private suspend fun executeTaskStep(
        context: TaskContext,
        plan: Plan,
        taskStep: TaskStep,
    ): Boolean {
        if (!canExecuteTaskStep(taskStep)) {
            logger.debug { "TREE_EXECUTOR_TASK_BLOCKED: Task step ${taskStep.id} dependencies not satisfied" }
            return false
        }

        logger.info { "TREE_EXECUTOR_TASK_START: Executing task step ${taskStep.name} (${taskStep.id})" }
        
        try {
            taskStep.status = StepStatus.PENDING
            taskStep.updatedAt = Instant.now()
            
            val tool = mcpToolRegistry.byName(PromptTypeEnum.valueOf(taskStep.name))
            
            // Build step context from dependencies
            val stepContext = buildStepContextFromDependencies(taskStep)
            
            val result = tool.execute(
                context = context,
                plan = plan,
                taskDescription = taskStep.taskDescription,
                stepContext = stepContext,
            )
            
            when (result) {
                is ToolResult.Ok, is ToolResult.Ask -> {
                    taskStep.output = result
                    taskStep.status = StepStatus.DONE
                    taskStep.updatedAt = Instant.now()
                    
                    // Cache the completed node
                    nodeCache[taskStep.id] = taskStep
                    
                    // Record summary - preserve full context from response
                    appendSummaryLine(plan, taskStep.id, taskStep.name, result)
                    
                    logger.info { "TREE_EXECUTOR_TASK_SUCCESS: Task step ${taskStep.name} completed successfully" }
                    return true
                }
                
                is ToolResult.Error, is ToolResult.Stop -> {
                    taskStep.output = result
                    taskStep.status = StepStatus.FAILED
                    taskStep.updatedAt = Instant.now()
                    
                    // Record summary even for failed tasks - preserve all results
                    appendSummaryLine(plan, taskStep.id, taskStep.name, result)
                    
                    logger.error { "TREE_EXECUTOR_TASK_FAILED: Task step ${taskStep.name} failed: ${result.output}" }
                    return false
                }
                
                else -> {
                    logger.warn { "TREE_EXECUTOR_TASK_UNEXPECTED: Unexpected result type from ${taskStep.name}" }
                    return false
                }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "TREE_EXECUTOR_TASK_EXCEPTION: Task step ${taskStep.name} threw exception" }
            taskStep.status = StepStatus.FAILED
            taskStep.output = ToolResult.error("Task execution failed: ${e.message}")
            taskStep.updatedAt = Instant.now()
            return false
        }
    }

    private suspend fun executeParallelGroup(
        context: TaskContext,
        plan: Plan,
        parallelGroup: ParallelGroup,
    ): Boolean {
        logger.info { "TREE_EXECUTOR_PARALLEL_START: Executing ${parallelGroup.nodes.size} nodes in parallel" }
        
        parallelGroup.status = StepStatus.PENDING
        parallelGroup.updatedAt = Instant.now()
        
        try {
            // Execute all child nodes concurrently - ALWAYS wait for ALL to complete
            val results = coroutineScope {
                parallelGroup.nodes.map { childNode ->
                    async { 
                        try {
                            executeNode(context, plan, childNode)
                        } catch (e: Exception) {
                            logger.error(e) { "TREE_EXECUTOR_PARALLEL_NODE_EXCEPTION: Node ${childNode.id} failed" }
                            false
                        }
                    }
                }.awaitAll() // Wait for ALL branches to complete, even if some fail
            }
            
            // Process and record ALL results before determining final status
            results.forEachIndexed { index, success ->
                val node = parallelGroup.nodes[index]
                logger.info { "TREE_EXECUTOR_PARALLEL_RESULT: Node ${node.id} (${node::class.simpleName}) completed with success: $success" }
                
                // Record summary for all nodes, including failed ones
                if (node is TaskStep) {
                    appendSummaryLine(plan, node.id, node.name, node.output)
                }
            }
            
            val allSuccessful = results.all { it }
            val hasFailures = results.any { !it }
            
            // Always insert results, regardless of success/failure status
            parallelGroup.status = if (allSuccessful) StepStatus.DONE else StepStatus.FAILED
            parallelGroup.updatedAt = Instant.now()
            
            // Cache the parallel group result (even if some branches failed)
            nodeCache[parallelGroup.id] = parallelGroup
            
            if (hasFailures) {
                logger.warn { "TREE_EXECUTOR_PARALLEL_HAS_FAILURES: ${results.count { !it }} out of ${results.size} branches failed, but all results were inserted" }
                
                // On FAIL, trigger new query by updating plan status but preserve all results
                if (!allSuccessful) {
                    logger.info { "TREE_EXECUTOR_PARALLEL_TRIGGER_REPLAN: Triggering replanning due to parallel failures" }
                    // The caller (PlanExecutor) should handle replanning based on the returned false status
                }
            }
            
            logger.info { "TREE_EXECUTOR_PARALLEL_COMPLETE: Parallel group finished. Success: $allSuccessful, Results inserted: ${results.size}" }
            return allSuccessful
            
        } catch (e: Exception) {
            logger.error(e) { "TREE_EXECUTOR_PARALLEL_EXCEPTION: Parallel group execution failed" }
            parallelGroup.status = StepStatus.FAILED
            parallelGroup.updatedAt = Instant.now()
            return false
        }
    }

    private suspend fun executeMergeNode(
        context: TaskContext,
        plan: Plan,
        mergeNode: MergeNode,
    ): Boolean {
        logger.info { "TREE_EXECUTOR_MERGE_START: Merging context from ${mergeNode.inputNodes.size} input nodes" }
        
        // Check if all input nodes are completed
        val inputNodesCompleted = mergeNode.inputNodes.all { inputNodeId ->
            nodeCache.containsKey(inputNodeId) && nodeCache[inputNodeId]?.isCompleted() == true
        }
        
        if (!inputNodesCompleted) {
            logger.debug { "TREE_EXECUTOR_MERGE_BLOCKED: Not all input nodes completed yet" }
            return false
        }
        
        mergeNode.status = StepStatus.PENDING
        mergeNode.updatedAt = Instant.now()
        
        try {
            // Use CONTEXT_MERGE tool to merge the inputs
            val contextMergeTool = mcpToolRegistry.byName(PromptTypeEnum.CONTEXT_MERGE)
            
            // Create task description with input node IDs
            val taskDescription = mergeNode.inputNodes.joinToString(",")
            
            val result = contextMergeTool.execute(
                context = context,
                plan = plan,
                taskDescription = taskDescription,
                stepContext = "",
            )
            
            when (result) {
                is ToolResult.Ok -> {
                    mergeNode.output = result
                    mergeNode.status = StepStatus.DONE
                    mergeNode.updatedAt = Instant.now()
                    
                    // Cache the completed merge node
                    nodeCache[mergeNode.id] = mergeNode
                    
                    // Execute child nodes that depend on this merge
                    val childResults = coroutineScope {
                        mergeNode.childNodes.map { childNode ->
                            async { executeNode(context, plan, childNode) }
                        }.awaitAll()
                    }
                    
                    val allChildrenSuccessful = childResults.all { it }
                    
                    logger.info { "TREE_EXECUTOR_MERGE_SUCCESS: Merge completed and children executed. Success: $allChildrenSuccessful" }
                    return allChildrenSuccessful
                }
                
                else -> {
                    mergeNode.status = StepStatus.FAILED
                    mergeNode.output = result
                    mergeNode.updatedAt = Instant.now()
                    
                    logger.error { "TREE_EXECUTOR_MERGE_FAILED: Context merge failed: ${result.output}" }
                    return false
                }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "TREE_EXECUTOR_MERGE_EXCEPTION: Merge node execution failed" }
            mergeNode.status = StepStatus.FAILED
            mergeNode.output = ToolResult.error("Merge execution failed: ${e.message}")
            mergeNode.updatedAt = Instant.now()
            return false
        }
    }

    private suspend fun executeSequentialGroup(
        context: TaskContext,
        plan: Plan,
        sequentialGroup: SequentialGroup,
    ): Boolean {
        logger.info { "TREE_EXECUTOR_SEQUENTIAL_START: Executing ${sequentialGroup.nodes.size} nodes sequentially" }
        
        sequentialGroup.status = StepStatus.PENDING
        sequentialGroup.updatedAt = Instant.now()
        
        try {
            // Execute child nodes one by one
            for (childNode in sequentialGroup.nodes) {
                val success = executeNode(context, plan, childNode)
                if (!success) {
                    sequentialGroup.status = StepStatus.FAILED
                    sequentialGroup.updatedAt = Instant.now()
                    logger.error { "TREE_EXECUTOR_SEQUENTIAL_FAILED: Sequential execution failed at node ${childNode.id}" }
                    return false
                }
            }
            
            sequentialGroup.status = StepStatus.DONE
            sequentialGroup.updatedAt = Instant.now()
            nodeCache[sequentialGroup.id] = sequentialGroup
            
            logger.info { "TREE_EXECUTOR_SEQUENTIAL_SUCCESS: All sequential nodes completed" }
            return true
            
        } catch (e: Exception) {
            logger.error(e) { "TREE_EXECUTOR_SEQUENTIAL_EXCEPTION: Sequential group execution failed" }
            sequentialGroup.status = StepStatus.FAILED
            sequentialGroup.updatedAt = Instant.now()
            return false
        }
    }

    private suspend fun executePlanningNode(
        context: TaskContext,
        plan: Plan,
        planningNode: PlanningNode,
    ): Boolean {
        logger.info { "TREE_EXECUTOR_PLANNING_START: Dynamic planning node ${planningNode.id}" }
        
        // Check if dependencies are satisfied
        val dependenciesCompleted = planningNode.dependsOn.all { depId ->
            nodeCache.containsKey(depId) && nodeCache[depId]?.isCompleted() == true
        }
        
        if (!dependenciesCompleted) {
            logger.debug { "TREE_EXECUTOR_PLANNING_BLOCKED: Planning dependencies not satisfied" }
            return false
        }
        
        planningNode.status = StepStatus.PENDING
        planningNode.updatedAt = Instant.now()
        
        try {
            // Use PLANNER tool for dynamic planning
            val plannerTool = mcpToolRegistry.byName(PromptTypeEnum.PLANNER)
            
            val stepContext = buildStepContextFromDependencies(planningNode)
            
            val result = plannerTool.execute(
                context = context,
                plan = plan,
                taskDescription = planningNode.taskDescription,
                stepContext = stepContext,
            )
            
            when (result) {
                is ToolResult.Ok -> {
                    planningNode.output = result
                    planningNode.status = StepStatus.DONE
                    planningNode.updatedAt = Instant.now()
                    
                    // Dynamic planning completed successfully - result stored in node output
                    nodeCache[planningNode.id] = planningNode
                    
                    logger.info { "TREE_EXECUTOR_PLANNING_SUCCESS: Dynamic planning completed" }
                    return true
                }
                
                else -> {
                    planningNode.status = StepStatus.FAILED
                    planningNode.output = result
                    planningNode.updatedAt = Instant.now()
                    
                    logger.error { "TREE_EXECUTOR_PLANNING_FAILED: Dynamic planning failed: ${result.output}" }
                    return false
                }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "TREE_EXECUTOR_PLANNING_EXCEPTION: Planning node execution failed" }
            planningNode.status = StepStatus.FAILED
            planningNode.output = ToolResult.error("Planning execution failed: ${e.message}")
            planningNode.updatedAt = Instant.now()
            return false
        }
    }

    private fun canExecuteTaskStep(taskStep: TaskStep): Boolean {
        return taskStep.dependsOn.all { depId ->
            nodeCache.containsKey(depId) && nodeCache[depId]?.isCompleted() == true
        }
    }

    private fun summarizeToolResult(toolResult: ToolResult?): String = when (toolResult) {
        is ToolResult.Ok -> toolResult.output // Preserve full response without truncation
        is ToolResult.Error -> toolResult.errorMessage ?: "Unknown error"
        is ToolResult.Ask -> toolResult.output // Preserve full response without truncation
        is ToolResult.Stop -> toolResult.reason
        is ToolResult.InsertStep -> "Insert step: ${toolResult.stepToInsert.name}"
        null -> "No output"
    }

    private fun appendSummaryLine(
        plan: Plan,
        stepId: ObjectId,
        toolName: String,
        toolResult: ToolResult?,
    ) {
        val summary = summarizeToolResult(toolResult)
        val truncatedSummary =
            if (toolName == "RAG_QUERY") {
                summary // Preserve full RAG results for proper context
            } else {
                summary.take(2000) // Increased limit for better context, but still bounded
            }

        val line = "Step $stepId: $toolName → $truncatedSummary"
        val prefix = plan.contextSummary?.takeIf { it.isNotBlank() }?.plus("\n") ?: ""
        plan.contextSummary = prefix + line
    }

    private fun buildStepContextFromDependencies(node: ExecutionNode): String {
        val dependencies = when (node) {
            is TaskStep -> node.dependsOn
            is PlanningNode -> node.dependsOn
            else -> emptyList()
        }
        
        if (dependencies.isEmpty()) return ""
        
        val contextBuilder = StringBuilder()
        contextBuilder.appendLine("=== CONTEXT FROM DEPENDENCIES ===")
        
        dependencies.forEach { depId ->
            val depNode = nodeCache[depId]
            if (depNode != null && depNode.output != null) {
                contextBuilder.appendLine()
                contextBuilder.appendLine("## Dependency: ${depNode.id}")
                contextBuilder.appendLine("Type: ${depNode::class.simpleName}")
                contextBuilder.appendLine("Output:")
                contextBuilder.appendLine(depNode.output?.output ?: "No output")
                contextBuilder.appendLine("---")
            }
        }
        
        return contextBuilder.toString()
    }
}