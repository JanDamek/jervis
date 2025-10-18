package com.jervis.configuration.prompts

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "")
data class PromptsConfiguration(
    // Non-tool prompty (AGENT, SERVICE)
    var prompts: Map<PromptTypeEnum, PromptGenericConfig> = emptyMap(),
    // MCP nástroje
    var tools: Map<PromptTypeEnum, PromptToolConfig> = emptyMap(),
    var creativityLevels: Map<CreativityLevel, CreativityConfig> = emptyMap(),
)
