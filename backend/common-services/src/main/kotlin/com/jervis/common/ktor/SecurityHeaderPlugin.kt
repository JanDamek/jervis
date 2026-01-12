package com.jervis.common.ktor

import com.jervis.api.SecurityConstants
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Ktor plugin that validates the X-Jervis-Client header.
 * If the header is missing or invalid, the request is logged and then "ignored"
 * (returns 404 with no content to behave like a dead port).
 */
val SecurityHeaderPlugin =
    createApplicationPlugin(name = "SecurityHeaderPlugin") {
        onCall { call ->
            // Skip security check for healthcheck, root, and agent card
            val path = call.request.path()
            if (path == "/" || path == "/actuator/health" || path == "/.well-known/agent-card.json") {
                return@onCall
            }

            val clientToken = call.request.headers[SecurityConstants.CLIENT_HEADER]
            val expectedToken = SecurityConstants.CLIENT_TOKEN

            if (clientToken != expectedToken) {
                val ip = call.request.origin.remoteHost
                val method = call.request.httpMethod.value
                logger.warn { "UNAUTHORIZED_ACCESS: method=$method path=$path ip=$ip - Missing or invalid security token" }

                // Behave like a dead port: return 404 Not Found with no body
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
