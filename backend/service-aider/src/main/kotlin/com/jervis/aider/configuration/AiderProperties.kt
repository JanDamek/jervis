package com.jervis.aider.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "aider")
data class AiderProperties(
    val dataRootDir: String
)
