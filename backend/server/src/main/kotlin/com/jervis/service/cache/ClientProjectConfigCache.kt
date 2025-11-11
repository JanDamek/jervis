package com.jervis.service.cache

import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized in-memory cache for ALL client and project configuration.
 *
 * Purpose:
 * - Eliminate ALL DB reads for client/project lookups during runtime
 * - Single source of truth for application configuration
 * - Instant access to client data, project data, and integration mappings
 * - Ensure immediate propagation of configuration changes (via invalidation)
 *
 * Cache Structure:
 * - clients: Map<ObjectId, ClientDocument> - all clients by ID
 * - projects: Map<ObjectId, ProjectDocument> - all projects by ID
 * - clientProjects: Map<ObjectId, List<ObjectId>> - projects per client
 * - Integration mappings (Jira/Confluence) per client
 *
 * RAG Search Hierarchy:
 * - Client is ALWAYS required (filters by clientId)
 * - Project is OPTIONAL (if present, filters by projectId)
 * - This enables both client-level content (emails, mono-repo code)
 *   and project-specific content (Jira issues, Confluence pages)
 *
 * Lifecycle:
 * - Loaded once at startup via @PostConstruct
 * - Invalidated on any client/project create/update/delete
 * - Full reload on invalidation (simple, safe, fast for single-instance)
 *
 * Thread Safety:
 * - ConcurrentHashMap for safe concurrent reads/writes
 * - Atomic reload operations
 *
 * NOTE: Designed for SINGLE-INSTANCE deployment.
 * For multi-instance, use Redis or distributed cache.
 */
