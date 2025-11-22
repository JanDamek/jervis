package com.jervis.configuration.prompts

data class PromptConfig(
    var systemPrompt: String,
    var userPrompt: String? = null,
    var modelParams: ModelParams,
)
