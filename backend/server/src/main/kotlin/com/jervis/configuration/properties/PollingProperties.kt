package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "jervis.polling")
data class PollingProperties(
    val http: Duration = Duration.ofMinutes(5),
    val imap: Duration = Duration.ofMinutes(5),
    val pop3: Duration = Duration.ofMinutes(5),
    val oauth2: Duration = Duration.ofMinutes(5),
)
