package com.jervis.service.migration

import com.jervis.entity.mongo.ClientDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Startup migration:
 * 1) Ensure a default client exists (slug = "default").
 * 2) Assign clientId of the default client to all projects with null clientId.
 */
@Service
class DefaultClientMigration(
    private val clientRepository: ClientMongoRepository,
    private val projectRepository: ProjectMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun migrate() = runBlocking {
        try {
            val defaultSlug = "default"
            var defaultClient = clientRepository.findBySlug(defaultSlug)
            if (defaultClient == null) {
                defaultClient = clientRepository.save(
                    ClientDocument(
                        name = "Default",
                        slug = defaultSlug,
                        description = "Auto-created default client",
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                    )
                )
                logger.info { "Created default client with id=${defaultClient.id} slug=${defaultClient.slug}" }
            }

            // Backfill projects without clientId
            val legacyProjects = projectRepository.findByClientIdIsNull().toList()
            if (legacyProjects.isNotEmpty()) {
                logger.info { "Backfilling ${legacyProjects.size} projects with default clientId=${defaultClient.id}" }
                legacyProjects.forEach { proj ->
                    val updated = proj.copy(clientId = defaultClient.id, updatedAt = Instant.now())
                    projectRepository.save(updated)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "DefaultClientMigration failed: ${e.message}" }
        }
    }
}
