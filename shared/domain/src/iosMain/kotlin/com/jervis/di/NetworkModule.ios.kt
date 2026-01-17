package com.jervis.di

import com.jervis.api.SecurityConstants
import io.ktor.client.*
import io.ktor.client.plugins.*

/**
 * iOS implementation using default engine selection
 *
 * Let Ktor automatically choose the best engine for iOS platform
 * This typically selects Darwin engine but with proper configuration
 */
actual fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient {
    // Use HttpClient() without explicit engine - Ktor will auto-select
    // This works better than forcing CIO or Darwin on iOS
    return HttpClient {
        // Add security headers for all requests
        defaultRequest {
            headers.append(SecurityConstants.CLIENT_HEADER, SecurityConstants.CLIENT_TOKEN)
            headers.append(SecurityConstants.PLATFORM_HEADER, SecurityConstants.PLATFORM_IOS)
        }

        block()
    }
}
