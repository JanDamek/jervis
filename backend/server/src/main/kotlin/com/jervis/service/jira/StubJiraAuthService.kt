package com.jervis.service.jira

import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraConnection
import com.jervis.domain.jira.JiraProjectKey
import com.jervis.domain.jira.JiraTenant
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodilessEntity
import java.time.Instant
import java.util.Base64

@Service
class StubJiraAuthService(
    private val connectionRepo: com.jervis.repository.mongo.JiraConnectionMongoRepository,
    private val webClientBuilder: WebClient.Builder,
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
        val normalizedTenant = normalizeTenant(tenant)
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

        val normalizedTenant = normalizeTenant(tenant)
        val existing = connectionRepo.findByClientIdAndTenant(clientObjId, normalizedTenant)
        val saved =
            if (existing == null) {
                val doc =
                    com.jervis.entity.jira.JiraConnectionDocument(
                        clientId = clientObjId,
                        tenant = normalizedTenant,
                        email = null,
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

        logger.info { "JIRA_AUTH_STUB: completeCloudOauth saved connection for client=$clientId tenant=$normalizedTenant" }
        return JiraConnection(
            clientId = clientId,
            tenant = JiraTenant(saved.tenant),
            email = saved.email,
            accessToken = saved.accessToken,
            refreshToken = saved.refreshToken,
            expiresAt = saved.expiresAt,
            preferredUser = saved.preferredUser?.let { JiraAccountId(it) },
            mainBoard = saved.mainBoard?.let { JiraBoardId(it) },
            primaryProject = saved.primaryProject?.let { JiraProjectKey(it) },
            updatedAt = saved.updatedAt,
        )
    }

    override suspend fun testApiToken(
        tenant: String,
        email: String,
        apiToken: String,
    ): Boolean {
        val normalizedTenant = normalizeTenant(tenant)
        val client = webClientBuilder.baseUrl("https://$normalizedTenant").build()
        val basic = Base64.getEncoder().encodeToString("$email:$apiToken".toByteArray())
        return try {
            val response =
                client
                    .get()
                    .uri("/rest/api/3/myself")
                    .header("Authorization", "Basic $basic")
                    .header("Accept", "application/json")
                    .retrieve()
                    .awaitBodilessEntity()
            response.statusCode.is2xxSuccessful
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun saveApiToken(
        clientId: String,
        tenant: String,
        email: String,
        apiToken: String,
    ): JiraConnection {
        require(tenant.isNotBlank()) { "Tenant (e.g., your-domain.atlassian.net) must be provided" }
        require(email.isNotBlank()) { "Email must be provided" }
        require(apiToken.isNotBlank()) { "API token must be provided" }

        // Always persist what user entered first; validate in background to set connection state
        val isValid = testApiToken(tenant, email, apiToken)

        val clientObjId = org.bson.types.ObjectId(clientId)
        val now = Instant.now()
        val farFuture = now.plusSeconds(10L * 365 * 24 * 3600) // ~10 years
        val normalizedTenant = normalizeTenant(tenant)
        val effectiveExpiry = if (isValid) farFuture else Instant.EPOCH

        val existing = connectionRepo.findByClientIdAndTenant(clientObjId, normalizedTenant)
        val saved =
            if (existing == null) {
                val doc =
                    com.jervis.entity.jira.JiraConnectionDocument(
                        clientId = clientObjId,
                        tenant = normalizedTenant,
                        email = email,
                        accessToken = apiToken, // store token
                        refreshToken = existing?.refreshToken ?: "",
                        expiresAt = effectiveExpiry,
                        preferredUser = null,
                        mainBoard = null,
                        primaryProject = null,
                        updatedAt = now,
                    )
                connectionRepo.save(doc)
            } else {
                val updated =
                    existing.copy(
                        email = email,
                        accessToken = apiToken,
                        refreshToken = existing.refreshToken,
                        expiresAt = effectiveExpiry,
                        updatedAt = now,
                    )
                connectionRepo.save(updated)
            }

        if (!isValid) {
            logger.info {
                "JIRA_AUTH_STUB: Saved Jira API credentials but validation failed for client=$clientId tenant=$normalizedTenant (will show as disconnected)"
            }
        } else {
            logger.info { "JIRA_AUTH_STUB: Saved Jira API credentials and validated for client=$clientId tenant=$normalizedTenant" }
        }

        return JiraConnection(
            clientId = clientId,
            tenant = JiraTenant(saved.tenant),
            email = saved.email,
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
        // For API token flow, token does not expire; for OAuth flow we don't implement refresh in stub
        return conn.copy(updatedAt = Instant.now())
    }

    private fun normalizeTenant(raw: String): String =
        raw
            .trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")
}
