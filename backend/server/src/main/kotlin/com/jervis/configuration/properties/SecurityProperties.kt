package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jervis.security")
data class SecurityProperties(
    val clientToken: String,
    val ssl: SslProperties = SslProperties(),
) {
    data class SslProperties(
        val enabled: Boolean = false,
        val keyStore: String = "classpath:keystore.p12",
        val keyStorePassword: String = "changeit",
        val keyAlias: String = "jervis",
        val keyPassword: String = "changeit",
    )
}
