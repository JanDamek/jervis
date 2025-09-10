package com.jervis.repository.mongo

import com.jervis.configuration.prompts.McpToolType
import com.jervis.domain.model.ModelType
import com.jervis.entity.mongo.PromptDocument
import com.jervis.entity.mongo.PromptStatus
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface PromptMongoRepository : ReactiveMongoRepository<PromptDocument, ObjectId> {
    /**
     * Find prompt by tool type, model type, and status
     */
    fun findByToolTypeAndModelTypeAndStatus(
        toolType: McpToolType,
        modelType: ModelType?,
        status: PromptStatus,
    ): Mono<PromptDocument>

    /**
     * Find all prompts by tool type and status
     */
    fun findByToolTypeAndStatus(
        toolType: McpToolType,
        status: PromptStatus,
    ): Flux<PromptDocument>

    /**
     * Find prompts by tool type, status, ordered by priority (highest first)
     */
    fun findByToolTypeAndStatusOrderByPriorityDesc(
        toolType: McpToolType,
        status: PromptStatus,
    ): Flux<PromptDocument>

    /**
     * Find best match for tool and model with fallback logic:
     * 1. Exact match (toolType + modelType)
     * 2. Generic match (toolType + null modelType)
     * Ordered by priority descending
     */
    @Query("{ 'toolType': ?0, 'status': 'ACTIVE', '\$or': [{'modelType': ?1}, {'modelType': null}] }")
    fun findBestMatchForToolAndModel(
        toolType: McpToolType,
        modelType: ModelType,
    ): Flux<PromptDocument>

    /**
     * Find generic prompt (modelType is null) for tool type
     */
    fun findByToolTypeAndModelTypeIsNullAndStatus(
        toolType: McpToolType,
        status: PromptStatus,
    ): Mono<PromptDocument>

    /**
     * Find all prompts by status
     */
    fun findByStatus(status: PromptStatus): Flux<PromptDocument>

    /**
     * Find all prompts by tool type (regardless of status)
     */
    fun findByToolType(toolType: McpToolType): Flux<PromptDocument>

    /**
     * Find all active prompts
     */
    @Query("{ 'status': 'ACTIVE' }")
    fun findAllActive(): Flux<PromptDocument>

    /**
     * Check if prompt exists for tool type and model type
     */
    fun existsByToolTypeAndModelTypeAndStatus(
        toolType: McpToolType,
        modelType: ModelType?,
        status: PromptStatus,
    ): Mono<Boolean>

    /**
     * Count prompts by status
     */
    fun countByStatus(status: PromptStatus): Mono<Long>

    /**
     * Find prompts by metadata tags
     */
    @Query("{ 'metadata.tags': { '\$in': ?0 }, 'status': ?1 }")
    fun findByMetadataTagsInAndStatus(
        tags: List<String>,
        status: PromptStatus,
    ): Flux<PromptDocument>

    /**
     * Find prompts by creator
     */
    fun findByCreatedByAndStatus(
        createdBy: String,
        status: PromptStatus,
    ): Flux<PromptDocument>

    /**
     * Delete all prompts by tool type (for cleanup/migration)
     */
    fun deleteByToolType(toolType: McpToolType): Mono<Void>
}
