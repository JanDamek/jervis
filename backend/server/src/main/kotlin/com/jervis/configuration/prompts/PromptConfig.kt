package com.jervis.configuration.prompts

// Legacy PromptConfig for backward compatibility during migration
data class PromptConfig(
    var systemPrompt: String,
    var userPrompt: String?,
    var description: String?,
    var modelParams: ModelParams,
)
