package com.jervis.service.atlassian

import com.jervis.domain.atlassian.AtlassianConnection
import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraProjectKey
import com.jervis.domain.jira.JiraTenant
import com.jervis.repository.AtlassianConnectionMongoRepository
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultAtlassianSelectionService(
    private val connectionRepo: AtlassianConnectionMongoRepository,
    private val api: AtlassianApiClient,
    private val auth: AtlassianAuthService,
) : AtlassianSelectionService {
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

    override suspend fun getConnection(clientId: ObjectId): AtlassianConnection {
        val doc = connectionRepo.findByClientId(clientId) ?: missingConnection(clientId)
        return AtlassianConnection(
            clientId = clientId.toHexString(),
            tenant = JiraTenant(doc.tenant),
            email = doc.email,
            accessToken = doc.accessToken,
            preferredUser = doc.preferredUser?.let { JiraAccountId(it) },
            mainBoard = doc.mainBoard?.let { JiraBoardId(it) },
            primaryProject = doc.primaryProject?.let { JiraProjectKey(it) },
            updatedAt = doc.updatedAt,
        )
    }

    override suspend fun ensureSelectionsOrCreateTasks(
        clientId: ObjectId,
        allowAutoDetectUser: Boolean,
    ): Pair<JiraProjectKey, JiraAccountId>? {
        val conn = getConnection(clientId).let { auth.ensureValidToken(it) }

        val projectKey = conn.primaryProject
        val preferredUser = conn.preferredUser

        // If either is missing, return null (not an error - just means client-level indexing)
        if (projectKey == null || preferredUser == null) {
            logger.info {
                "JIRA_SELECTION: Selections not configured for client=${clientId.toHexString()} " +
                    "(primaryProject=${projectKey?.value}, preferredUser=${preferredUser?.value}). " +
                    "Will use client-level indexing (all projects)."
            }
            return null
        }

        return Pair(projectKey, preferredUser)
    }

    private fun missingConnection(clientId: ObjectId): Nothing =
        throw IllegalStateException("Jira connection not configured for client ${clientId.toHexString()}")
}
