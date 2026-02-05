package com.jervis.koog.cost

import com.jervis.common.types.ProjectId
import com.jervis.entity.cost.LlmCostDocument
import com.jervis.repository.cost.LlmCostRepository
import com.jervis.service.project.ProjectService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

@Service
class CostTrackingService(
    private val llmCostRepository: LlmCostRepository,
    private val llmPriceService: LlmPriceService,
    private val projectService: ProjectService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun checkBudget(
        projectId: ProjectId,
        modelId: String,
        inputTokens: Int,
    ): Boolean {
        val project = projectService.getProjectById(projectId)
        val policy = project.costPolicy

        if (!policy.allowCloudModels && llmPriceService.isCloudModel(modelId)) {
            logger.warn { "CLOUD_MODELS_NOT_ALLOWED | projectId=$projectId | model=$modelId" }
            return false
        }

        val estimatedCost = llmPriceService.calculateCost(modelId, inputTokens, 4096)
        val monthlySpent = getMonthlySpent(projectId)

        if (!llmPriceService.hasBudget(policy.monthlyBudgetLimit, monthlySpent, estimatedCost)) {
            logger.warn {
                "BUDGET_EXCEEDED | projectId=$projectId | limit=${policy.monthlyBudgetLimit} | spent=$monthlySpent | estimated=$estimatedCost"
            }
            return false
        }

        return true
    }

    suspend fun recordRequest(
        projectId: ProjectId,
        modelId: String,
        provider: String,
        inputTokens: Int,
        outputTokens: Int,
    ) {
        val cost = llmPriceService.calculateCost(modelId, inputTokens, outputTokens)
        val doc =
            LlmCostDocument(
                projectId = projectId,
                modelId = modelId,
                provider = provider,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costUsd = cost,
            )
        llmCostRepository.save(doc)
        logger.info { "COST_RECORDED | projectId=$projectId | model=$modelId | cost=$cost USD" }
    }

    suspend fun getMonthlySpent(projectId: ProjectId): Double {
        val firstDayOfMonth =
            LocalDate
                .now()
                .withDayOfMonth(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
        val spent = llmCostRepository.sumCostByProjectIdSince(projectId, firstDayOfMonth) ?: 0.0
        logger.debug { "MONTHLY_SPENT | projectId=$projectId | spent=$spent USD" }
        return spent
    }
}
