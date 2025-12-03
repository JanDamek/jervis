package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.domain.plan.Plan
import mu.KotlinLogging

/**
 * TaskRoutingTool - Routing decision for Qualifier agent.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - Used by KoogQualifierAgent (CPU) to decide task completion vs GPU routing
 * - After structuring data (Graph + RAG), agent decides next step
 * - DONE: Simple structuring complete, no further analysis needed
 * - READY_FOR_GPU: Complex analysis required, route to KoogWorkflowAgent
 *
 * Decision Criteria:
 * - DONE: Data indexed and structured, no action items, no complex questions
 * - READY_FOR_GPU: Requires decision making, user response, complex analysis, code changes
 *
 * Context Passing:
 * - READY_FOR_GPU tasks include contextSummary for GPU agent
 * - Context summary contains: key findings, action items, questions, structured data references
 * - GPU agent loads this via TaskMemory instead of re-reading everything
 */
@LLMDescription("Route task completion: mark as DONE or escalate to GPU for complex analysis")
class TaskRoutingTool(
    private val plan: Plan,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription("""Complete task qualification and decide routing.

Use DONE when:
- Document is indexed and structured (Graph + RAG)
- No action items or decisions required
- Simple informational content
- Routine updates (status changes, minor commits, etc.)

Use READY_FOR_GPU when:
- User action required (respond to email, update Jira, review code)
- Complex decision making needed
- Analysis or investigation required
- Code changes or architecture decisions
- Multiple entities need coordination
- Task mentions current user or requires user's expertise

Context Summary (for READY_FOR_GPU):
- Brief overview of the structured data
- Key findings and action items
- Questions that need answering
- Graph node keys for quick reference
- RAG document IDs for full content retrieval

This routing decision determines CPU vs GPU usage and cost efficiency.""")
    fun routeTask(
        @LLMDescription("Routing decision: DONE or READY_FOR_GPU")
        routing: String,

        @LLMDescription("Brief explanation of routing decision (1-2 sentences)")
        reason: String,

        @LLMDescription("Context summary for GPU agent (required if READY_FOR_GPU, optional if DONE)")
        contextSummary: String = "",
    ): String {
        val routingUpper = routing.uppercase().trim()

        if (routingUpper !in setOf("DONE", "READY_FOR_GPU")) {
            throw IllegalArgumentException("Invalid routing: $routing. Use DONE or READY_FOR_GPU")
        }

        logger.info { "TASK_ROUTING: decision=$routingUpper, reason='$reason'" }

        // Store routing decision in plan for BackgroundEngine to process
        plan.metadata["routing_decision"] = routingUpper
        plan.metadata["routing_reason"] = reason

        if (routingUpper == "READY_FOR_GPU") {
            if (contextSummary.isBlank()) {
                throw IllegalArgumentException("Context summary required for READY_FOR_GPU routing")
            }
            plan.metadata["context_summary"] = contextSummary
        }

        return buildString {
            appendLine("✓ Task routing decision made")
            appendLine("  Decision: $routingUpper")
            appendLine("  Reason: $reason")

            when (routingUpper) {
                "DONE" -> {
                    appendLine()
                    appendLine("Task complete:")
                    appendLine("  - Data indexed to RAG")
                    appendLine("  - Graph nodes created")
                    appendLine("  - No further action needed")
                    appendLine("  - Task will be marked as DONE")
                }
                "READY_FOR_GPU" -> {
                    appendLine()
                    appendLine("Escalating to GPU:")
                    appendLine("  - Complex analysis required")
                    appendLine("  - Task will be routed to KoogWorkflowAgent")
                    appendLine("  - Context summary prepared (${contextSummary.length} chars)")
                    appendLine()
                    appendLine("Context Summary:")
                    appendLine(contextSummary)
                }
            }
        }
    }
}
