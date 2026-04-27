package com.jervis.di

import com.jervis.api.SecurityConstants
import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*

/**
 * iOS implementation using Darwin engine (native NSURLSession).
 *
 * CIO engine doesn't support TLS properly on Kotlin/Native iOS.
 * Darwin engine has a known crash on WebSocket disconnect — handled by
 * setUnhandledExceptionHook in Main.kt.
 */
actual fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient(Darwin) {
        engine {
            configureRequest {
                setAllowsCellularAccess(true)
            }
        }

        defaultRequest {
            headers.append(SecurityConstants.CLIENT_HEADER, SecurityConstants.CLIENT_TOKEN)
            headers.append(SecurityConstants.PLATFORM_HEADER, SecurityConstants.PLATFORM_IOS)
        }

        block()
    }
}
