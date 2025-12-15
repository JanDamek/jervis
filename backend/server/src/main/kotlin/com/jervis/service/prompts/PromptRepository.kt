package com.jervis.service.prompts

import com.jervis.configuration.prompts.CreativityLevel
import com.jervis.configuration.prompts.ModelParams
import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.dto.PendingTaskTypeEnum
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service

@Service
class PromptRepository(
    private val promptsConfig: PromptsConfiguration,
) {
    val goals = promptsConfig.pendingTaskGoals

    /**
     * Return PromptConfig for given prompt type. Currently only EMBEDDING is supported.
     */
    fun getPrompt(type: PromptTypeEnum): PromptConfig =
        when (type) {
            PromptTypeEnum.EMBEDDING ->
                PromptConfig(
                    systemPrompt = "You generate structured outputs for embedding pipelines.",
                    userPrompt = "",
                    modelParams = ModelParams(modelType = ModelTypeEnum.EMBEDDING, creativityLevel = CreativityLevel.LOW),
                )
        }

    @PostConstruct
    fun validateConfiguration() {
        promptsConfig.pendingTaskGoals.forEach { (key, goal) ->
            if (goal.schema.isBlank()) {
                throw IllegalStateException("Pending task type $key has empty or blank schema")
            }
        }

        val missingGoals = PendingTaskTypeEnum.entries.filter { it !in promptsConfig.pendingTaskGoals.keys }
        if (missingGoals.isNotEmpty()) {
            throw IllegalStateException(
                "Missing goal configurations for pending task types: $missingGoals. " +
                    "Available goals: ${promptsConfig.pendingTaskGoals.keys}. " +
                    "Add missing goals to pending-tasks-goals.yaml",
            )
        }
    }
}
