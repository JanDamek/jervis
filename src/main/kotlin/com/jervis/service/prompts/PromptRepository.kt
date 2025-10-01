package com.jervis.service.prompts

import com.jervis.configuration.prompts.PromptConfigBase
import com.jervis.configuration.prompts.PromptToolConfig
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.configuration.prompts.PromptsConfiguration
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service

@Service
class PromptRepository(
    private val promptsConfig: PromptsConfiguration,
) {
    @PostConstruct
    fun validateConfiguration() {
        // Validate no duplicate keys between prompts and tools
        val promptKeys = promptsConfig.prompts.keys
        val toolKeys = promptsConfig.tools.keys
        val duplicateKeys = promptKeys.intersect(toolKeys)

        if (duplicateKeys.isNotEmpty()) {
            throw IllegalStateException("Duplicate keys found in both prompts and tools maps: $duplicateKeys")
        }

        // Validate all tools have non-empty descriptions
        promptsConfig.tools.forEach { (key, toolConfig) ->
            if (toolConfig.description.isBlank()) {
                throw IllegalStateException("Tool $key has empty or blank description")
            }
        }

        // Log validation success
        println("[DEBUG] Prompt configuration validation successful:")
        println("[DEBUG] - Tools configured: ${toolKeys.size}")
        println("[DEBUG] - Generic prompts configured: ${promptKeys.size}")
        println("[DEBUG] - No duplicate keys found")
        println("[DEBUG] - All tools have valid descriptions")
    }

    /**
     * Get MCP tool description directly from the tools configuration
     */
    fun getMcpToolDescription(toolType: PromptTypeEnum): String =
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
    fun getPrompt(promptTypeEnum: PromptTypeEnum): PromptConfigBase =
        promptsConfig.tools[promptTypeEnum] as? PromptConfigBase
            ?: promptsConfig.prompts[promptTypeEnum] as? PromptConfigBase
            ?: throw IllegalArgumentException("No prompt configuration found for type: $promptTypeEnum")

    /**
     * Retrieves the tool configuration for MCP tools specifically.
     *
     * @param promptTypeEnum the type of tool for which the configuration is to be retrieved
     * @return the tool configuration for the specified type
     * @throws IllegalArgumentException if no tool configuration is found for the given type
     */
    fun getToolConfig(promptTypeEnum: PromptTypeEnum): PromptToolConfig =
        promptsConfig.tools[promptTypeEnum]
            ?: throw IllegalArgumentException("No tool configuration found for type: $promptTypeEnum")
}
