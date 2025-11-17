package com.jervis.configuration

import com.jervis.service.error.ErrorLogService
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * Security filter that validates X-Jervis-Client header on all incoming requests.
 *
 * This acts as port scanning protection:
 * - Requests without valid token: logged as attack attempts, connection dropped (no response)
 * - Requests with valid token: processed normally
 * - 404 errors with valid token: logged as client bugs for debugging
 *
 * Whitelisted paths (no token required):
 * - /actuator/health, /actuator/info, /actuator/metrics - Health checks and monitoring
 */
@Component
@Order(-100) // Run before other filters
class SecurityHeaderFilter(
    @Value("\${jervis.security.client-token}") private val expectedToken: String,
    private val errorLogService: ErrorLogService,
) : WebFilter {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val CLIENT_HEADER = "X-Jervis-Client"
        
        /**
         * Paths that don't require security token.
         * Used for health checks, monitoring, and other public endpoints.
         */
        private val WHITELISTED_PATHS = setOf(
            "/actuator/health",
            "/actuator/info",
            "/actuator/metrics",
        )
    }

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        val request = exchange.request
        val path = request.uri.path
        
        // Skip security check for whitelisted paths
        if (WHITELISTED_PATHS.any { path.startsWith(it) }) {
            return chain.filter(exchange)
        }
        
        val clientToken = request.headers.getFirst(CLIENT_HEADER)

        // Missing or invalid token - potential attack
        if (clientToken != expectedToken) {
            return mono {
                logAttackAttempt(request, clientToken)
                // Drop connection without response (act like port is closed)
                null
            }.then()
        }

        // Valid token - proceed with request processing
        // Add response handling to catch 404 errors from our client
        return chain.filter(exchange).doOnError { error ->
            mono {
                if (exchange.response.statusCode == HttpStatus.NOT_FOUND) {
                    logClientError(request, error)
                }
            }.subscribe()
        }
    }

    private suspend fun logAttackAttempt(
        request: ServerHttpRequest,
        providedToken: String?,
    ) {
        val details =
            buildString {
                append("ATTACK ATTEMPT - Invalid or missing client token\n")
                append("Timestamp: ${Instant.now()}\n")
                append("Remote Address: ${request.remoteAddress}\n")
                append("Method: ${request.method}\n")
                append("URI: ${request.uri}\n")
                append("Headers:\n")
                request.headers.forEach { (name, values) ->
                    append("  $name: ${values.joinToString(", ")}\n")
                }
                append("Provided Token: ${providedToken ?: "<missing>"}\n")
            }

        logger.warn { details }

        errorLogService.recordError(
            throwable =
                SecurityException(
                    "Unauthorized access attempt - missing or invalid $CLIENT_HEADER header",
                ),
            correlationId = "attack-${System.currentTimeMillis()}",
        )
    }

    private suspend fun logClientError(
        request: ServerHttpRequest,
        error: Throwable,
    ) {
        val details =
            buildString {
                append("CLIENT ERROR - Valid token but endpoint not found\n")
                append("Timestamp: ${Instant.now()}\n")
                append("Remote Address: ${request.remoteAddress}\n")
                append("Method: ${request.method}\n")
                append("URI: ${request.uri}\n")
                append("Error: ${error.message}\n")
            }

        logger.error { details }

        errorLogService.recordError(
            throwable = error,
            correlationId = "client-error-${System.currentTimeMillis()}",
        )
    }
}
