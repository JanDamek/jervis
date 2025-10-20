package com.jervis.entity.mongo

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "background_settings")
data class BackgroundSettingsDocument(
    @Id
    val id: String = "background_engine",
    val idleThresholdSeconds: Long = 120,
    val chunkTokenLimit: Int = 1200,
    val chunkTimeoutSeconds: Long = 45,
    val maxCpuBgTasks: Int = 2,
    val coverageWeights: Map<String, Double> =
        mapOf(
            "docs" to 0.3,
            "tasks" to 0.2,
            "code" to 0.4,
            "meetings" to 0.1,
        ),
)
