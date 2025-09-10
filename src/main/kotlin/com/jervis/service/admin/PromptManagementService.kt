package com.jervis.service.admin

import com.jervis.configuration.prompts.McpToolType
import com.jervis.domain.model.ModelType
import com.jervis.entity.mongo.CreatePromptRequest
import com.jervis.entity.mongo.PromptDocument
import com.jervis.entity.mongo.PromptStatus
import com.jervis.entity.mongo.UpdatePromptRequest
import com.jervis.repository.mongo.PromptMongoRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class PromptManagementService(
    private val promptRepository: PromptMongoRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun getAllPrompts(): List<PromptDocument> = promptRepository.findAll().collectList().awaitSingle()

    suspend fun getPromptsByTool(toolType: McpToolType): List<PromptDocument> =
        promptRepository
            .findByToolTypeAndStatus(toolType, PromptStatus.ACTIVE)
            .collectList()
            .awaitSingle()

    suspend fun getPromptsByStatus(status: PromptStatus): List<PromptDocument> =
        promptRepository.findByStatus(status).collectList().awaitSingle()

    suspend fun getPromptById(id: String): PromptDocument? =
        try {
            promptRepository.findById(ObjectId(id)).awaitSingleOrNull()
        } catch (e: IllegalArgumentException) {
            logger.warn { "Invalid ObjectId format: $id" }
            null
        }

    suspend fun createPrompt(
        request: CreatePromptRequest,
        userId: String,
    ): PromptDocument {
        // Check if prompt already exists for this tool type and model type
        val existing =
            promptRepository
                .findByToolTypeAndModelTypeAndStatus(
                    request.toolType,
                    request.modelType,
                    PromptStatus.ACTIVE,
                ).awaitSingleOrNull()

        if (existing != null) {
            throw IllegalArgumentException(
                "Active prompt already exists for tool type ${request.toolType}" +
                    if (request.modelType != null) " and model type ${request.modelType}" else "",
            )
        }

        val prompt =
            PromptDocument(
                toolType = request.toolType,
                modelType = request.modelType,
                systemPrompt = request.systemPrompt,
                userPrompt = request.userPrompt,
                description = request.description,
                finalProcessing = request.finalProcessing,
                userInteractionPrompts = request.userInteractionPrompts,
                modelParams = request.modelParams,
                metadata = request.metadata,
                priority = request.priority,
                createdBy = userId,
                updatedBy = userId,
            )

        val saved = promptRepository.save(prompt).awaitSingle()
        eventPublisher.publishEvent(PromptChangedEvent(saved.toolType, saved.modelType))

        logger.info { "Created new prompt: ${saved.toolType}${saved.modelType?.let { " ($it)" } ?: ""} by $userId" }

        return saved
    }

    suspend fun updatePrompt(
        id: String,
        request: UpdatePromptRequest,
        userId: String,
    ): PromptDocument? {
        val existing = getPromptById(id) ?: return null

        val updated =
            existing.copy(
                systemPrompt = request.systemPrompt ?: existing.systemPrompt,
                userPrompt = request.userPrompt ?: existing.userPrompt,
                description = request.description ?: existing.description,
                finalProcessing = request.finalProcessing ?: existing.finalProcessing,
                userInteractionPrompts = request.userInteractionPrompts ?: existing.userInteractionPrompts,
                modelParams = request.modelParams,
                metadata = request.metadata,
                status = request.status,
                priority = request.priority,
                version = incrementVersion(existing.version),
                updatedAt = Instant.now(),
                updatedBy = userId,
            )

        val saved = promptRepository.save(updated).awaitSingle()
        eventPublisher.publishEvent(PromptChangedEvent(saved.toolType, saved.modelType))

        logger.info { "Updated prompt: ${saved.toolType}${saved.modelType?.let { " ($it)" } ?: ""} to version ${saved.version} by $userId" }

        return saved
    }

    suspend fun deletePrompt(
        id: String,
        userId: String,
    ): Boolean {
        val existing = getPromptById(id) ?: return false

        return try {
            promptRepository.deleteById(ObjectId(id)).awaitSingleOrNull()
            eventPublisher.publishEvent(PromptChangedEvent(existing.toolType, existing.modelType))

            logger.info { "Deleted prompt: ${existing.toolType}${existing.modelType?.let { " ($it)" } ?: ""} by $userId" }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete prompt $id" }
            false
        }
    }

    suspend fun clonePromptForModel(
        id: String,
        targetModelType: ModelType,
        userId: String,
    ): PromptDocument? {
        val source = getPromptById(id) ?: return null

        // Check if target already exists
        val existing =
            promptRepository
                .findByToolTypeAndModelTypeAndStatus(
                    source.toolType,
                    targetModelType,
                    PromptStatus.ACTIVE,
                ).awaitSingleOrNull()

        if (existing != null) {
            throw IllegalArgumentException(
                "Active prompt already exists for tool type ${source.toolType} and model type $targetModelType",
            )
        }

        val cloned =
            source.copy(
                id = ObjectId.get(),
                modelType = targetModelType,
                version = "1.0.0",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                createdBy = userId,
                updatedBy = userId,
                metadata =
                    source.metadata.copy(
                        tags = source.metadata.tags + "cloned-from-${source.modelType ?: "generic"}",
                        notes = "Cloned from ${source.toolType}${source.modelType?.let { " ($it)" } ?: ""}",
                    ),
            )

        val saved = promptRepository.save(cloned).awaitSingle()
        eventPublisher.publishEvent(PromptChangedEvent(saved.toolType, saved.modelType))

        logger.info { "Cloned prompt ${source.toolType} to model type $targetModelType by $userId" }

        return saved
    }

    suspend fun changePromptStatus(
        id: String,
        newStatus: PromptStatus,
        userId: String,
    ): PromptDocument? {
        val existing = getPromptById(id) ?: return null

        val updated =
            existing.copy(
                status = newStatus,
                version = if (newStatus == PromptStatus.ACTIVE) incrementVersion(existing.version) else existing.version,
                updatedAt = Instant.now(),
                updatedBy = userId,
            )

        val saved = promptRepository.save(updated).awaitSingle()
        eventPublisher.publishEvent(PromptChangedEvent(saved.toolType, saved.modelType))

        logger.info { "Changed prompt status: ${saved.toolType}${saved.modelType?.let { " ($it)" } ?: ""} to $newStatus by $userId" }

        return saved
    }

    suspend fun searchPromptsByTags(
        tags: List<String>,
        status: PromptStatus = PromptStatus.ACTIVE,
    ): List<PromptDocument> = promptRepository.findByMetadataTagsInAndStatus(tags, status).collectList().awaitSingle()

    suspend fun getPromptsByCreator(
        createdBy: String,
        status: PromptStatus = PromptStatus.ACTIVE,
    ): List<PromptDocument> = promptRepository.findByCreatedByAndStatus(createdBy, status).collectList().awaitSingle()

    suspend fun getPromptStatistics(): PromptStatistics {
        val total = promptRepository.count().awaitSingle()
        val active = promptRepository.countByStatus(PromptStatus.ACTIVE).awaitSingle()
        val draft = promptRepository.countByStatus(PromptStatus.DRAFT).awaitSingle()
        val deprecated = promptRepository.countByStatus(PromptStatus.DEPRECATED).awaitSingle()
        val archived = promptRepository.countByStatus(PromptStatus.ARCHIVED).awaitSingle()

        return PromptStatistics(
            total = total,
            active = active,
            draft = draft,
            deprecated = deprecated,
            archived = archived,
        )
    }

    private fun incrementVersion(currentVersion: String): String {
        val parts = currentVersion.split(".")
        return if (parts.size >= 3) {
            val patch = (parts[2].toIntOrNull() ?: 0) + 1
            "${parts[0]}.${parts[1]}.$patch"
        } else {
            "1.0.1"
        }
    }
}

data class PromptChangedEvent(
    val toolType: McpToolType,
    val modelType: ModelType?,
)

data class PromptStatistics(
    val total: Long,
    val active: Long,
    val draft: Long,
    val deprecated: Long,
    val archived: Long,
)
