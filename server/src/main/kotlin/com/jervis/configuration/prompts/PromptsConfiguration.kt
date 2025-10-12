package com.jervis.configuration.prompts

import com.jervis.domain.model.ModelType
import org.springframework.boot.context.properties.ConfigurationProperties

// Společný základ pro všechny prompty
sealed interface PromptConfigBase {
    val systemPrompt: String
    val userPrompt: String?
    val modelParams: ModelParams
}

// Běžné prompty (agents/services) – bez description
data class PromptGenericConfig(
    override var systemPrompt: String,
    override var userPrompt: String? = null,
    override var modelParams: ModelParams,
) : PromptConfigBase

// MCP nástroje – s povinným description
data class PromptToolConfig(
    override var systemPrompt: String,
    override var userPrompt: String? = null,
    var description: String, // povinné
    override var modelParams: ModelParams,
) : PromptConfigBase

@ConfigurationProperties(prefix = "")
data class PromptsConfiguration(
    // Non-tool prompty (AGENT, SERVICE)
    var prompts: Map<PromptTypeEnum, PromptGenericConfig> = emptyMap(),
    // MCP nástroje
    var tools: Map<PromptTypeEnum, PromptToolConfig> = emptyMap(),
    var creativityLevels: Map<CreativityLevel, CreativityConfig> = emptyMap(),
)

// Legacy PromptConfig for backward compatibility during migration
data class PromptConfig(
    var systemPrompt: String,
    var userPrompt: String?,
    var description: String?,
    var modelParams: ModelParams,
)

data class ModelParams(
    var modelType: ModelType,
    var creativityLevel: CreativityLevel,
)

data class CreativityConfig(
    var temperature: Double,
    var topP: Double,
)
