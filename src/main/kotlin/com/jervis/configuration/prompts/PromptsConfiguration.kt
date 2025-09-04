package com.jervis.configuration.prompts

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "")
data class PromptsConfiguration(
    var prompts: Map<PromptType, PromptConfig> = emptyMap(),
    var creativityLevels: Map<CreativityLevel, CreativityConfig> = emptyMap(),
    var timeouts: TimeoutsConfig = TimeoutsConfig(30000, 60000, 120000),
)

data class PromptConfig(
    val systemPrompt: String? = null,
    val description: String? = null,
    val finalProcessing: FinalProcessingConfig? = null,
    val prompts: UserInteractionPrompts? = null,
    val modelParams: ModelParams,
)

data class FinalProcessingConfig(
    val systemPrompt: String,
)

data class UserInteractionPrompts(
    val reformulation: String,
    val answerGeneration: String,
    val translation: String,
)

data class ModelParams(
    val creativityLevel: CreativityLevel = CreativityLevel.MEDIUM,
    val presencePenalty: Double = 0.0,
    val frequencyPenalty: Double = 0.0,
    val repeatPenalty: Double = 1.0,
    val systemPromptWeight: Double = 1.0,
    val stopSequences: List<String> = emptyList(),
    val logitBias: Map<String, Double> = emptyMap(),
    val seed: Long? = null,
    val jsonMode: Boolean = false,
)

data class CreativityConfig(
    val temperature: Double,
    val topP: Double,
    val description: String,
)

data class TimeoutsConfig(
    val quick: Long,
    val standard: Long,
    val extended: Long,
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
