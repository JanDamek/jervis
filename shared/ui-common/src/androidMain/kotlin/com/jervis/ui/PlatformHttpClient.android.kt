package com.jervis.ui

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Android implementation of HTTP client with WebSocket support
 */
actual fun createPlatformHttpClient(): HttpClient {
    // Trust all certificates for development (self-signed certificates)
    val trustAllCerts = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf(trustAllCerts), SecureRandom())

    val okHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts)
        .hostnameVerifier { _, _ -> true }
        .build()

    return HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
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
