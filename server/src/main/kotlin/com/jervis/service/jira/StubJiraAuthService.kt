package com.jervis.service.jira

import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraConnection
import com.jervis.domain.jira.JiraProjectKey
import com.jervis.domain.jira.JiraTenant
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class StubJiraAuthService(
    private val connectionRepo: com.jervis.repository.mongo.JiraConnectionMongoRepository,
) : JiraAuthService {
    private val logger = KotlinLogging.logger {}

    override suspend fun beginCloudOauth(
        clientId: String,
        tenant: String,
        redirectUri: String,
    ): String {
        require(tenant.isNotBlank()) { "Tenant (e.g., your-domain.atlassian.net) must be provided" }
        logger.info { "JIRA_AUTH_STUB: beginCloudOauth client=$clientId tenant=$tenant redirect=$redirectUri" }
        // Minimal placeholder URL to guide the user; real flow should point to Atlassian auth URL
        val normalizedTenant = tenant.trim().removeSuffix("/")
        return "https://$normalizedTenant/"
    }

    override suspend fun completeCloudOauth(
        clientId: String,
        tenant: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): JiraConnection {
        require(tenant.isNotBlank()) { "Tenant (e.g., your-domain.atlassian.net) must be provided" }
        val clientObjId = org.bson.types.ObjectId(clientId)
        val now = Instant.now()
        val expires = now.plusSeconds(3600)

        val existing = connectionRepo.findByClientIdAndTenant(clientObjId, tenant)
        val saved =
            if (existing == null) {
                val doc =
                    com.jervis.entity.jira.JiraConnectionDocument(
                        clientId = clientObjId,
                        tenant = tenant,
                        accessToken = "ACCESS_TOKEN_PLACEHOLDER",
                        refreshToken = "REFRESH_TOKEN_PLACEHOLDER",
                        expiresAt = expires,
                        preferredUser = null,
                        mainBoard = null,
                        primaryProject = null,
                        updatedAt = now,
                    )
                connectionRepo.save(doc)
            } else {
                val updated =
                    existing.copy(
                        accessToken = "ACCESS_TOKEN_PLACEHOLDER",
                        refreshToken = "REFRESH_TOKEN_PLACEHOLDER",
                        expiresAt = expires,
                        updatedAt = now,
                    )
                connectionRepo.save(updated)
            }

        logger.info { "JIRA_AUTH_STUB: completeCloudOauth saved connection for client=$clientId tenant=$tenant" }
        return JiraConnection(
            clientId = clientId,
            tenant = JiraTenant(saved.tenant),
            accessToken = saved.accessToken,
            refreshToken = saved.refreshToken,
            expiresAt = saved.expiresAt,
            preferredUser = saved.preferredUser?.let { JiraAccountId(it) },
            mainBoard = saved.mainBoard?.let { JiraBoardId(it) },
            primaryProject = saved.primaryProject?.let { JiraProjectKey(it) },
            updatedAt = saved.updatedAt,
        )
    }

    override suspend fun ensureValidToken(conn: JiraConnection): JiraConnection {
        if (conn.expiresAt.isBefore(Instant.now())) {
            logger.warn { "JIRA_AUTH_STUB: Access token expired for tenant=${conn.tenant.value}; refresh not implemented" }
        }
        return conn.copy(updatedAt = Instant.now())
    }
}
