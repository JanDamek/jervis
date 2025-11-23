package com.jervis.service.atlassian

import com.jervis.domain.atlassian.AtlassianCredentials
import com.jervis.entity.atlassian.AtlassianConnectionDocument
import com.jervis.repository.AtlassianConnectionMongoRepository
import com.jervis.repository.ClientMongoRepository
import com.jervis.service.task.TaskSourceType
import com.jervis.service.task.UserTaskService
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Service for managing Atlassian connections.
 *
 * Architecture:
 * - AtlassianConnectionDocument = shared resource (can be used by multiple clients/projects)
 * - ClientDocument.atlassianConnectionId → references connection
 * - Connection authStatus: VALID (can be used) | INVALID (needs re-authentication)
 *
 * Rules:
 * - Connections are used for API/indexing ONLY when authStatus == VALID
 * - Only UI-triggered validation can set authStatus to VALID
 * - On auth errors during polling/indexing, mark INVALID and create user task
 */
@Service
class AtlassianConnectionService(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val clientRepository: ClientMongoRepository,
    private val authValidator: AtlassianAuthValidator,
    private val userTaskService: UserTaskService,
) {
    /**
     * Save Atlassian connection with credentials validation and link to client.
     * Validates credentials first, then persists connection, then links to client.
     *
     * Returns Result<connectionId> - success if validated and saved, failure otherwise.
     */
    suspend fun saveAndLinkConnection(
        credentials: AtlassianCredentials,
        clientId: ObjectId,
    ): Result<ObjectId> = coroutineScope {
        val normalized = credentials.normalized()

        // 1. Validate credentials via HTTP call
        authValidator.validateCredentials(normalized)
            .mapCatching {
                // 2. Find or create connection
                val existing = connectionRepository.findByTenantAndEmail(normalized.tenant, normalized.email)
                val connection = if (existing == null) {
                    AtlassianConnectionDocument(
                        tenant = normalized.tenant,
                        email = normalized.email,
                        accessToken = normalized.apiToken,
                        authStatus = "VALID",
                        lastAuthCheckedAt = Instant.now(),
                        updatedAt = Instant.now(),
                    )
                } else {
                    existing.copy(
                        accessToken = normalized.apiToken,
                        authStatus = "VALID",
                        lastAuthCheckedAt = Instant.now(),
                        lastErrorMessage = null,
                        updatedAt = Instant.now(),
                    )
                }

                val saved = connectionRepository.save(connection)
                logger.info { "Saved Atlassian connection ${saved.id} for tenant=${saved.tenant} (authStatus=VALID)" }

                // 3. Link connection to client
                linkConnectionToClient(clientId, saved.id)

                saved.id
            }
    }

    /**
     * Test existing connection for a client.
     * Re-validates credentials and updates authStatus accordingly.
     */
    suspend fun testConnectionForClient(clientId: ObjectId): Result<Unit> = coroutineScope {
        runCatching {
            // Get client's connection
            val client = clientRepository.findById(clientId)
                ?: throw IllegalArgumentException("Client not found: $clientId")

            val connectionId = client.atlassianConnectionId
                ?: throw IllegalArgumentException("Client $clientId has no Atlassian connection")

            val connection = connectionRepository.findById(connectionId)
                ?: throw IllegalStateException("Connection $connectionId not found")

            // Validate credentials
            val credentials = AtlassianCredentials(
                tenant = connection.tenant,
                email = connection.email ?: throw IllegalStateException("Connection has no email"),
                apiToken = connection.accessToken,
            )

            authValidator.validateCredentials(credentials)
                .onSuccess {
                    // Mark as VALID
                    val updated = connection.copy(
                        authStatus = "VALID",
                        lastAuthCheckedAt = Instant.now(),
                        lastErrorMessage = null,
                        updatedAt = Instant.now(),
                    )
                    connectionRepository.save(updated)
                    logger.info { "Connection ${connection.id} validated successfully (authStatus=VALID)" }
                }
                .onFailure { error ->
                    // Mark as INVALID and create user task
                    markAuthInvalid(connection, clientId, error.message)
                    throw error
                }
                .getOrThrow()
        }
    }

    /**
     * Link Atlassian connection to a client.
     * Sets client.atlassianConnectionId to the connection ID.
     */
    suspend fun linkConnectionToClient(
        clientId: ObjectId,
        connectionId: ObjectId,
    ) {
        val client = clientRepository.findById(clientId)
            ?: throw IllegalArgumentException("Client not found: $clientId")

        val updated = client.copy(
            atlassianConnectionId = connectionId,
            updatedAt = Instant.now(),
        )
        clientRepository.save(updated)
        logger.info { "Linked Atlassian connection $connectionId to client $clientId" }
    }

    /**
     * Mark connection as INVALID due to authentication error.
     * Creates user task to notify about the issue.
     */
    suspend fun markAuthInvalid(
        connection: AtlassianConnectionDocument,
        clientId: ObjectId,
        errorMessage: String?,
    ) {
        val updated = connection.copy(
            authStatus = "INVALID",
            lastAuthCheckedAt = Instant.now(),
            lastErrorMessage = errorMessage ?: connection.lastErrorMessage,
            updatedAt = Instant.now(),
        )
        connectionRepository.save(updated)

        // Create user task
        runCatching {
            val title = "Atlassian connection authentication failed: ${connection.tenant}"
            val description = buildString {
                appendLine("The Atlassian connection cannot authenticate. It has been disabled for API calls and indexing.")
                appendLine()
                appendLine("Connection ID: ${connection.id}")
                appendLine("Tenant: https://${connection.tenant}")
                connection.email?.let { appendLine("Email: $it") }
                errorMessage?.let {
                    appendLine()
                    appendLine("Error: $it")
                }
                appendLine()
                appendLine("Action required:")
                appendLine("1) Open Atlassian connection settings in Jervis")
                appendLine("2) Update the API token")
                appendLine("3) Click 'Test Connection' to re-enable (authStatus → VALID)")
            }

            userTaskService.createTask(
                title = title,
                description = description,
                projectId = null,
                clientId = clientId,
                sourceType = TaskSourceType.AUTHORIZATION,
                sourceUri = "atlassian-connection-auth://${connection.id}",
                metadata = mapOf(
                    "connectionId" to connection.id.toHexString(),
                    "tenant" to connection.tenant,
                ),
            )
        }.onFailure { e ->
            logger.warn(e) { "Failed to create user task for auth failure of connection ${connection.id}" }
        }

        logger.warn { "Connection ${connection.id} marked as INVALID due to authentication error: $errorMessage" }
    }

    /**
     * Get connection for a client (from client.atlassianConnectionId).
     */
    suspend fun getConnectionForClient(clientId: ObjectId): AtlassianConnectionDocument? {
        val client = clientRepository.findById(clientId) ?: return null
        val connectionId = client.atlassianConnectionId ?: return null
        return connectionRepository.findById(connectionId)
    }
}
