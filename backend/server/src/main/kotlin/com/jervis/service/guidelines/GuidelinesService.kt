package com.jervis.service.guidelines

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.guidelines.GuidelinesDocumentDto
import com.jervis.dto.guidelines.GuidelinesScope
import com.jervis.dto.guidelines.GuidelinesUpdateRequest
import com.jervis.dto.guidelines.MergedGuidelinesDto
import com.jervis.entity.GuidelinesDocument
import com.jervis.entity.mergeGuidelines
import com.jervis.repository.GuidelinesRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for managing hierarchical guidelines (GLOBAL → CLIENT → PROJECT).
 *
 * Provides:
 * - CRUD for guidelines at each scope level
 * - Merged resolution with deep merge semantics
 * - In-memory cache with 5-minute TTL
 */
@Service
class GuidelinesService(
    private val repository: GuidelinesRepository,
) {
    private val logger = KotlinLogging.logger {}

    private data class CacheEntry(
        val value: MergedGuidelinesDto,
        val expiresAt: Instant,
    )

    private val mergedCache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheTtlSeconds = 300L // 5 minutes

    /**
     * Get guidelines for a specific scope (raw, unmerged).
     */
    suspend fun getGuidelines(
        scope: GuidelinesScope,
        clientId: ClientId?,
        projectId: ProjectId?,
    ): GuidelinesDocument {
        val doc = when (scope) {
            GuidelinesScope.GLOBAL -> repository.findByClientIdIsNullAndProjectIdIsNull()
            GuidelinesScope.CLIENT -> {
                requireNotNull(clientId) { "clientId required for CLIENT scope" }
                repository.findByClientIdAndProjectId(clientId, null)
            }
            GuidelinesScope.PROJECT -> {
                requireNotNull(clientId) { "clientId required for PROJECT scope" }
                requireNotNull(projectId) { "projectId required for PROJECT scope" }
                repository.findByClientIdAndProjectId(clientId, projectId)
            }
        }
        return doc ?: createDefault(scope, clientId, projectId)
    }

    /**
     * Update guidelines for a specific scope. Only non-null categories in the request are updated.
     */
    suspend fun updateGuidelines(request: GuidelinesUpdateRequest): GuidelinesDocument {
        val clientId = request.clientId?.let { ClientId(it) }
        val projectId = request.projectId?.let { ProjectId(it) }

        val existing = getGuidelines(request.scope, clientId, projectId)

        val updated = existing.copy(
            coding = request.coding ?: existing.coding,
            git = request.git ?: existing.git,
            review = request.review ?: existing.review,
            communication = request.communication ?: existing.communication,
            approval = request.approval ?: existing.approval,
            general = request.general ?: existing.general,
            updatedAt = Instant.now(),
        )

        val saved = repository.save(updated)
        invalidateCache(clientId, projectId)

        logger.info {
            "GUIDELINES_UPDATED | scope=${request.scope} | client=$clientId | project=$projectId"
        }
        return saved
    }

    /**
     * Get merged guidelines for a client+project context.
     * Resolves GLOBAL → CLIENT → PROJECT with deep merge.
     * Result is cached for 5 minutes.
     */
    suspend fun getMergedGuidelines(
        clientId: ClientId?,
        projectId: ProjectId?,
    ): MergedGuidelinesDto {
        val cacheKey = "${clientId?.value ?: "null"}:${projectId?.value ?: "null"}"

        mergedCache[cacheKey]?.let { entry ->
            if (Instant.now().isBefore(entry.expiresAt)) {
                return entry.value
            }
            mergedCache.remove(cacheKey)
        }

        val documents = mutableListOf<GuidelinesDocument>()

        // 1. Global
        repository.findByClientIdIsNullAndProjectIdIsNull()?.let { documents.add(it) }

        // 2. Client
        if (clientId != null) {
            repository.findByClientIdAndProjectId(clientId, null)?.let { documents.add(it) }
        }

        // 3. Project
        if (clientId != null && projectId != null) {
            repository.findByClientIdAndProjectId(clientId, projectId)?.let { documents.add(it) }
        }

        val merged = mergeGuidelines(documents)

        mergedCache[cacheKey] = CacheEntry(
            value = merged,
            expiresAt = Instant.now().plusSeconds(cacheTtlSeconds),
        )

        return merged
    }

    /**
     * Delete guidelines for a specific scope.
     */
    suspend fun deleteGuidelines(
        scope: GuidelinesScope,
        clientId: ClientId?,
        projectId: ProjectId?,
    ): Boolean {
        val doc = when (scope) {
            GuidelinesScope.GLOBAL -> repository.findByClientIdIsNullAndProjectIdIsNull()
            GuidelinesScope.CLIENT -> {
                requireNotNull(clientId) { "clientId required for CLIENT scope" }
                repository.findByClientIdAndProjectId(clientId, null)
            }
            GuidelinesScope.PROJECT -> {
                requireNotNull(clientId) { "clientId required for PROJECT scope" }
                requireNotNull(projectId) { "projectId required for PROJECT scope" }
                repository.findByClientIdAndProjectId(clientId, projectId)
            }
        }

        return if (doc != null) {
            repository.delete(doc)
            invalidateCache(clientId, projectId)
            logger.info { "GUIDELINES_DELETED | scope=$scope | client=$clientId | project=$projectId" }
            true
        } else {
            false
        }
    }

    private fun invalidateCache(clientId: ClientId?, projectId: ProjectId?) {
        // Invalidate all entries that could be affected by this scope change
        val keysToRemove = mergedCache.keys.filter { key ->
            val clientVal = clientId?.value ?: "null"
            key.startsWith("$clientVal:") || key == "null:null"
        }
        keysToRemove.forEach { mergedCache.remove(it) }
    }

    private fun createDefault(
        scope: GuidelinesScope,
        clientId: ClientId?,
        projectId: ProjectId?,
    ): GuidelinesDocument = when (scope) {
        GuidelinesScope.GLOBAL -> GuidelinesDocument.defaultGlobal()
        GuidelinesScope.CLIENT -> GuidelinesDocument(clientId = clientId)
        GuidelinesScope.PROJECT -> GuidelinesDocument(clientId = clientId, projectId = projectId)
    }
}
