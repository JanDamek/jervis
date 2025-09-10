package com.jervis.migration

import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.entity.mongo.PromptDocument
import com.jervis.entity.mongo.PromptMetadata
import com.jervis.entity.mongo.PromptStatus
import com.jervis.repository.mongo.PromptMongoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class YamlToMongoPromptMigration(
    private val promptRepository: PromptMongoRepository,
    private val yamlPromptsConfig: PromptsConfiguration,
) {
    private val logger = KotlinLogging.logger {}
    private val migrationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @EventListener(ApplicationReadyEvent::class)
    fun migrateYamlToMongo() {
        migrationScope.launch {
            logger.info { "Starting YAML to MongoDB prompt migration..." }

            var migratedCount = 0
            var skippedCount = 0

            yamlPromptsConfig.prompts.forEach { (toolType, promptConfig) ->
                try {
                    // Check if prompt already exists
                    val existing =
                        promptRepository
                            .findByToolTypeAndModelTypeIsNullAndStatus(
                                toolType,
                                PromptStatus.ACTIVE,
                            ).awaitSingleOrNull()

                    if (existing != null) {
                        logger.debug { "Prompt for $toolType already exists, skipping migration" }
                        skippedCount++
                    } else {
                        migratePromptConfig(toolType, promptConfig)
                        migratedCount++
                        logger.debug { "Migrated prompt for $toolType" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to migrate prompt for $toolType" }
                }
            }

            logger.info {
                "Migration completed: $migratedCount prompts migrated, $skippedCount skipped"
            }
        }
    }

    private suspend fun migratePromptConfig(
        toolType: com.jervis.configuration.prompts.McpToolType,
        config: com.jervis.configuration.prompts.PromptConfig,
    ) {
        val promptDocument =
            PromptDocument(
                toolType = toolType,
                modelType = null, // Generic for all models initially
                version = "1.0.0",
                systemPrompt = config.systemPrompt,
                userPrompt = config.userPrompt,
                description = config.description,
                finalProcessing = config.finalProcessing,
                userInteractionPrompts = config.prompts,
                modelParams = config.modelParams,
                metadata =
                    PromptMetadata(
                        tags = listOf("migrated-from-yaml", "initial-version"),
                        author = "system-migration",
                        source = "yaml-configuration",
                        notes = "Automatically migrated from YAML configuration during startup",
                        category = "system",
                    ),
                status = PromptStatus.ACTIVE,
                priority = 0,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                createdBy = "yaml-migration-service",
                updatedBy = "yaml-migration-service",
            )

        promptRepository.save(promptDocument).awaitSingle()
    }
}
