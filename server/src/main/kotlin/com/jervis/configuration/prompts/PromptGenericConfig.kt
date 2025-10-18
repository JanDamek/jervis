package com.jervis.configuration.prompts

// Běžné prompty (agents/services) – bez description
data class PromptGenericConfig(
    override var systemPrompt: String,
    override var userPrompt: String? = null,
    override var modelParams: ModelParams,
) : PromptConfigBase
