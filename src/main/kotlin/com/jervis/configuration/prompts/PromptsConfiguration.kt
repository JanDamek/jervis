package com.jervis.configuration.prompts

import com.jervis.domain.model.ModelType
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "")
data class PromptsConfiguration(
    var prompts: Map<PromptTypeEnum, PromptConfig> = emptyMap(),
    var creativityLevels: Map<CreativityLevel, CreativityConfig> = emptyMap(),
)

data class PromptConfig(
    var systemPrompt: String,
    var userPrompt: String?,
    var description: String,
    var modelParams: ModelParams = ModelParams(),
)

data class ModelParams(
    var modelType: ModelType = ModelType.INTERNAL,
    var creativityLevel: CreativityLevel = CreativityLevel.MEDIUM,
)

data class CreativityConfig(
    var temperature: Double,
    var topP: Double,
)
