package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "jervis.background")
data class BackgroundProperties(
    val enabled: Boolean = true,
    val waitOnError: Duration = Duration.ofSeconds(30),
)
