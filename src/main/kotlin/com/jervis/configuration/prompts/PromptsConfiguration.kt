package com.jervis.configuration.prompts

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "")
data class PromptsConfiguration(
    var prompts: Map<PromptType, PromptConfig> = emptyMap(),
    var creativityLevels: Map<CreativityLevel, CreativityConfig> = emptyMap(),
    var timeouts: TimeoutsConfig = TimeoutsConfig(30000, 60000, 120000),
)

data class PromptConfig(
    var systemPrompt: String? = null,
    var description: String? = null,
    var finalProcessing: FinalProcessingConfig? = null,
    var prompts: UserInteractionPrompts? = null,
    var modelParams: ModelParams = ModelParams(),
)

data class FinalProcessingConfig(
    var systemPrompt: String,
)

data class UserInteractionPrompts(
    var reformulation: String,
    var answerGeneration: String,
    var translation: String,
)

data class ModelParams(
    var creativityLevel: CreativityLevel = CreativityLevel.MEDIUM,
    var presencePenalty: Double = 0.0,
    var frequencyPenalty: Double = 0.0,
    var repeatPenalty: Double = 1.0,
    var systemPromptWeight: Double = 1.0,
    var stopSequences: List<String> = emptyList(),
    var logitBias: Map<String, Double> = emptyMap(),
    var seed: Long? = null,
    var jsonMode: Boolean = false,
)

data class CreativityConfig(
    var temperature: Double,
    var topP: Double,
    var description: String,
)

data class TimeoutsConfig(
    var quick: Long,
    var standard: Long,
    var extended: Long,
)

data class EffectiveModelParams(
    // From CreativityLevel
    val temperature: Double,
    val topP: Double,
    val creativityLevel: CreativityLevel,
    // From ModelParams
    val presencePenalty: Double,
    val frequencyPenalty: Double,
    val repeatPenalty: Double,
    val systemPromptWeight: Double,
    val stopSequences: List<String>,
    val logitBias: Map<String, Double>,
    val seed: Long?,
    val jsonMode: Boolean,
    // From timeout configuration
    val timeoutMs: Long,
)
