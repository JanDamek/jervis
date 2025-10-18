package com.jervis.mapper

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.dto.PlanDto
import com.jervis.dto.PlanStepDto
import com.jervis.service.mcp.domain.ToolResult
import org.bson.types.ObjectId

fun PlanDto.toDomain() =
    Plan(
        id = ObjectId(id),
        contextId = ObjectId(contextId),
        originalQuestion = originalQuestion,
        originalLanguage = originalLanguage,
        englishQuestion = englishQuestion,
        questionChecklist = questionChecklist,
        initialRagQueries = initialRagQueries,
        status = status,
        steps = steps.map { it.toDomain() },
        contextSummary = contextSummary,
        finalAnswer = finalAnswer,
        thinkingSequence = thinkingSequence,
    )

fun Plan.toDto() =
    PlanDto(
        id = id.toString(),
        contextId = contextId.toString(),
        originalQuestion = originalQuestion,
        originalLanguage = originalLanguage,
        englishQuestion = englishQuestion,
        questionChecklist = questionChecklist,
        initialRagQueries = initialRagQueries,
        status = status,
        steps = steps.map { it.toDto() },
        contextSummary = contextSummary,
        finalAnswer = finalAnswer,
        thinkingSequence = thinkingSequence,
    )

fun PlanStepDto.toDomain() =
    PlanStep(
        id = ObjectId(id),
        order = order,
        planId = ObjectId(planId),
        contextId = ObjectId(contextId),
        stepToolName = PromptTypeEnum.valueOf(stepToolName),
        stepInstruction = stepInstruction,
        stepDependsOn = stepDependsOn,
        status = status,
        toolResult = toolResult?.let { ToolResult.ok(it) },
    )

fun PlanStep.toDto() =
    PlanStepDto(
        id = id.toString(),
        order = order,
        planId = planId.toString(),
        contextId = contextId.toString(),
        stepToolName = stepToolName.name,
        stepInstruction = stepInstruction,
        stepDependsOn = stepDependsOn,
        status = status,
        toolResult = toolResult?.toString(),
    )
