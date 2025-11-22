package com.jervis.configuration.prompts

import com.jervis.dto.PendingTaskTypeEnum
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "")
data class PromptsConfiguration(
    var prompts: Map<PromptTypeEnum, PromptConfig> = emptyMap(),
    var tools: Map<ToolTypeEnum, ToolConfig> = emptyMap(),
    var creativityLevels: Map<CreativityLevel, CreativityConfig> = emptyMap(),
    var pendingTaskGoals: Map<PendingTaskTypeEnum, String> = emptyMap(),
)
