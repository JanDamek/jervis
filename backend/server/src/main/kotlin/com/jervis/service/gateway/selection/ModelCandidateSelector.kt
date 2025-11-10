package com.jervis.service.gateway.selection

import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.model.ModelTypeEnum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import org.springframework.stereotype.Service

/**
 * Service responsible for selecting appropriate LLM model candidates based on various criteria.
 * Handles filtering by model type, quick mode, and token requirements.
 */
@Service
class ModelCandidateSelector(
    private val modelsProperties: ModelsProperties,
) {
    /**
     * Selects model candidates based on model type, quick mode preference, and estimated token requirements.
     * Always returns at least one model (the one with the highest token capacity) to ensure processing can proceed.
     */
    fun selectCandidates(
        modelTypeEnum: ModelTypeEnum,
        quickModeOnly: Boolean,
        estimatedTokens: Int,
    ): Flow<ModelsProperties.ModelDetail> {
        val baseModels = modelsProperties.models[modelTypeEnum] ?: emptyList()

        if (baseModels.isEmpty()) {
            return emptyFlow()
        }

        // Apply quick mode filter first
        val quickFilteredModels =
            baseModels.filter { candidate ->
                !quickModeOnly || candidate.quick
            }

        if (quickFilteredModels.isEmpty()) {
            return emptyFlow()
        }

        // Try to find models that can handle the estimated tokens
        val capacityFilteredModels =
            quickFilteredModels.filter { candidate ->
                hasCapacityForTokens(candidate, estimatedTokens)
            }

        val selectedModels =
            capacityFilteredModels.ifEmpty {
                val largestModel =
                    quickFilteredModels.maxByOrNull { it.contextLength ?: 0 }
                        ?: error("No models available after filtering")
                listOf(largestModel)
            }

        return selectedModels.asFlow()
    }

    /**
     * Checks if a model candidate has sufficient token capacity for the estimated requirements.
     */
    private fun hasCapacityForTokens(
        candidate: ModelsProperties.ModelDetail,
        estimatedTokens: Int,
    ): Boolean = candidate.contextLength?.let { maxTokens -> maxTokens >= estimatedTokens } ?: true
}
