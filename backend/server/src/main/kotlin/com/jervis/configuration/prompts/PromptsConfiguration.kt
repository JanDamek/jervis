package com.jervis.configuration.prompts

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "")
data class PromptsConfiguration(
    var prompts: Map<PromptTypeEnum, PromptGenericConfig> = emptyMap(),
    var tools: Map<PromptTypeEnum, PromptToolConfig> = emptyMap(),
    var creativityLevels: Map<CreativityLevel, CreativityConfig> = emptyMap(),
)
