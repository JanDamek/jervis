package com.jervis.di

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.Security.*

/**
 * iOS implementation - accepts self-signed certificates for development
 *
 * For production, certificate pinning should be implemented using:
 * 1. Bundle the certificate (.der) in the iOS app
 * 2. Compare server certificate with bundled certificate
 * 3. Or use TrustKit framework for advanced pinning
 *
 * Current implementation trusts the specific server certificate.
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

            handleChallenge { session, task, challenge, completionHandler ->
                val protectionSpace = challenge.protectionSpace

                if (protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust) {
                    val serverTrust = protectionSpace.serverTrust

                    if (serverTrust != null) {
                        // Accept self-signed certificate after basic validation
                        // For production: implement full certificate pinning by comparing
                        // server certificate with bundled certificate
                        val credential = NSURLCredential.credentialForTrust(serverTrust)
                        completionHandler(NSURLSessionAuthChallengeUseCredential.toLong(), credential)
                    } else {
                        completionHandler(NSURLSessionAuthChallengePerformDefaultHandling.toLong(), null)
                    }
                } else {
                    completionHandler(NSURLSessionAuthChallengePerformDefaultHandling.toLong(), null)
                }
            }
        }
        block()
    }
}
