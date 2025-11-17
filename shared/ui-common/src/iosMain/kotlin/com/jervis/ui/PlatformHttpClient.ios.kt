package com.jervis.ui

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import kotlinx.cinterop.*
import platform.Foundation.*

/**
 * iOS implementation of HTTP client with WebSocket support
 * Accepts self-signed certificates for development by handling SSL challenge
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(Darwin) {
        engine {
            configureRequest {
                setAllowsCellularAccess(true)
                setAllowsExpensiveNetworkAccess(true)
                setAllowsConstrainedNetworkAccess(true)
            }

            // Handle SSL challenges to accept self-signed certificates
            // This is required for WebSocket connections (wss://)
            handleChallenge { _, _, challenge, completionHandler ->
                val protectionSpace = challenge.protectionSpace
                if (protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust) {
                    protectionSpace.serverTrust?.let { trust ->
                        val credential = NSURLCredential.credentialForTrust(trust)
                        completionHandler(0, credential) // 0 = NSURLSessionAuthChallengeUseCredential
                        return@handleChallenge
                    }
                }
                completionHandler(1, null) // 1 = NSURLSessionAuthChallengePerformDefaultHandling
            }
        }
        install(WebSockets) {
            pingIntervalMillis = 20_000
            maxFrameSize = Long.MAX_VALUE
        }
        // Add security header for all requests
        defaultRequest {
            headers.append("X-Jervis-Client", "a7f3c9e2-4b8d-11ef-9a1c-0242ac120002")
        }
    }
}
