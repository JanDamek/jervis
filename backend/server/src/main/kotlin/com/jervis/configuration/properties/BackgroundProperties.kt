package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * BackgroundEngine timing configuration (prefix: `jervis.background`).
 *
 * Controls the three independent loops in BackgroundEngine:
 * - Qualification loop (CPU): runs every [waitInterval], processes READY_FOR_QUALIFICATION tasks
 * - Execution loop (GPU): runs when idle, processes READY_FOR_GPU tasks
 * - Orchestrator poll loop: polls Python orchestrator every 5s (hardcoded)
 */
@ConfigurationProperties(prefix = "jervis.background")
data class BackgroundProperties(
    val waitOnError: Duration,    // Delay after error before retrying (default 1m)
    val waitOnStartup: Duration,  // Delay before BackgroundEngine starts processing (default 10s)
    val waitInterval: Duration,   // Qualification loop sleep between cycles (default 30s)
)
