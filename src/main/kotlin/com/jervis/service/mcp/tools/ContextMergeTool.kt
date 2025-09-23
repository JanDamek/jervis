package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.execution.ExecutionNode
import com.jervis.domain.execution.MergeNode
import com.jervis.domain.execution.ParallelGroup
import com.jervis.domain.execution.TaskStep
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.StepStatus
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

/**
 * Context merge tool that combines outputs from multiple parallel execution branches
 * and provides structured context for dependent steps in the execution tree.
 */
@Component
class ContextMergeTool(
    override val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}
    
    override val name: PromptTypeEnum = PromptTypeEnum.CONTEXT_MERGE

    suspend override fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "CONTEXT_MERGE_START: Merging context from parallel execution branches" }
        
        try {
            // Parse input node IDs from task description
            // Expected format: "nodeId1,nodeId2,nodeId3" or similar
            val inputNodeIds = parseInputNodeIds(taskDescription)
            
            if (inputNodeIds.isEmpty()) {
                return ToolResult.error(
                    "CONTEXT_MERGE failed: No input node IDs provided in task description",
                    "Task description should contain comma-separated node IDs to merge"
                )
            }
            
            logger.debug { "CONTEXT_MERGE_INPUT_NODES: Merging outputs from ${inputNodeIds.size} nodes: $inputNodeIds" }
            
            // Find completed steps from the plan that match the input node IDs
            val inputSteps = plan.steps.filter { step ->
                inputNodeIds.contains(step.id.toString()) && step.status == StepStatus.DONE
            }
            
            if (inputSteps.isEmpty()) {
                return ToolResult.error(
                    "CONTEXT_MERGE failed: No completed steps found for the specified input nodes",
                    "All input nodes must be completed before merging"
                )
            }
            
            logger.debug { "CONTEXT_MERGE_FOUND_STEPS: Found ${inputSteps.size} completed steps to merge" }
            
            // Merge the outputs
            val mergedContext = mergeStepOutputs(inputSteps)
            
            logger.info { "CONTEXT_MERGE_SUCCESS: Successfully merged context from ${inputSteps.size} parallel branches" }
            
            return ToolResult.success(
                toolName = "CONTEXT_MERGE",
                summary = "Merged context from ${inputSteps.size} parallel execution branches",
                content = mergedContext,
                "Input Steps: ${inputSteps.map { "${it.name} (${it.id})" }}",
                "Merged Content Length: ${mergedContext.length} characters"
            )
            
        } catch (e: Exception) {
            logger.error(e) { "CONTEXT_MERGE_ERROR: Failed to merge context" }
            return ToolResult.error(
                "CONTEXT_MERGE failed with error: ${e.message}",
                e.message
            )
        }
    }
    
    /**
     * Parse input node IDs from task description.
     * Supports various formats: comma-separated, space-separated, or JSON array
     */
    private fun parseInputNodeIds(taskDescription: String): List<String> {
        return when {
            // JSON array format: ["id1", "id2", "id3"]
            taskDescription.trim().startsWith("[") && taskDescription.trim().endsWith("]") -> {
                taskDescription.trim()
                    .removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"", "'") }
                    .filter { it.isNotBlank() }
            }
            
            // Comma-separated format: id1,id2,id3
            taskDescription.contains(",") -> {
                taskDescription.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
            
            // Space-separated format: id1 id2 id3
            taskDescription.contains(" ") -> {
                taskDescription.split("\\s+".toRegex())
                    .filter { it.isNotBlank() }
            }
            
            // Single ID
            taskDescription.isNotBlank() -> listOf(taskDescription.trim())
            
            else -> emptyList()
        }
    }
    
    /**
     * Merge outputs from multiple steps into a structured context
     */
    private fun mergeStepOutputs(steps: List<com.jervis.domain.plan.PlanStep>): String {
        if (steps.isEmpty()) return ""
        
        val mergedBuilder = StringBuilder()
        
        mergedBuilder.appendLine("=== MERGED CONTEXT FROM PARALLEL EXECUTION ===")
        mergedBuilder.appendLine()
        
        // Group steps by type for better organization
        val stepsByType = steps.groupBy { it.name }
        
        stepsByType.forEach { (stepType, stepsOfType) ->
            mergedBuilder.appendLine("## $stepType Results (${stepsOfType.size} ${if (stepsOfType.size == 1) "step" else "steps"})")
            mergedBuilder.appendLine()
            
            stepsOfType.forEach { step ->
                val output = step.output?.output ?: "No output available"
                mergedBuilder.appendLine("### Step: ${step.taskDescription}")
                mergedBuilder.appendLine("Status: ${step.status}")
                
                // Summarize long outputs
                val trimmedOutput = if (output.length > 1000) {
                    "${output.take(1000)}... [truncated, full output ${output.length} chars]"
                } else {
                    output
                }
                
                mergedBuilder.appendLine("Output:")
                mergedBuilder.appendLine(trimmedOutput)
                mergedBuilder.appendLine()
            }
            
            mergedBuilder.appendLine("---")
            mergedBuilder.appendLine()
        }
        
        // Add summary section
        mergedBuilder.appendLine("## Merge Summary")
        mergedBuilder.appendLine("- Total steps merged: ${steps.size}")
        mergedBuilder.appendLine("- Step types: ${stepsByType.keys.joinToString(", ")}")
        mergedBuilder.appendLine("- All steps completed successfully")
        mergedBuilder.appendLine()
        
        // Add key findings if we can extract them
        val keyFindings = extractKeyFindings(steps)
        if (keyFindings.isNotEmpty()) {
            mergedBuilder.appendLine("## Key Findings")
            keyFindings.forEach { finding ->
                mergedBuilder.appendLine("- $finding")
            }
            mergedBuilder.appendLine()
        }
        
        mergedBuilder.appendLine("=== END MERGED CONTEXT ===")
        
        return mergedBuilder.toString()
    }
    
    /**
     * Extract key findings from step outputs for summary
     */
    private fun extractKeyFindings(steps: List<com.jervis.domain.plan.PlanStep>): List<String> {
        val findings = mutableListOf<String>()
        
        steps.forEach { step ->
            val output = step.output?.output ?: ""
            
            // Look for common patterns that indicate findings
            when (step.name) {
                "RAG_QUERY" -> {
                    if (output.contains("found") || output.contains("located")) {
                        findings.add("${step.name}: Located relevant information in knowledge base")
                    }
                }
                "FILE_LISTING" -> {
                    val fileCount = output.count { it == '\n' }
                    if (fileCount > 0) {
                        findings.add("${step.name}: Discovered $fileCount files in project structure")
                    }
                }
                "CODE_EXTRACTOR" -> {
                    if (output.contains("class") || output.contains("method") || output.contains("function")) {
                        findings.add("${step.name}: Extracted relevant code components")
                    }
                }
                "JOERN" -> {
                    if (output.contains("analysis") || output.contains("call graph")) {
                        findings.add("${step.name}: Performed static code analysis")
                    }
                }
                else -> {
                    // Generic finding extraction
                    if (output.length > 50 && !output.contains("error", ignoreCase = true)) {
                        findings.add("${step.name}: Completed successfully with ${output.length} chars output")
                    }
                }
            }
        }
        
        return findings
    }
}