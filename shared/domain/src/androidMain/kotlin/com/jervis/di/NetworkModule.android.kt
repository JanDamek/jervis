package com.jervis.di

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Expected SHA-256 hash of the server's public key (certificate pinning)
 * Generated from: openssl x509 -in jervis_cert.pem -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
 */
private const val EXPECTED_PUBLIC_KEY_HASH = "XN2J6RQ9q5cjHeNMlCFb8mZh+kaWuWeyUkyc/nhXu2I="

/**
 * Android implementation - uses certificate pinning for security
 * Uses CIO engine (same as JVM) for better compatibility
 */
actual fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient(CIO) {
        engine {
            https {
                // Load our certificate from resources
                val certPem = object {}.javaClass.getResourceAsStream("/jervis_cert.pem")?.bufferedReader()?.readText()
                    ?: throw IllegalStateException("Certificate not found in resources")

                // Parse certificate
                val certFactory = CertificateFactory.getInstance("X.509")
                val cert = certFactory.generateCertificate(
                    ByteArrayInputStream(certPem.toByteArray())
                ) as X509Certificate

                // Create trust manager with certificate pinning
                val pinnedTrustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        if (chain.isEmpty()) {
                            throw javax.net.ssl.SSLException("Certificate chain is empty")
                        }

                        // Get the server's certificate (first in chain)
                        val serverCert = chain[0]

                        // Compute SHA-256 hash of the public key
                        val publicKeyBytes = serverCert.publicKey.encoded
                        val digest = MessageDigest.getInstance("SHA-256")
                        val publicKeyHash = Base64.getEncoder().encodeToString(digest.digest(publicKeyBytes))

                        // Verify it matches our pinned certificate
                        if (publicKeyHash != EXPECTED_PUBLIC_KEY_HASH) {
                            throw javax.net.ssl.SSLException(
                                "Certificate pinning failed! Expected: $EXPECTED_PUBLIC_KEY_HASH, Got: $publicKeyHash"
                            )
                        }
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(cert)
                }

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(pinnedTrustManager), SecureRandom())

                trustManager = pinnedTrustManager
            }
        }
        block()
    }
}
