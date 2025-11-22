package com.jervis.service.atlassian

import com.jervis.domain.atlassian.AtlassianConnection
import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraProjectKey
import com.jervis.domain.jira.JiraTenant
import com.jervis.repository.AtlassianConnectionMongoRepository
import com.jervis.service.task.TaskSourceType
import com.jervis.service.task.UserTaskService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service managing Jira Atlassian connection authentication status.
 *
 * Rules:
 * - Connections are used for API/indexing ONLY when authStatus == VALID.
 * - UI can set VALID via an explicit connection test endpoint.
 * - On any authentication error (401/403), mark connection as INVALID and create a UserTask
 *   instructing the user to fix settings and re-test the connection.
 */
@Service
class AtlassianConnectionService(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val userTaskService: UserTaskService,
    private val jiraApiClient: AtlassianApiClient,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun markAuthInvalid(
        conn: com.jervis.entity.atlassian.AtlassianConnectionDocument,
        errorMessage: String?,
    ): com.jervis.entity.atlassian.AtlassianConnectionDocument {
        val updated =
            conn.copy(
                authStatus = "INVALID",
                lastAuthCheckedAt = Instant.now(),
                lastErrorMessage = errorMessage ?: conn.lastErrorMessage,
                updatedAt = Instant.now(),
            )
        val saved = connectionRepository.save(updated)

        runCatching {
            val title = "Jira connection authentication failed: ${conn.tenant}"
            val description =
                buildString {
                    appendLine("The Jira connection cannot authenticate (401/403). It has been disabled for API calls and indexing.")
                    appendLine()
                    appendLine("Client ID: ${conn.clientId}")
                    appendLine("Tenant: https://${conn.tenant}")
                    conn.email?.let { appendLine("Email: $it") }
                    errorMessage?.let {
                        appendLine()
                        appendLine("Error: $it")
                    }
                    appendLine()
                    appendLine("Action required:")
                    appendLine("1) Open Jira connection settings in Jervis")
                    appendLine("2) Update the Atlassian token/credentials")
                    appendLine("3) Click 'Test Connection' to re-enable the connection (authStatus → VALID)")
                }
            userTaskService.createTask(
                title = title,
                description = description,
                projectId = null,
                clientId = conn.clientId,
                sourceType = TaskSourceType.AUTHORIZATION,
                sourceUri = "jira-connection-auth://${conn.id}",
                metadata = mapOf("clientId" to conn.clientId.toHexString(), "tenant" to conn.tenant),
            )
        }.onFailure { e ->
            logger.warn(e) { "Failed to create user task for auth failure of Jira connection ${conn.id}" }
        }

        logger.info { "Jira connection ${conn.id} marked as INVALID due to authentication error" }
        return saved
    }

    suspend fun markAuthValid(clientId: ObjectId): com.jervis.entity.atlassian.AtlassianConnectionDocument {
        val existing = connectionRepository.findByClientId(clientId) ?: error("Jira connection not found for client $clientId")
        val updated =
            existing.copy(
                authStatus = "VALID",
                lastAuthCheckedAt = Instant.now(),
                lastErrorMessage = null,
                updatedAt = Instant.now(),
            )
        val saved = connectionRepository.save(updated)
        logger.info { "Jira connection for client ${clientId.toHexString()} marked as VALID" }
        return saved
    }

    /**
     * UI-triggered connection test. Only this method is allowed to set authStatus to VALID.
     * On success: sets VALID. On failure: sets INVALID and creates a user task.
     */
    suspend fun testConnection(clientId: ObjectId): com.jervis.entity.atlassian.AtlassianConnectionDocument {
        val doc = connectionRepository.findByClientId(clientId) ?: error("Jira connection not found for client $clientId")
        val conn =
            AtlassianConnection(
                clientId = doc.clientId.toHexString(),
                tenant = JiraTenant(doc.tenant),
                email = doc.email,
                accessToken = doc.accessToken,
                preferredUser = doc.preferredUser?.let { JiraAccountId(it) },
                mainBoard = doc.mainBoard?.let { JiraBoardId(it) },
                primaryProject = doc.primaryProject?.let { JiraProjectKey(it) },
                updatedAt = doc.updatedAt,
            )

        return try {
            // Lightweight check: /myself ensures token validity
            jiraApiClient.getMyself(conn)
            markAuthValid(clientId)
        } catch (e: Exception) {
            logger.warn(e) { "Jira test connection failed for client ${clientId.toHexString()}" }
            markAuthInvalid(doc, e.message)
        }
    }
}
