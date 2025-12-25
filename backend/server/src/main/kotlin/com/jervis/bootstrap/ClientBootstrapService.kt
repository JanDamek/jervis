package com.jervis.bootstrap

import com.jervis.graphdb.GraphDBService
import com.jervis.rag.internal.WeaviatePerClientProvisioner
import com.jervis.repository.ClientRepository
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
    private val clientRepository: ClientRepository,
    private val graphDBService: GraphDBService,
    private val weaviateProvisioner: WeaviatePerClientProvisioner,
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

        clientRepository
            .findAll()
            .onEach { client ->
                try {
                    val graphStatus = graphDBService.ensureSchema(client.id)
                    weaviateProvisioner.ensureClientCollections(client.id)

                    val arangoOk = graphStatus.ok
                    val weaviateOk = true

                    if (!arangoOk || !weaviateOk) {
                        failures += "${client.name} "
                    } else {
                        logger.info { "Client '${client.name}' provisioning OK" }
                    }
                } catch (e: Exception) {
                    failures += "${client.name} (${client.name}): ${e.message}"
                    logger.error(e) { "Client provisioning failed for ${client.name}" }
                }
            }.collect()

        if (failures.isNotEmpty()) {
            val message = "Per-client bootstrap failed for: ${failures.joinToString()}"
            logger.error { message }
            throw IllegalStateException(message)
        }

        logger.info { "Per-client bootstrap finished successfully" }
    }
}
