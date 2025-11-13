package com.jervis.configuration.prompts

// MCP tools – description is mandatory
data class PromptToolConfig(
    override var systemPrompt: String,
    override var userPrompt: String? = null,
    var description: String,
    override var modelParams: ModelParams,
) : PromptConfigBase
