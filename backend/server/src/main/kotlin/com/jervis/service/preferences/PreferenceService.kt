package com.jervis.service.preferences

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.entity.AgentPreferenceDocument
import com.jervis.entity.PreferenceSource
import com.jervis.repository.AgentPreferenceRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for managing agent preferences with scope-based lookup.
 *
 * Lookup order (most specific to least specific):
 * 1. PROJECT scope (clientId + projectId)
 * 2. CLIENT scope (clientId only)
 * 3. GLOBAL scope (no IDs)
 */
@Service
class PreferenceService(
    private val preferenceRepository: AgentPreferenceRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Get preference value with fallback to higher scopes.
     * Order: PROJECT → CLIENT → GLOBAL → default
     */
    suspend fun getPreference(
        key: String,
        clientId: ClientId? = null,
        projectId: ProjectId? = null,
        default: String? = null,
    ): String? {
        // 1. Try project scope
        if (clientId != null && projectId != null) {
            preferenceRepository.findByClientIdAndProjectIdAndKey(clientId, projectId, key)?.let {
                incrementUsage(it)
                return it.value
            }
        }

        // 2. Try client scope
        if (clientId != null) {
            preferenceRepository.findByClientIdAndProjectIdAndKey(clientId, null, key)?.let {
                incrementUsage(it)
                return it.value
            }
        }

        // 3. Try global scope
        preferenceRepository.findByClientIdAndProjectIdAndKey(null, null, key)?.let {
            incrementUsage(it)
            return it.value
        }

        // 4. Return default
        return default
    }

    /**
     * Get all preferences for a scope (for loading into agent context).
     */
    suspend fun getAllPreferences(
        clientId: ClientId? = null,
        projectId: ProjectId? = null,
    ): Map<String, String> {
        val prefs = mutableMapOf<String, String>()

        // Load in order: GLOBAL → CLIENT → PROJECT (later overrides earlier)

        // 1. Global
        preferenceRepository.findByClientIdIsNullAndProjectIdIsNull().toList().forEach {
            prefs[it.key] = it.value
        }

        // 2. Client
        if (clientId != null) {
            preferenceRepository.findByClientIdAndProjectId(clientId, null).toList().forEach {
                prefs[it.key] = it.value
            }
        }

        // 3. Project
        if (clientId != null && projectId != null) {
            preferenceRepository.findByClientIdAndProjectId(clientId, projectId).toList().forEach {
                prefs[it.key] = it.value
            }
        }

        return prefs
    }

    /**
     * Set or update preference (agent self-modification).
     */
    suspend fun setPreference(
        key: String,
        value: String,
        clientId: ClientId? = null,
        projectId: ProjectId? = null,
        source: PreferenceSource = PreferenceSource.AGENT_LEARNED,
        confidence: Double = 0.7,
        description: String? = null,
    ): AgentPreferenceDocument {
        val existing = preferenceRepository.findByClientIdAndProjectIdAndKey(clientId, projectId, key)

        val preference =
            if (existing != null) {
                // Update existing
                existing.copy(
                    value = value,
                    description = description ?: existing.description,
                    confidence = confidence,
                    updatedAt = Instant.now(),
                    usageCount = existing.usageCount + 1,
                    lastUsedAt = Instant.now(),
                )
            } else {
                // Create new
                AgentPreferenceDocument(
                    clientId = clientId,
                    projectId = projectId,
                    key = key,
                    value = value,
                    description = description,
                    source = source,
                    confidence = confidence,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
            }

        val saved = preferenceRepository.save(preference)
        logger.info {
            "PREFERENCE_SET | key=$key | value=$value | " +
                "scope=${getScopeString(clientId, projectId)} | source=$source | confidence=$confidence"
        }
        return saved
    }

    /**
     * Delete preference from specific scope.
     */
    suspend fun deletePreference(
        key: String,
        clientId: ClientId? = null,
        projectId: ProjectId? = null,
    ): Boolean {
        val existing = preferenceRepository.findByClientIdAndProjectIdAndKey(clientId, projectId, key)
        return if (existing != null) {
            preferenceRepository.delete(existing)
            logger.info { "PREFERENCE_DELETED | key=$key | scope=${getScopeString(clientId, projectId)}" }
            true
        } else {
            false
        }
    }

    /**
     * Increment usage counter and update lastUsedAt.
     */
    private suspend fun incrementUsage(pref: AgentPreferenceDocument) {
        val updated =
            pref.copy(
                usageCount = pref.usageCount + 1,
                lastUsedAt = Instant.now(),
            )
        preferenceRepository.save(updated)
    }

    private fun getScopeString(
        clientId: ClientId?,
        projectId: ProjectId?,
    ): String =
        when {
            clientId == null && projectId == null -> "GLOBAL"
            projectId == null -> "CLIENT:$clientId"
            else -> "PROJECT:$clientId/$projectId"
        }
}