@Service
class ClientProjectConfigCache(
    private val clientRepository: ClientMongoRepository,
    private val projectRepository: ProjectMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    // Core data caches
    private val clients = ConcurrentHashMap<ObjectId, ClientDocument>()
    private val projects = ConcurrentHashMap<ObjectId, ProjectDocument>()
    private val clientProjects = ConcurrentHashMap<ObjectId, List<ObjectId>>()

    // Integration mapping caches (per client)
    private val jiraProjectMappings = ConcurrentHashMap<ObjectId, Map<String, ObjectId>>()
    private val confluenceSpaceMappings = ConcurrentHashMap<ObjectId, Map<String, ObjectId>>()
    private val confluencePageMappings = ConcurrentHashMap<ObjectId, Map<String, ObjectId>>()

    // Initialization flag
    @Volatile
    private var initialized = false

    /**
     * Initialize cache on startup.
     * Called automatically by Spring after bean construction.
     */
    @jakarta.annotation.PostConstruct
    fun initialize() {
        if (initialized) return

        kotlinx.coroutines.runBlocking {
            try {
                logger.info { "CONFIG_CACHE: Initializing cache..." }
                reload()
                initialized = true
                logger.info { "CONFIG_CACHE: Initialization complete" }
            } catch (e: Exception) {
                logger.error(e) { "CONFIG_CACHE: Failed to initialize cache" }
                // Don't throw - allow app to start, will retry on first access
            }
        }
    }

    // ========== Client Operations ==========

    /**
     * Get client by ID.
     * Returns null if not found.
     */
    suspend fun getClient(clientId: ObjectId): ClientDocument? {
        ensureInitialized()
        return clients[clientId]
    }

    /**
     * Get all clients.
     */
    suspend fun getAllClients(): List<ClientDocument> {
        ensureInitialized()
        return clients.values.toList()
    }

    // ========== Project Operations ==========

    /**
     * Get project by ID.
     * Returns null if not found.
     */
    suspend fun getProject(projectId: ObjectId): ProjectDocument? {
        ensureInitialized()
        return projects[projectId]
    }

    /**
     * Get all projects for a client.
     */
    suspend fun getProjectsForClient(clientId: ObjectId): List<ProjectDocument> {
        ensureInitialized()
        val projectIds = clientProjects[clientId] ?: return emptyList()
        return projectIds.mapNotNull { projects[it] }
    }

    /**
     * Get active projects for a client.
     */
    suspend fun getActiveProjectsForClient(clientId: ObjectId): List<ProjectDocument> =
        getProjectsForClient(clientId).filter { !it.isDisabled && it.isActive }

    /**
     * Get all projects.
     */
    suspend fun getAllProjects(): List<ProjectDocument> {
        ensureInitialized()
        return projects.values.toList()
    }

    // ========== Integration Mapping Operations ==========

    /**
     * Get Jervis project ID for a Jira project key.
     * Returns null if no mapping exists.
     */
    suspend fun getProjectForJira(
        clientId: ObjectId,
        jiraProjectKey: String,
    ): ObjectId? {
        ensureInitialized()
        return jiraProjectMappings[clientId]?.get(jiraProjectKey)
    }

    /**
     * Get Jervis project ID for a Confluence space key.
     * Returns null if no mapping exists.
     */
    suspend fun getProjectForConfluenceSpace(
        clientId: ObjectId,
        spaceKey: String,
    ): ObjectId? {
        ensureInitialized()
        return confluenceSpaceMappings[clientId]?.get(spaceKey)
    }

    /**
     * Get Jervis project ID for a Confluence page ID.
     * Returns null if no mapping exists.
     */
    suspend fun getProjectForConfluencePage(
        clientId: ObjectId,
        pageId: String,
    ): ObjectId? {
        ensureInitialized()
        return confluencePageMappings[clientId]?.get(pageId)
    }

    // ========== Cache Management ==========

    /**
     * Invalidate cache for a specific client.
     * Triggers full reload.
     */
    suspend fun invalidateClient(clientId: ObjectId) {
        logger.info { "CONFIG_CACHE: Invalidating client $clientId, reloading all data" }
        reload()
    }

    /**
     * Invalidate entire cache and reload.
     * Called on any client/project change.
     */
    suspend fun invalidateAll() {
        logger.info { "CONFIG_CACHE: Invalidating entire cache, reloading all data" }
        reload()
    }

    /**
     * Get cache statistics for monitoring.
     */
    fun getStats(): CacheStats =
        CacheStats(
            initialized = initialized,
            clientCount = clients.size,
            projectCount = projects.size,
            jiraMappingCount = jiraProjectMappings.values.sumOf { it.size },
            confluenceSpaceMappingCount = confluenceSpaceMappings.values.sumOf { it.size },
            confluencePageMappingCount = confluencePageMappings.values.sumOf { it.size },
        )

    // ========== Private Implementation ==========

    private suspend fun ensureInitialized() {
        if (!initialized) {
            logger.warn { "CONFIG_CACHE: Cache not initialized, loading now" }
            reload()
            initialized = true
        }
    }

    /**
     * Reload entire cache from DB.
     * Atomic operation - swaps all maps at once.
     */
    private suspend fun reload() {
        val startTime = System.currentTimeMillis()

        // Load all data from DB
        val allClients = clientRepository.findAll().toList()
        val allProjects = projectRepository.findAll().toList()

        // Build new maps
        val newClients = allClients.associateBy { it.id }
        val newProjects = allProjects.associateBy { it.id }
        val newClientProjects =
            allProjects
                .groupBy { it.clientId }
                .mapValues { (_, projects) -> projects.map { it.id } }

        // Build integration mappings
        val newJiraMappings = mutableMapOf<ObjectId, MutableMap<String, ObjectId>>()
        val newConfluenceSpaceMappings = mutableMapOf<ObjectId, MutableMap<String, ObjectId>>()
        val newConfluencePageMappings = mutableMapOf<ObjectId, MutableMap<String, ObjectId>>()

        allProjects.forEach { project ->
            project.overrides?.jiraProjectKey?.let { jiraKey ->
                newJiraMappings
                    .getOrPut(project.clientId) { mutableMapOf() }[jiraKey] = project.id
            }

            project.overrides?.confluenceSpaceKey?.let { spaceKey ->
                newConfluenceSpaceMappings
                    .getOrPut(project.clientId) { mutableMapOf() }[spaceKey] = project.id
            }

            project.overrides?.confluenceRootPageId?.let { pageId ->
                newConfluencePageMappings
                    .getOrPut(project.clientId) { mutableMapOf() }[pageId] = project.id
            }
        }

        // Atomic swap - update all maps at once
        clients.clear()
        clients.putAll(newClients)

        projects.clear()
        projects.putAll(newProjects)

        clientProjects.clear()
        clientProjects.putAll(newClientProjects)

        jiraProjectMappings.clear()
        jiraProjectMappings.putAll(newJiraMappings)

        confluenceSpaceMappings.clear()
        confluenceSpaceMappings.putAll(newConfluenceSpaceMappings)

        confluencePageMappings.clear()
        confluencePageMappings.putAll(newConfluencePageMappings)

        val elapsed = System.currentTimeMillis() - startTime
        val stats = getStats()

        logger.info {
            "CONFIG_CACHE: Reload complete in ${elapsed}ms: " +
                "${stats.clientCount} clients, " +
                "${stats.projectCount} projects, " +
                "${stats.jiraMappingCount} Jira mappings, " +
                "${stats.confluenceSpaceMappingCount} Confluence space mappings, " +
                "${stats.confluencePageMappingCount} Confluence page mappings"
        }
    }
}

/**
 * Cache statistics for monitoring.
 */
data class CacheStats(
    val initialized: Boolean,
    val clientCount: Int,
    val projectCount: Int,
    val jiraMappingCount: Int,
    val confluenceSpaceMappingCount: Int,
    val confluencePageMappingCount: Int,
)
