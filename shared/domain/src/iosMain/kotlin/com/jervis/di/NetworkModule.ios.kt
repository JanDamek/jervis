package com.jervis.di

import com.jervis.api.SecurityConstants
import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.SystemConfiguration.*
import platform.darwin.*
import platform.posix.*

/**
 * iOS implementation - accepts self-signed certificates for development
 *
 * For production, certificate pinning should be implemented using:
 * 1. Bundle the certificate (.der) in the iOS app
 * 2. Compare server certificate with bundled certificate
 * 3. Or use TrustKit framework for advanced pinning
 *
 * Current implementation trusts all server certificates - DEVELOPMENT ONLY!
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient(Darwin) {
        engine {
            configureRequest {
                setAllowsCellularAccess(true)
                setAllowsExpensiveNetworkAccess(true)
                setAllowsConstrainedNetworkAccess(true)
            }

            // Handle SSL challenges to accept self-signed certificates
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
        
        // Add security headers for all requests
        defaultRequest {
            headers.append(SecurityConstants.CLIENT_HEADER, SecurityConstants.CLIENT_TOKEN)
            headers.append(SecurityConstants.PLATFORM_HEADER, SecurityConstants.PLATFORM_IOS)
            // Get local IP - best effort
            try {
                val localIp = getLocalIpAddress()
                if (localIp != null) {
                    headers.append(SecurityConstants.CLIENT_IP_HEADER, localIp)
                }
            } catch (e: Exception) {
                // Ignore - IP is optional
            }
        }

        block()
    }
}

/**
 * Get local IP address of the device (best effort)
 */
@OptIn(ExperimentalForeignApi::class)
private fun getLocalIpAddress(): String? {
    // For iOS, getting local IP is complex and requires proper C interop
    // Return null for now - platform header is sufficient for identification
    return null
}
