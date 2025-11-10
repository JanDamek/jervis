package com.jervis.configuration.prompts

// MCP nástroje – s povinným description
data class PromptToolConfig(
    override var systemPrompt: String,
    override var userPrompt: String? = null,
    var description: String, // povinné
    override var modelParams: ModelParams,
) : PromptConfigBase
