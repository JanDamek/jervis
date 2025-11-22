package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jervis.security")
data class SecurityProperties(
    val clientToken: String,
)
