package com.jervis.controller.api

import org.springframework.http.server.reactive.ServerHttpRequest

internal fun EmailAccountRestController.resolveBaseUrl(request: ServerHttpRequest): String {
    val headers = request.headers

    // Try RFC 7239 Forwarded header first
    headers.getFirst("Forwarded")?.let { forwarded ->
        val parts = forwarded.split(";").map { it.trim() }
        var proto: String? = null
        var host: String? = null
        for (p in parts) {
            val kv = p.split("=")
            if (kv.size == 2) {
                when (kv[0].lowercase()) {
                    "proto" -> proto = kv[1]
                    "host" -> host = kv[1]
                }
            }
        }
        if (!proto.isNullOrBlank() && !host.isNullOrBlank()) {
            return "$proto://$host"
        }
    }

    val scheme = headers.getFirst("X-Forwarded-Proto") ?: request.uri.scheme ?: "http"
    val hostHeader = headers.getFirst("X-Forwarded-Host") ?: request.uri.host ?: "localhost"
    val portHeader = headers.getFirst("X-Forwarded-Port")

    val hostHasPort = hostHeader.contains(":")
    val defaultPort = if (scheme.equals("https", ignoreCase = true)) "443" else "80"
    val hostWithPort =
        if (!portHeader.isNullOrBlank() && !hostHasPort && portHeader != defaultPort) {
            "$hostHeader:$portHeader"
        } else if (!hostHasPort && request.uri.port != -1 && request.uri.port.toString() != defaultPort) {
            "$hostHeader:${request.uri.port}"
        } else {
            hostHeader
        }

    return "$scheme://$hostWithPort"
}

internal fun EmailAccountRestController.buildCallbackUri(
    request: ServerHttpRequest,
    path: String,
): String = resolveBaseUrl(request) + path
