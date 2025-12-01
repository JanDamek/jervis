package com.jervis.bootstrap

import com.jervis.entity.ClientReadyStatusDocument
import com.jervis.graphdb.GraphDBService
import com.jervis.rag._internal.WeaviatePerClientProvisioner
import com.jervis.repository.ClientMongoRepository
import com.jervis.repository.ClientReadyStatusRepository
import com.jervis.util.ClientSlugger
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Per-client bootstrap on application startup.
 * - Ensures Arango graph collections/indexes exist
 * - Ensures Weaviate per-client classes exist
 * - Writes ClientReadyStatus into Mongo
 *
 * Fail-fast: if any client provisioning fails, application startup stops with IllegalStateException.
 */
@Component
@Order(2)
class ClientBootstrapService(
    private val clientRepository: ClientMongoRepository,
    private val graphDBService: GraphDBService,
    private val weaviateProvisioner: WeaviatePerClientProvisioner,
    private val statusRepository: ClientReadyStatusRepository,
) {
    private val logger = KotlinLogging.logger {}

    init {
        runBlocking {
            bootstrap()
        }
    }

    private suspend fun bootstrap() {
        logger.info { "Starting per-client bootstrap (Arango + Weaviate)..." }
        val failures = mutableListOf<String>()

        clientRepository.findAll()
            .onEach { client ->
                val clientKey = client.id.toHexString()
                try {
                    val graphStatus = graphDBService.ensureSchema(clientKey)
                    weaviateProvisioner.ensureClientCollections(client.id)

                    val arangoOk = graphStatus.ok
                    val weaviateOk = true

                    statusRepository.save(
                        ClientReadyStatusDocument(
                            clientId = client.id,
                            clientSlug = clientKey,
                            arangoOk = arangoOk,
                            weaviateOk = weaviateOk,
                            arangoDetails = graphStatus.warnings.joinToString().ifBlank { null },
                            weaviateDetails = null,
                            createdCollections = graphStatus.createdCollections,
                            createdIndexes = graphStatus.createdIndexes,
                            createdGraph = graphStatus.createdGraph,
                        ),
                    )

                    if (!arangoOk || !weaviateOk) {
                        failures += "${client.name} ($clientKey)"
                    } else {
                        logger.info { "Client '$clientKey' provisioning OK" }
                    }
                } catch (e: Exception) {
                    failures += "${client.name} ($clientKey): ${e.message}"
                    logger.error(e) { "Client provisioning failed for $clientKey" }
                }
            }
            .collect()

        if (failures.isNotEmpty()) {
            val message = "Per-client bootstrap failed for: ${failures.joinToString()}"
            logger.error { message }
            throw IllegalStateException(message)
        }

        logger.info { "Per-client bootstrap finished successfully" }
    }

    // Slug generation centralized in ClientSlugger
}
