package com.jervis.service.prompts

import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.dto.PendingTaskTypeEnum
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service

@Service
class PromptRepository(
    private val promptsConfig: PromptsConfiguration,
) {
    @PostConstruct
    fun validateConfiguration() {
        val promptKeys = promptsConfig.prompts.keys
        val toolKeys = promptsConfig.tools.keys
        val duplicateKeys = promptKeys.intersect(toolKeys)

        if (duplicateKeys.isNotEmpty()) {
            throw IllegalStateException("Duplicate keys found in both prompts and tools maps: $duplicateKeys")
        }

        promptsConfig.tools.forEach { (key, toolConfig) ->
            if (toolConfig.description.isBlank()) {
                throw IllegalStateException("Tool $key has empty or blank description")
            }
            if (toolConfig.plannerDescription.isBlank()) {
                throw IllegalStateException("Tool $key has empty or blank plannerDescription")
            }
        }

        promptsConfig.pendingTaskGoals.forEach { (key, goals) ->
            if (goals.isBlank()) {
                throw IllegalStateException("Pending task type $key has empty or blank goals")
            }
        }

        // Validate all PromptTypeEnum values have corresponding prompt configuration
        val missingPrompts = PromptTypeEnum.values().filter { it !in promptsConfig.prompts.keys }
        if (missingPrompts.isNotEmpty()) {
            throw IllegalStateException(
                "Missing prompt configurations for: $missingPrompts. " +
                    "Available prompts: ${promptsConfig.prompts.keys}. " +
                    "Add missing prompts to prompts.yaml",
            )
        }

        // Validate all PendingTaskTypeEnum values have corresponding goal configuration
        val missingGoals = PendingTaskTypeEnum.values().filter { it !in promptsConfig.pendingTaskGoals.keys }
        if (missingGoals.isNotEmpty()) {
            throw IllegalStateException(
                "Missing goal configurations for pending task types: $missingGoals. " +
                    "Available goals: ${promptsConfig.pendingTaskGoals.keys}. " +
                    "Add missing goals to pending-tasks-goals.yaml",
            )
        }
    }

    /**
     * Get MCP tool planner description (short) from tools configuration.
     */
    fun getMcpToolPlannerDescription(toolType: ToolTypeEnum): String =
        promptsConfig.tools[toolType]?.plannerDescription?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("No plannerDescription found for tool type: $toolType")

    /**
     * Get MCP tool description (detailed with JSON schemas) from tools configuration.
     */
    fun getMcpToolDescription(toolType: ToolTypeEnum): String =
        promptsConfig.tools[toolType]?.description?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("No description found for tool type: $toolType")

    /**
     * Retrieves the prompt configuration for the specified prompt type.
     * Searches both tools and prompts maps for unified access.
     *
     * @param promptTypeEnum the type of prompt for which the configuration is to be retrieved
     * @return the prompt configuration base for the specified type
     * @throws IllegalArgumentException if no prompt configuration is found for the given type
     */
    fun getPrompt(promptTypeEnum: PromptTypeEnum): PromptConfig =
        promptsConfig.prompts[promptTypeEnum]
            ?: throw IllegalArgumentException(
                "No prompt configuration found for type: $promptTypeEnum. " +
                    "Available tool keys=${promptsConfig.tools.keys}. Available prompt keys=${promptsConfig.prompts.keys}",
            )

    fun getGoals(taskType: PendingTaskTypeEnum) =
        promptsConfig.pendingTaskGoals[taskType]
            ?: throw IllegalArgumentException("No goals found for pending task type: $taskType")
}
