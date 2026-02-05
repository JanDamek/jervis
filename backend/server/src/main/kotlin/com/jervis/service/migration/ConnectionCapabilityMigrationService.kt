package com.jervis.service.migration

import com.jervis.dto.connection.ConnectionCapability
import com.jervis.entity.ProjectConnectionCapability
import com.jervis.entity.ProjectDocument
import com.jervis.repository.ProjectRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Migration service to convert legacy connection fields to connectionCapabilities.
 *
 * Legacy fields in ProjectDocument:
 * - gitRepositoryConnectionId + gitRepositoryIdentifier → REPOSITORY/GIT capability
 * - bugtrackerConnectionId + bugtrackerProjectKey → BUGTRACKER capability
 * - wikiConnectionId + wikiSpaceKey → WIKI capability
 *
 * This migration is idempotent - it will skip projects that already have
 * connectionCapabilities configured for the same capability type.
 *
 * Usage:
 * - Run manually via /migrate/project-capabilities endpoint
 * - Or call migrateAll() from application startup
 */
@Service
class ConnectionCapabilityMigrationService(
    private val projectRepository: ProjectRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Migrate all projects with legacy connection fields to connectionCapabilities.
     *
     * @return Migration result with counts
     */
    suspend fun migrateAll(): MigrationResult {
        logger.info { "Starting connection capability migration..." }

        val projects = projectRepository.findAll().toList()
        var migrated = 0
        var skipped = 0
        var errors = 0

        for (project in projects) {
            try {
                val result = migrateProject(project)
                when (result) {
                    MigrateStatus.MIGRATED -> migrated++
                    MigrateStatus.SKIPPED -> skipped++
                    MigrateStatus.ERROR -> errors++
                }
            } catch (e: Exception) {
                logger.error(e) { "Error migrating project ${project.name} (${project.id})" }
                errors++
            }
        }

        logger.info {
            "Connection capability migration completed: migrated=$migrated, skipped=$skipped, errors=$errors"
        }

        return MigrationResult(
            totalProjects = projects.size,
            migrated = migrated,
            skipped = skipped,
            errors = errors,
        )
    }

    /**
     * Migrate a single project's legacy fields to connectionCapabilities.
     */
    private suspend fun migrateProject(project: ProjectDocument): MigrateStatus {
        val newCapabilities = mutableListOf<ProjectConnectionCapability>()
        var hasLegacyFields = false

        // Migrate Git repository
        if (project.gitRepositoryConnectionId != null) {
            hasLegacyFields = true
            val existing = project.connectionCapabilities.find {
                it.capability == ConnectionCapability.GIT || it.capability == ConnectionCapability.REPOSITORY
            }
            if (existing == null) {
                newCapabilities.add(
                    ProjectConnectionCapability(
                        connectionId = project.gitRepositoryConnectionId,
                        capability = ConnectionCapability.GIT,
                        enabled = true,
                        resourceIdentifier = project.gitRepositoryIdentifier,
                    ),
                )
                logger.debug { "  Adding GIT capability for project ${project.name}" }
            }
        }

        // Migrate Bug Tracker
        if (project.bugtrackerConnectionId != null) {
            hasLegacyFields = true
            val existing = project.connectionCapabilities.find {
                it.capability == ConnectionCapability.BUGTRACKER
            }
            if (existing == null) {
                newCapabilities.add(
                    ProjectConnectionCapability(
                        connectionId = project.bugtrackerConnectionId,
                        capability = ConnectionCapability.BUGTRACKER,
                        enabled = true,
                        resourceIdentifier = project.bugtrackerProjectKey,
                        selectedResources = if (project.bugtrackerProjectKey != null) {
                            listOf(project.bugtrackerProjectKey)
                        } else {
                            emptyList()
                        },
                    ),
                )
                logger.debug { "  Adding BUGTRACKER capability for project ${project.name}" }
            }
        }

        // Migrate Wiki
        if (project.wikiConnectionId != null) {
            hasLegacyFields = true
            val existing = project.connectionCapabilities.find {
                it.capability == ConnectionCapability.WIKI
            }
            if (existing == null) {
                newCapabilities.add(
                    ProjectConnectionCapability(
                        connectionId = project.wikiConnectionId,
                        capability = ConnectionCapability.WIKI,
                        enabled = true,
                        resourceIdentifier = project.wikiSpaceKey,
                        selectedResources = if (project.wikiSpaceKey != null) {
                            listOf(project.wikiSpaceKey)
                        } else {
                            emptyList()
                        },
                    ),
                )
                logger.debug { "  Adding WIKI capability for project ${project.name}" }
            }
        }

        // Skip if no legacy fields or no new capabilities to add
        if (!hasLegacyFields) {
            return MigrateStatus.SKIPPED
        }

        if (newCapabilities.isEmpty()) {
            logger.debug { "  Skipping ${project.name}: capabilities already migrated" }
            return MigrateStatus.SKIPPED
        }

        // Update project with merged capabilities
        val mergedCapabilities = project.connectionCapabilities + newCapabilities
        val updatedProject = project.copy(connectionCapabilities = mergedCapabilities)
        projectRepository.save(updatedProject)

        logger.info { "  Migrated project ${project.name}: added ${newCapabilities.size} capabilities" }
        return MigrateStatus.MIGRATED
    }

    private enum class MigrateStatus {
        MIGRATED,
        SKIPPED,
        ERROR,
    }

    data class MigrationResult(
        val totalProjects: Int,
        val migrated: Int,
        val skipped: Int,
        val errors: Int,
    )
}
