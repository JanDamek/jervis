package com.jervis.service.atlassian

import com.jervis.domain.atlassian.AtlassianCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodilessEntity

private val logger = KotlinLogging.logger {}

/**
 * Pure validator for Atlassian API credentials.
 * No persistence, no side effects - just HTTP validation.
 *
 * Uses Basic Auth with email + API token against Atlassian REST API.
 */
@Service
class AtlassianAuthValidator(
    private val webClientBuilder: WebClient.Builder,
) {
    /**
     * Validate Atlassian API credentials by calling /rest/api/3/myself.
     * Returns Result<Unit> - success if 2xx, failure if invalid credentials or network error.
     */
    suspend fun validateCredentials(credentials: AtlassianCredentials): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalized = credentials.normalized()
                val baseUrl = "https://${normalized.tenant}"

                logger.debug { "Validating Atlassian credentials for tenant=${normalized.tenant} email=${normalized.email}" }

                val response = webClientBuilder
                    .baseUrl(baseUrl)
                    .build()
                    .get()
                    .uri("/rest/api/3/myself")
                    .header("Authorization", normalized.toBasicAuthHeader())
                    .header("Accept", "application/json")
                    .retrieve()
                    .awaitBodilessEntity()

                if (response.statusCode.is2xxSuccessful) {
                    logger.info { "Atlassian credentials validated successfully for tenant=${normalized.tenant}" }
                } else {
                    throw IllegalStateException("Unexpected status code: ${response.statusCode}")
                }
            }.onFailure { e ->
                logger.warn { "Atlassian credentials validation failed for tenant=${credentials.tenant}: ${e.message}" }
            }
        }
}
