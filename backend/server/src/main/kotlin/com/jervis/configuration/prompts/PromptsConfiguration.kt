package com.jervis.configuration.prompts

import com.jervis.dto.PendingTaskTypeEnum
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "")
data class PromptsConfiguration(
    var pendingTaskGoals: Map<PendingTaskTypeEnum, ExtractionGoal> = emptyMap(),
)
