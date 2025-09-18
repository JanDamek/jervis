package com.jervis.service.gateway.selection

import com.jervis.configuration.ModelsProperties
import com.jervis.domain.model.ModelType
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux

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
     */
    fun selectCandidates(
        modelType: ModelType,
        quickModeOnly: Boolean,
        estimatedTokens: Int,
    ): Flux<ModelsProperties.ModelDetail> {
        val baseModels = modelsProperties.models[modelType] ?: emptyList()

        val filteredModels =
            baseModels
                .asSequence()
                .filter { candidate -> !quickModeOnly || candidate.quick }
                .filter { candidate -> hasCapacityForTokens(candidate, estimatedTokens) }
                .toList()

        return Flux.fromIterable(filteredModels)
    }

    /**
     * Checks if a model candidate has sufficient token capacity for the estimated requirements.
     */
    private fun hasCapacityForTokens(
        candidate: ModelsProperties.ModelDetail,
        estimatedTokens: Int,
    ): Boolean = candidate.maxTokens?.let { maxTokens -> maxTokens >= estimatedTokens } ?: true
}
