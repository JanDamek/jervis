package com.jervis.qualifier

import com.jervis.dto.pipeline.ActionType
import com.jervis.dto.pipeline.EstimatedComplexity
import com.jervis.dto.pipeline.SuggestedAgent
import com.jervis.knowledgebase.model.FullIngestResult
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Infers structured pipeline fields (actionType, estimatedComplexity, suggestedAgent)
 * from existing FullIngestResult fields when KB does not provide them directly.
 *
 * This bridges the gap between the current KB output (free-form suggestedActions)
 * and the typed enums needed for autonomous task routing.
 */
@Component
class ActionTypeInferrer {
    private val logger = KotlinLogging.logger {}

    /**
     * Mapping from suggestedActions strings to ActionType.
     * Order matters — first match wins.
     */
    private val actionMappings = listOf(
        // Code-related actions
        setOf("analyze_code", "create_application", "decompose_issue") to ActionType.CODE_FIX,
        setOf("review_code") to ActionType.CODE_REVIEW,
        // Communication actions
        setOf("reply_email", "answer_question") to ActionType.RESPOND_EMAIL,
        // Documentation actions
        setOf("update_documentation", "write_docs") to ActionType.UPDATE_DOCS,
        // Ticket management
        setOf("create_ticket", "create_issue") to ActionType.CREATE_TICKET,
        // Investigation
        setOf("investigate", "research", "design_architecture") to ActionType.INVESTIGATE,
        // Scheduling (treated as notification with side-effect)
        setOf("schedule_meeting") to ActionType.NOTIFY_ONLY,
    )

    /**
     * Complexity inference based on suggestedActions content.
     */
    private val complexActions = setOf(
        "create_application", "design_architecture",
    )
    private val mediumActions = setOf(
        "decompose_issue", "analyze_code",
    )
    private val simpleActions = setOf(
        "review_code", "reply_email", "answer_question",
        "update_documentation", "write_docs",
        "create_ticket", "create_issue",
    )
    private val trivialActions = setOf(
        "schedule_meeting",
    )

    data class InferredFields(
        val actionType: ActionType,
        val estimatedComplexity: EstimatedComplexity,
        val suggestedAgent: SuggestedAgent,
    )

    /**
     * Infer structured fields from FullIngestResult.
     * Uses explicitly provided values when available, falls back to inference.
     */
    fun infer(result: FullIngestResult): InferredFields {
        val actionType = inferActionType(result)
        val complexity = inferComplexity(result, actionType)
        val agent = inferAgent(actionType, complexity)

        logger.debug {
            "INFERRED: actionType=$actionType complexity=$complexity agent=$agent " +
                "suggestedActions=${result.suggestedActions} urgency=${result.urgency}"
        }

        return InferredFields(actionType, complexity, agent)
    }

    private fun inferActionType(result: FullIngestResult): ActionType {
        // Use explicit value if KB provided it
        result.actionType?.let { explicit ->
            return try {
                ActionType.valueOf(explicit)
            } catch (_: IllegalArgumentException) {
                logger.warn { "Unknown actionType from KB: $explicit, falling back to inference" }
                inferFromSuggestedActions(result)
            }
        }

        return inferFromSuggestedActions(result)
    }

    private fun inferFromSuggestedActions(result: FullIngestResult): ActionType {
        if (!result.hasActionableContent) return ActionType.NOTIFY_ONLY

        for ((actionSet, actionType) in actionMappings) {
            if (result.suggestedActions.any { it in actionSet }) {
                return actionType
            }
        }

        // Default: if actionable but no recognized action → investigate
        return ActionType.INVESTIGATE
    }

    private fun inferComplexity(result: FullIngestResult, actionType: ActionType): EstimatedComplexity {
        // Use explicit value if KB provided it
        result.estimatedComplexity?.let { explicit ->
            return try {
                EstimatedComplexity.valueOf(explicit)
            } catch (_: IllegalArgumentException) {
                logger.warn { "Unknown estimatedComplexity from KB: $explicit, falling back to inference" }
                inferComplexityFromActions(result, actionType)
            }
        }

        return inferComplexityFromActions(result, actionType)
    }

    private fun inferComplexityFromActions(result: FullIngestResult, actionType: ActionType): EstimatedComplexity {
        // Check specific actions first
        for (action in result.suggestedActions) {
            when (action) {
                in complexActions -> return EstimatedComplexity.COMPLEX
                in mediumActions -> return EstimatedComplexity.MEDIUM
                in simpleActions -> return EstimatedComplexity.SIMPLE
                in trivialActions -> return EstimatedComplexity.TRIVIAL
            }
        }

        // Fall back to action type defaults
        return when (actionType) {
            ActionType.CODE_FIX -> EstimatedComplexity.MEDIUM
            ActionType.CODE_REVIEW -> EstimatedComplexity.SIMPLE
            ActionType.RESPOND_EMAIL -> EstimatedComplexity.SIMPLE
            ActionType.UPDATE_DOCS -> EstimatedComplexity.SIMPLE
            ActionType.CREATE_TICKET -> EstimatedComplexity.TRIVIAL
            ActionType.INVESTIGATE -> EstimatedComplexity.MEDIUM
            ActionType.NOTIFY_ONLY -> EstimatedComplexity.TRIVIAL
        }
    }

    private fun inferAgent(actionType: ActionType, complexity: EstimatedComplexity): SuggestedAgent {
        return when (actionType) {
            ActionType.CODE_FIX -> SuggestedAgent.CODING
            ActionType.CODE_REVIEW -> SuggestedAgent.CODING
            ActionType.RESPOND_EMAIL -> SuggestedAgent.ORCHESTRATOR
            ActionType.UPDATE_DOCS -> SuggestedAgent.ORCHESTRATOR
            ActionType.CREATE_TICKET -> SuggestedAgent.ORCHESTRATOR
            ActionType.INVESTIGATE -> when (complexity) {
                EstimatedComplexity.COMPLEX -> SuggestedAgent.ORCHESTRATOR
                else -> SuggestedAgent.ORCHESTRATOR
            }
            ActionType.NOTIFY_ONLY -> SuggestedAgent.NONE
        }
    }
}
