package com.jervis.service.prompts

import com.jervis.configuration.prompts.CreativityLevel
import com.jervis.configuration.prompts.EffectiveModelParams
import com.jervis.configuration.prompts.McpToolType
import com.jervis.configuration.prompts.ModelParams
import com.jervis.configuration.prompts.PromptsConfiguration
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class PromptRepository(
    private val promptsConfig: PromptsConfiguration,
) {
    private val logger = KotlinLogging.logger {}

    init {
        logger.info { "PromptRepository initialized with configuration:" }
        logger.info { "  Prompts count: ${promptsConfig.prompts.size}" }
        logger.info { "  Creativity levels count: ${promptsConfig.creativityLevels.size}" }
        logger.info { "  Available prompt types: ${promptsConfig.prompts.keys}" }

        if (promptsConfig.prompts.isEmpty()) {
            logger.error { "WARNING: No prompts loaded from configuration! Check YAML binding." }
        } else {
            promptsConfig.prompts.forEach { (type, config) ->
                logger.debug {
                    "  $type: hasSystemPrompt=${!config.systemPrompt.isNullOrBlank()}, hasDescription=${
                        !config.description
                            .isNullOrBlank()
                    }"
                }
            }
        }
    }

    /**
     * Get system prompt by McpToolType - direct map lookup
     */
    fun getSystemPrompt(toolType: McpToolType): String =
        promptsConfig.prompts[toolType]?.systemPrompt
            ?: throw IllegalArgumentException("No system prompt found for tool type: $toolType")

    /**
     * Get system prompt for MCP tools with AGENT_TOOL_SUFFIX automatically appended
     */
    fun getMcpToolSystemPrompt(toolType: McpToolType): String {
        val basePrompt = getSystemPrompt(toolType)
        val agentSuffix = promptsConfig.prompts[McpToolType.AGENT_TOOL_SUFFIX]?.systemPrompt ?: ""

        return if (agentSuffix.isNotBlank()) {
            "$basePrompt\n\n$agentSuffix"
        } else {
            basePrompt
        }
    }

    /**
     * Get MCP tool description directly from the prompt configuration
     */
    fun getMcpToolDescription(toolType: McpToolType): String =
        promptsConfig.prompts[toolType]?.description
            ?: throw IllegalArgumentException("No description found for tool type: $toolType")

    /**
     * Get model parameters by McpToolType
     */
    fun getModelParams(toolType: McpToolType): ModelParams =
        promptsConfig.prompts[toolType]?.modelParams
            ?: throw IllegalArgumentException("No model params found for tool type: $toolType")

    /**
     * Get effective model parameters with resolved creativity level by string key
     */
    fun getEffectiveModelParams(promptKey: McpToolType): EffectiveModelParams {
        val baseParams = getModelParams(promptKey)
        val creativityConfig =
            promptsConfig.creativityLevels[baseParams.creativityLevel]
                ?: promptsConfig.creativityLevels[CreativityLevel.MEDIUM]
                ?: throw IllegalStateException("No creativity level configuration found for MEDIUM")

        return EffectiveModelParams(
            // From creativity level - SINGLE SOURCE of temperature/topP
            temperature = creativityConfig.temperature,
            topP = creativityConfig.topP,
            creativityLevel = baseParams.creativityLevel,
            // From model params - specific parameters
            presencePenalty = baseParams.presencePenalty,
            frequencyPenalty = baseParams.frequencyPenalty,
            repeatPenalty = baseParams.repeatPenalty,
            systemPromptWeight = baseParams.systemPromptWeight,
            stopSequences = baseParams.stopSequences,
            logitBias = baseParams.logitBias,
            seed = baseParams.seed,
            jsonMode = baseParams.jsonMode,
            // From timeout config
            timeoutMs =
                when (baseParams.creativityLevel) {
                    CreativityLevel.LOW -> promptsConfig.timeouts.quick
                    CreativityLevel.MEDIUM -> promptsConfig.timeouts.standard
                    CreativityLevel.HIGH -> promptsConfig.timeouts.extended
                },
        )
    }

    /**
     * Get the final processing system prompt for MCP tools
     */
    fun getMcpToolFinalProcessingSystemPrompt(toolType: McpToolType): String? {
        val promptConfig = promptsConfig.prompts[toolType]
        return promptConfig?.finalProcessing?.systemPrompt
    }
}
