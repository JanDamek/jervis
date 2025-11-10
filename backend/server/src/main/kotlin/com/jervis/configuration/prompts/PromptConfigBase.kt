package com.jervis.configuration.prompts

// Společný základ pro všechny prompty
sealed interface PromptConfigBase {
    val systemPrompt: String
    val userPrompt: String?
    val modelParams: ModelParams
}
