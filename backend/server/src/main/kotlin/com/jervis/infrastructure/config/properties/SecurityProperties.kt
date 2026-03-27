package com.jervis.infrastructure.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jervis.security")
data class SecurityProperties(
    val clientToken: String,
)
