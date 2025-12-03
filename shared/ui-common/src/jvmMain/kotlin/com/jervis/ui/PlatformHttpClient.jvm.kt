package com.jervis.ui

import com.jervis.api.SecurityConstants
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import java.net.NetworkInterface
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * JVM (Desktop) implementation of HTTP client with WebSocket support
 */
actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(CIO) {
        engine {
            https {
                // Trust all certificates for development (self-signed certificates)
                val trustAllCerts = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(trustAllCerts), SecureRandom())

                trustManager = trustAllCerts
            }
        }
        install(WebSockets) {
            pingIntervalMillis = 20_000
            maxFrameSize = Long.MAX_VALUE
        }
        // Security headers for WebSocket are added explicitly in DebugWebSocketClient.
    }
}

actual fun getPlatformName(): String {
    return SecurityConstants.PLATFORM_DESKTOP
}

actual fun getLocalIpAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address.address.size == 4) {
                    return address.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        // Ignore
    }
    return null
}
