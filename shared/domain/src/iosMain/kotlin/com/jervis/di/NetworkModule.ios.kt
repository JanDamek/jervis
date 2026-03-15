package com.jervis.di

import com.jervis.api.SecurityConstants
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*

/**
 * iOS implementation using CIO engine (not Darwin).
 *
 * Darwin engine has a known crash in WebSocket sendMessages completion handler
 * when connection is cancelled — CompletionHandlerException propagates as
 * unhandled C++ exception → terminateWithUnhandledException → SIGABRT.
 *
 * CIO engine handles WebSocket disconnects gracefully via Kotlin coroutines.
 */
actual fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient(CIO) {
        engine {
            requestTimeout = 0 // No timeout for WebSocket
        }

        defaultRequest {
            headers.append(SecurityConstants.CLIENT_HEADER, SecurityConstants.CLIENT_TOKEN)
            headers.append(SecurityConstants.PLATFORM_HEADER, SecurityConstants.PLATFORM_IOS)
        }

        block()
    }
}
