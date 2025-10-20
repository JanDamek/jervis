package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "encryption")
data class EncryptionProperties(
    val masterKey: String,
)
