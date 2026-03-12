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
 * Ktor HTTP client for communicating with the O365 Browser Pool service.
 */
class BrowserPoolClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    suspend fun getToken(clientId: String): BrowserPoolTokenResponse? {
        return try {
            val response = httpClient.get("$baseUrl/token/$clientId")
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
            val response = httpClient.get("$baseUrl/session/$clientId")
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
            val response = httpClient.post("$baseUrl/session/$clientId/init") {
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
            val response = httpClient.post("$baseUrl/session/$clientId/refresh")
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.error(e) { "Failed to refresh session for $clientId" }
            false
        }
    }

    suspend fun deleteSession(clientId: String): Boolean {
        return try {
            val response = httpClient.delete("$baseUrl/session/$clientId")
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete session for $clientId" }
            false
        }
    }
}
