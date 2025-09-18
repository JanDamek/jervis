package com.jervis.service.prompts

import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.configuration.prompts.PromptsConfiguration
import org.springframework.stereotype.Service

@Service
class PromptRepository(
    private val promptsConfig: PromptsConfiguration,
) {
    /**
     * Get MCP tool description directly from the prompt configuration
     */
    fun getMcpToolDescription(toolType: PromptTypeEnum): String =
        promptsConfig.prompts[toolType]?.description
            ?: throw IllegalArgumentException("No description found for tool type: $toolType")

    /**
     * Retrieves the prompt configuration for the specified prompt type.
     *
     * @param promptTypeEnum the type of prompt for which the configuration is to be retrieved
     * @return the prompt configuration for the specified type
     * @throws IllegalArgumentException if no prompt configuration is found for the given type
     */
    fun getPrompt(promptTypeEnum: PromptTypeEnum): PromptConfig =
        promptsConfig.prompts[promptTypeEnum]
            ?: throw IllegalArgumentException("No model params found for tool type: $promptTypeEnum")
}
