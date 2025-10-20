package com.jervis.configuration

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Configuration for background cognitive engine.
 *
 * Enables scheduling for coverage calculation and other periodic tasks.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "jervis.background",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class BackgroundEngineConfiguration
