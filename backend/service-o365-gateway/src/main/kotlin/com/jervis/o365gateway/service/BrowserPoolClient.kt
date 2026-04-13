package com.jervis.o365gateway.service

import com.jervis.o365gateway.model.BrowserPoolSessionStatus
import com.jervis.o365gateway.model.BrowserPoolTokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Ktor HTTP client for communicating with browser pod services.
 * Each connection has its own K8s service: jervis-browser-{clientId}
 */
class BrowserPoolClient(
    private val httpClient: HttpClient,
) {
    /** Resolve browser pod base URL for a given clientId (= connectionId). */
    private fun baseUrl(clientId: String): String =
        "http://jervis-browser-$clientId.jervis.svc.cluster.local:8090"

    suspend fun getToken(clientId: String): BrowserPoolTokenResponse? {
        return try {
            val response = httpClient.get("${baseUrl(clientId)}/token/$clientId")
            if (response.status.isSuccess()) {
                response.body<BrowserPoolTokenResponse>()
            } else {
                logger.warn { "No token for client $clientId: HTTP ${response.status}" }
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get token for client $clientId" }
            null
        }
    }

    suspend fun getSessionStatus(clientId: String): BrowserPoolSessionStatus? {
        return try {
            val response = httpClient.get("${baseUrl(clientId)}/session/$clientId")
            if (response.status.isSuccess()) {
                response.body<BrowserPoolSessionStatus>()
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get session status for $clientId" }
            null
        }
    }

    suspend fun initSession(clientId: String, loginUrl: String = "https://teams.microsoft.com"): Boolean {
        return try {
            val response = httpClient.post("${baseUrl(clientId)}/session/$clientId/init") {
                setBody(mapOf("login_url" to loginUrl))
                contentType(ContentType.Application.Json)
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.error(e) { "Failed to init session for $clientId" }
            false
        }
    }

    suspend fun refreshSession(clientId: String): Boolean {
        return try {
            val response = httpClient.post("${baseUrl(clientId)}/session/$clientId/refresh")
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.error(e) { "Failed to refresh session for $clientId" }
            false
        }
    }

    suspend fun deleteSession(clientId: String): Boolean {
        return try {
            val response = httpClient.delete("${baseUrl(clientId)}/session/$clientId")
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete session for $clientId" }
            false
        }
    }
}
