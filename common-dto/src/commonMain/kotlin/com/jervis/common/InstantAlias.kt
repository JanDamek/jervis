package com.jervis.common

import kotlinx.datetime.Instant as KotlinInstant

/**
 * Type alias for platform-agnostic instant.
 * On JVM: Can be converted to/from java.time.Instant
 * On Native/JS: Uses kotlinx.datetime.Instant
 */
typealias PlatformInstant = KotlinInstant
