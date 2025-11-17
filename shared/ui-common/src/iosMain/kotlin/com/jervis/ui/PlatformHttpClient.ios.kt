package com.jervis.ui

import com.jervis.api.SecurityConstants
import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.*

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
        // Add security headers for all requests
        defaultRequest {
            headers.append(SecurityConstants.CLIENT_HEADER, SecurityConstants.CLIENT_TOKEN)
            headers.append(SecurityConstants.PLATFORM_HEADER, SecurityConstants.PLATFORM_IOS)
            try {
                val localIp = getLocalIpAddress()
                if (localIp != null) {
                    headers.append(SecurityConstants.CLIENT_IP_HEADER, localIp)
                }
            } catch (e: Exception) {
                // Ignore - IP is optional
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun getLocalIpAddress(): String? {
    // For iOS, getting local IP is complex and requires proper C interop
    // Return null for now - platform header is sufficient for identification
    return null
}
