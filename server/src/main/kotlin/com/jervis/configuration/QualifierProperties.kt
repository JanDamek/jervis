package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "qualifier")
data class QualifierProperties(
    var concurrency: Int = 4,
    var timeout: Long = 5000,
)
