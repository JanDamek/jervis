package com.jervis.ui.storage

import com.jervis.dto.client.ClientDto
import com.jervis.dto.project.ProjectDto
import kotlinx.serialization.Serializable

/**
 * Cached data for offline mode — clients and their projects.
 * Populated when online, used as fallback when offline.
 */
@Serializable
data class CachedData(
    val clients: List<ClientDto> = emptyList(),
    val projectsByClient: Map<String, List<ProjectDto>> = emptyMap(),
)

/**
 * Platform-specific persistent storage for offline data cache.
 */
expect object OfflineDataCache {
    fun save(data: CachedData)
    fun load(): CachedData?
}
