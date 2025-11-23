package com.jervis.service.atlassian

import com.jervis.domain.atlassian.AtlassianConnection
import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraProjectKey
import com.jervis.domain.jira.JiraTenant
import com.jervis.repository.AtlassianConnectionMongoRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodilessEntity
import java.time.Instant
import java.util.Base64

@Service
class StubAtlassianAuthService(
    private val connectionRepo: AtlassianConnectionMongoRepository,
    private val webClientBuilder: WebClient.Builder,
) : AtlassianAuthService {
    private val logger = KotlinLogging.logger {}

    override suspend fun beginCloudOauth(
        tenant: String,
        redirectUri: String,
    ): String {
        require(tenant.isNotBlank()) { "Tenant (e.g., your-domain.atlassian.net) must be provided" }
        logger.info { "JIRA_AUTH_STUB: beginCloudOauth tenant=$tenant redirect=$redirectUri" }
        // Minimal placeholder URL to guide the user; real flow should point to Atlassian auth URL
        val normalizedTenant = normalizeTenant(tenant)
        return "https://$normalizedTenant/"
    }

    override suspend fun completeCloudOauth(
        tenant: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): com.jervis.entity.atlassian.AtlassianConnectionDocument {
        require(tenant.isNotBlank()) { "Tenant (e.g., your-domain.atlassian.net) must be provided" }
        val now = Instant.now()

        val normalizedTenant = normalizeTenant(tenant)
        val existing = connectionRepo.findByTenant(normalizedTenant)
        val saved =
            if (existing == null) {
                val doc =
                    com.jervis.entity.atlassian.AtlassianConnectionDocument(
                        tenant = normalizedTenant,
                        email = null,
                        accessToken = "ACCESS_TOKEN_PLACEHOLDER",
                        preferredUser = null,
                        mainBoard = null,
                        primaryProject = null,
                        authStatus = "VALID",
                        updatedAt = now,
                    )
                connectionRepo.save(doc)
            } else {
                val updated =
                    existing.copy(
                        accessToken = "ACCESS_TOKEN_PLACEHOLDER",
                        authStatus = "VALID",
                        updatedAt = now,
                    )
                connectionRepo.save(updated)
            }

        logger.info { "JIRA_AUTH_STUB: completeCloudOauth saved connection for tenant=$normalizedTenant" }
        return saved
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
        tenant: String,
        email: String,
        apiToken: String,
    ): com.jervis.entity.atlassian.AtlassianConnectionDocument {
        require(tenant.isNotBlank()) { "Tenant (e.g., your-domain.atlassian.net) must be provided" }
        require(email.isNotBlank()) { "Email must be provided" }
        require(apiToken.isNotBlank()) { "API token must be provided" }

        // Always persist what user entered first; validate in background to set connection state
        val isValid = testApiToken(tenant, email, apiToken)

        val now = Instant.now()
        val normalizedTenant = normalizeTenant(tenant)

        val existing = connectionRepo.findByTenantAndEmail(normalizedTenant, email)
        val saved =
            if (existing == null) {
                val doc =
                    com.jervis.entity.atlassian.AtlassianConnectionDocument(
                        tenant = normalizedTenant,
                        email = email,
                        accessToken = apiToken, // store token
                        preferredUser = null,
                        mainBoard = null,
                        primaryProject = null,
                        authStatus = if (isValid) "VALID" else "INVALID",
                        updatedAt = now,
                    )
                connectionRepo.save(doc)
            } else {
                val updated =
                    existing.copy(
                        email = email,
                        accessToken = apiToken,
                        authStatus = if (isValid) "VALID" else "INVALID",
                        updatedAt = now,
                    )
                connectionRepo.save(updated)
            }

        if (!isValid) {
            logger.info {
                "JIRA_AUTH_STUB: Saved Atlassian API token but validation failed for tenant=$normalizedTenant (authStatus=INVALID)"
            }
        } else {
            logger.info { "JIRA_AUTH_STUB: Saved Atlassian API token and validated for tenant=$normalizedTenant (authStatus=VALID)" }
        }

        return saved
    }

    override suspend fun ensureValidToken(conn: AtlassianConnection): AtlassianConnection {
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
