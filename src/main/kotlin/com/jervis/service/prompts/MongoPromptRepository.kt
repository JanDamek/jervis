package com.jervis.service.prompts

import com.jervis.configuration.prompts.CreativityLevel
import com.jervis.configuration.prompts.EffectiveModelParams
import com.jervis.configuration.prompts.McpToolType
import com.jervis.configuration.prompts.ModelParams
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.domain.model.ModelType
import com.jervis.entity.mongo.PromptStatus
import com.jervis.repository.mongo.PromptMongoRepository
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.cache.annotation.Cacheable
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary // This will override the existing YAML-based PromptRepository
class MongoPromptRepository(
    private val promptRepository: PromptMongoRepository,
    private val promptsConfig: PromptsConfiguration,
) {
    private val logger = KotlinLogging.logger {}

    init {
        logger.info { "MongoPromptRepository initialized - MongoDB-based prompt management active" }
    }

    /**
     * Get system prompt by McpToolType - direct MongoDB lookup with caching
     */
    @Cacheable("prompts", key = "#toolType.name + '_system'")
    suspend fun getSystemPrompt(toolType: McpToolType): String {
        val prompt = findOptimalPrompt(toolType, ModelType.INTERNAL)
        return prompt?.systemPrompt
            ?: throw IllegalArgumentException("No system prompt found for tool type: $toolType")
    }

    /**
     * Get system prompt for MCP tools with AGENT_TOOL_SUFFIX automatically appended
     */
    suspend fun getMcpToolSystemPrompt(toolType: McpToolType): String {
        val basePrompt = getSystemPrompt(toolType)
        val agentSuffix =
            try {
                getSystemPrompt(McpToolType.AGENT_TOOL_SUFFIX)
            } catch (e: IllegalArgumentException) {
                logger.debug { "No AGENT_TOOL_SUFFIX found, using base prompt only" }
                ""
            }

        return if (agentSuffix.isNotBlank()) {
            "$basePrompt\n\n$agentSuffix"
        } else {
            basePrompt
        }
    }

    /**
     * Get MCP tool description directly from the MongoDB prompt
     */
    @Cacheable("prompts", key = "#toolType.name + '_description'")
    suspend fun getMcpToolDescription(toolType: McpToolType): String {
        val prompt = findOptimalPrompt(toolType, ModelType.INTERNAL)
        return prompt?.description
            ?: throw IllegalArgumentException("No description found for tool type: $toolType")
    }

    /**
     * Get user prompt by McpToolType - direct MongoDB lookup
     */
    @Cacheable("prompts", key = "#toolType.name + '_user'")
    suspend fun getMcpToolUserPrompt(toolType: McpToolType): String {
        val prompt = findOptimalPrompt(toolType, ModelType.INTERNAL)
        return prompt?.userPrompt
            ?: throw IllegalArgumentException("No user prompt found for tool type: $toolType")
    }

    /**
     * Get model parameters by McpToolType
     */
    @Cacheable("prompts", key = "#toolType.name + '_params'")
    suspend fun getModelParams(toolType: McpToolType): ModelParams {
        val prompt = findOptimalPrompt(toolType, ModelType.INTERNAL)
        return prompt?.modelParams
            ?: throw IllegalArgumentException("No model params found for tool type: $toolType")
    }

    /**
     * Get effective model parameters with resolved creativity level by string key
     */
    suspend fun getEffectiveModelParams(promptKey: McpToolType): EffectiveModelParams {
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
    @Cacheable("prompts", key = "#toolType.name + '_final'")
    suspend fun getMcpToolFinalProcessingSystemPrompt(toolType: McpToolType): String? {
        val prompt = findOptimalPrompt(toolType, ModelType.INTERNAL)
        return prompt?.finalProcessing?.systemPrompt
    }

    /**
     * Enhanced method for model-specific prompt retrieval
     */
    suspend fun getSystemPromptWithModel(
        toolType: McpToolType,
        modelType: ModelType,
    ): String {
        val prompt = findOptimalPrompt(toolType, modelType)
        return prompt?.systemPrompt
            ?: throw IllegalArgumentException("No system prompt found for tool type: $toolType and model type: $modelType")
    }

    /**
     * Find prompt by exact match only - no fallback logic
     */
    private suspend fun findOptimalPrompt(
        toolType: McpToolType,
        modelType: ModelType?,
    ) = promptRepository
        .findByToolTypeAndModelTypeAndStatus(toolType, modelType, PromptStatus.ACTIVE)
        .awaitSingleOrNull()
}
