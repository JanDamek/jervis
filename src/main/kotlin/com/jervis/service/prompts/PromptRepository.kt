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
     * Get system prompt by McpToolType - direct map lookup
     */
    fun getSystemPrompt(toolType: PromptTypeEnum): String =
        promptsConfig.prompts[toolType]?.systemPrompt
            ?: throw IllegalArgumentException("No system prompt found for tool type: $toolType")

    /**
     * Get system prompt for MCP tools with AGENT_TOOL_SUFFIX automatically appended
     */
    fun getMcpToolSystemPrompt(toolType: PromptTypeEnum): String {
        val basePrompt = getSystemPrompt(toolType)
        val agentSuffix = promptsConfig.prompts[PromptTypeEnum.AGENT_TOOL_SUFFIX]?.systemPrompt ?: ""

        return if (agentSuffix.isNotBlank()) {
            "$basePrompt\n\n$agentSuffix"
        } else {
            basePrompt
        }
    }

    /**
     * Get MCP tool description directly from the prompt configuration
     */
    fun getMcpToolDescription(toolType: PromptTypeEnum): String =
        promptsConfig.prompts[toolType]?.description
            ?: throw IllegalArgumentException("No description found for tool type: $toolType")

    /**
     * Get user prompt by McpToolType - direct map lookup
     */
    fun getMcpToolUserPrompt(toolType: PromptTypeEnum): String =
        promptsConfig.prompts[toolType]?.userPrompt
            ?: throw IllegalArgumentException("No user prompt found for tool type: $toolType")

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
