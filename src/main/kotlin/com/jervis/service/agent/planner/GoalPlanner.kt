package com.jervis.service.agent.planner

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.GoalDto
import com.jervis.domain.plan.GoalsDto
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Enhanced GoalPlanner with simplified discovery handling.
 * Uses functional approach with proper error handling and configuration.
 */
@Service
class GoalPlanner(
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Creates structured goals from user request and simple discovery context.
     * Uses functional Result pattern with simplified discovery data.
     */
    suspend fun createGoals(
        context: TaskContext,
        plan: Plan,
        discoveryResult: String,
    ): List<GoalDto> {
        val result =
            llmGateway.callLlm(
                type = PromptTypeEnum.PLANNING_CREATE_GOALS,
                mappingValue =
                    mapOf(
                        "planEnglishQuestion" to plan.englishQuestion,
                        "discoveryResult" to discoveryResult,
                        "questionChecklistText" to plan.questionChecklist.joinToString { "$it, " },
                    ),
                quick = context.quick,
                responseSchema = GoalsDto(),
            )

        logger.info { "Goals created successfully: ${result.goals.size} goals" }
        return result.goals
    }
}
