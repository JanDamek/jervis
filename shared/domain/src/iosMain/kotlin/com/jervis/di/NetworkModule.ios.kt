package com.jervis.di

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.darwin.*

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
        block()
    }
}
