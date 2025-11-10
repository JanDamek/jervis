package com.jervis.service.jira

import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraConnection
import com.jervis.domain.jira.JiraProjectKey
import com.jervis.domain.jira.JiraTenant
import com.jervis.repository.mongo.JiraConnectionMongoRepository
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultJiraSelectionService(
    private val connectionRepo: JiraConnectionMongoRepository,
    private val api: JiraApiClient,
    private val auth: JiraAuthService,
) : JiraSelectionService {
    private val logger = KotlinLogging.logger {}

    override suspend fun setPreferredUser(
        clientId: ObjectId,
        accountId: JiraAccountId,
    ) {
        val doc = connectionRepo.findByClientId(clientId) ?: missingConnection(clientId)
        val updated = doc.copy(preferredUser = accountId.value, updatedAt = Instant.now())
        connectionRepo.save(updated)
        logger.info { "JIRA_SELECTION: Set preferred user for client=${clientId.toHexString()} accountId=${accountId.value}" }
    }

    override suspend fun setPrimaryProject(
        clientId: ObjectId,
        projectKey: JiraProjectKey,
    ) {
        val conn = getConnection(clientId)
        val exists = api.projectExists(conn, projectKey)
        require(exists) { "Jira project ${projectKey.value} does not exist or is not accessible" }
        val doc = requireNotNull(connectionRepo.findByClientId(clientId))
        val updated = doc.copy(primaryProject = projectKey.value, updatedAt = Instant.now())
        connectionRepo.save(updated)
        logger.info { "JIRA_SELECTION: Set primary project for client=${clientId.toHexString()} project=${projectKey.value}" }
    }

    override suspend fun setMainBoard(
        clientId: ObjectId,
        boardId: JiraBoardId,
    ) {
        val doc = connectionRepo.findByClientId(clientId) ?: missingConnection(clientId)
        val updated = doc.copy(mainBoard = boardId.value, updatedAt = Instant.now())
        connectionRepo.save(updated)
        logger.info { "JIRA_SELECTION: Set main board for client=${clientId.toHexString()} board=${boardId.value}" }
    }

    override suspend fun getConnection(clientId: ObjectId): JiraConnection {
        val doc = connectionRepo.findByClientId(clientId) ?: missingConnection(clientId)
        return JiraConnection(
            clientId = clientId.toHexString(),
            tenant = JiraTenant(doc.tenant),
            email = doc.email,
            accessToken = doc.accessToken,
            refreshToken = doc.refreshToken,
            expiresAt = doc.expiresAt,
            preferredUser = doc.preferredUser?.let { JiraAccountId(it) },
            mainBoard = doc.mainBoard?.let { JiraBoardId(it) },
            primaryProject = doc.primaryProject?.let { JiraProjectKey(it) },
            updatedAt = doc.updatedAt,
        )
    }

    override suspend fun ensureSelectionsOrCreateTasks(
        clientId: ObjectId,
        allowAutoDetectUser: Boolean,
    ): Pair<JiraProjectKey, JiraAccountId> {
        val conn = getConnection(clientId).let { auth.ensureValidToken(it) }

        val projectKey =
            conn.primaryProject
                ?: throw IllegalStateException(
                    "Jira primary project not configured for client ${clientId.toHexString()}. Configure it during desktop/server setup.",
                )

        val preferred =
            conn.preferredUser
                ?: throw IllegalStateException(
                    "Jira preferred user not configured for client ${clientId.toHexString()}. Configure it during desktop/server setup.",
                )

        return Pair(projectKey, preferred)
    }

    private fun missingConnection(clientId: ObjectId): Nothing =
        throw IllegalStateException("Jira connection not configured for client ${clientId.toHexString()}")
}
