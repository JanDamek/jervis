package com.jervis.service.background

import com.jervis.configuration.BackgroundEngineProperties
import com.jervis.entity.mongo.BackgroundSettingsDocument
import com.jervis.repository.mongo.BackgroundSettingsMongoRepository
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

/**
 * Manages background engine settings, synchronizing from YAML config to DocumentDB.
 */
@Service
class BackgroundSettingsService(
    private val properties: BackgroundEngineProperties,
    private val settingsRepository: BackgroundSettingsMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    suspend fun initializeSettings() {
        val existing = settingsRepository.findById("background_engine")

        if (existing == null) {
            logger.info { "Initializing background engine settings from YAML config" }
            val settings =
                BackgroundSettingsDocument(
                    id = "background_engine",
                    idleThresholdSeconds = properties.idleThresholdSeconds,
                    chunkTokenLimit = properties.chunkTokenLimit,
                    chunkTimeoutSeconds = properties.chunkTimeoutSeconds,
                    maxCpuBgTasks = properties.maxCpuBgTasks,
                    coverageWeights =
                        mapOf(
                            "docs" to properties.coverageWeights.docs,
                            "tasks" to properties.coverageWeights.tasks,
                            "code" to properties.coverageWeights.code,
                            "meetings" to properties.coverageWeights.meetings,
                        ),
                )
            settingsRepository.save(settings)
            logger.info { "Background engine settings initialized: $settings" }
        } else {
            logger.info { "Background engine settings already exist: $existing" }
        }
    }

    suspend fun getSettings(): BackgroundSettingsDocument =
        settingsRepository.findById("background_engine")
            ?: BackgroundSettingsDocument()

    suspend fun updateSettings(update: (BackgroundSettingsDocument) -> BackgroundSettingsDocument) {
        val current = getSettings()
        val updated = update(current)
        settingsRepository.save(updated)
        logger.info { "Updated background engine settings" }
    }
}
