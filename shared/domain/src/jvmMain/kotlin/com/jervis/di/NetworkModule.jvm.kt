package com.jervis.di

import com.jervis.api.SecurityConstants
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import java.io.ByteArrayInputStream
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * JVM/Desktop implementation - accepts self-signed certificates for development
 *
 * WARNING: This implementation trusts ALL certificates without validation!
 * For production, implement proper certificate pinning or use a valid CA-signed certificate.
 */
actual fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient(CIO) {
        engine {
            https {
                // Create a trust manager that accepts all certificates (DEVELOPMENT ONLY!)
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
        
        // Add security headers for all requests
        defaultRequest {
            headers.append(SecurityConstants.CLIENT_HEADER, SecurityConstants.CLIENT_TOKEN)
            headers.append(SecurityConstants.PLATFORM_HEADER, SecurityConstants.PLATFORM_DESKTOP)
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
private fun getLocalIpAddress(): String? {
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
