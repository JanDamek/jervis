package com.jervis.service.prompts

import com.jervis.configuration.prompts.CreativityLevel
import com.jervis.configuration.prompts.EffectiveModelParams
import com.jervis.configuration.prompts.McpToolType
import com.jervis.configuration.prompts.ModelParams
import com.jervis.configuration.prompts.PromptType
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.configuration.prompts.UserInteractionPromptType
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class PromptRepository(
    private val promptsConfig: PromptsConfiguration,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Get system prompt by type - direct map lookup
     */
    fun getSystemPrompt(promptType: PromptType): String =
        promptsConfig.prompts[promptType]?.systemPrompt
            ?: throw IllegalArgumentException("No system prompt found for type: $promptType")

    /**
     * Get MCP tool description directly from prompt configuration
     */
    fun getMcpToolDescription(toolType: McpToolType): String {
        val promptType = mapMcpToolToPromptType(toolType)
        return promptsConfig.prompts[promptType]?.description
            ?: throw IllegalArgumentException("No description found for tool type: $toolType")
    }

    /**
     * Get model parameters directly from prompt configuration
     */
    fun getModelParams(promptType: PromptType): ModelParams =
        promptsConfig.prompts[promptType]?.modelParams
            ?: throw IllegalArgumentException("No model params found for type: $promptType")

    /**
     * Get final processing prompt
     */
    fun getFinalProcessingPrompt(promptType: PromptType): String? = promptsConfig.prompts[promptType]?.finalProcessing?.systemPrompt

    /**
     * Get user interaction prompts
     */
    fun getUserInteractionPrompt(type: UserInteractionPromptType): String {
        val userInteractionPrompts =
            promptsConfig.prompts[PromptType.USER_INTERACTION_SYSTEM]?.prompts
                ?: throw IllegalArgumentException("No user interaction prompts configured")

        return when (type) {
            UserInteractionPromptType.REFORMULATION -> userInteractionPrompts.reformulation
            UserInteractionPromptType.ANSWER_GENERATION -> userInteractionPrompts.answerGeneration
            UserInteractionPromptType.TRANSLATION -> userInteractionPrompts.translation
        }
    }

    /**
     * Get effective model parameters with resolved creativity level
     */
    fun getEffectiveModelParams(promptType: PromptType): EffectiveModelParams {
        val baseParams = getModelParams(promptType)
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
     * Map MCP tool type to PromptType
     */
    private fun mapMcpToolToPromptType(toolType: McpToolType): PromptType =
        when (toolType) {
            McpToolType.RAG_QUERY -> PromptType.RAG_QUERY_SYSTEM
            McpToolType.JOERN -> PromptType.JOERN_SYSTEM
            McpToolType.TERMINAL -> PromptType.TERMINAL_SYSTEM
            McpToolType.LLM -> PromptType.LLM_SYSTEM
            McpToolType.USER_INTERACTION -> PromptType.USER_INTERACTION_SYSTEM
        }

    /**
     * Validate configuration on startup
     */
    fun validateConfiguration() {
        try {
            logger.info { "Validating prompt configuration..." }

            // Check all required prompt types are present
            PromptType.values().forEach { promptType ->
                val config =
                    promptsConfig.prompts[promptType]
                        ?: throw IllegalStateException("Missing configuration for prompt type: $promptType")

                // Check system prompt is present for non-user-interaction prompts
                if (promptType != PromptType.USER_INTERACTION_SYSTEM && config.systemPrompt.isNullOrBlank()) {
                    throw IllegalStateException("Missing system prompt for type: $promptType")
                }

                // Check model params are present
                if (config.modelParams == null) {
                    throw IllegalStateException("Missing model params for type: $promptType")
                }
            }

            // Check all creativity levels are configured
            CreativityLevel.values().forEach { level ->
                promptsConfig.creativityLevels[level]
                    ?: throw IllegalStateException("Missing creativity level configuration for: $level")
            }

            logger.info { "Prompt configuration validation completed successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Prompt configuration validation failed" }
            throw e
        }
    }
}
