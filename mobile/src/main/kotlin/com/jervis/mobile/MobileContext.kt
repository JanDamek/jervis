package com.jervis.mobile

/**
 * Immutable runtime context for the mobile UI.
 * Intended to be passed to feature facades.
 */
data class MobileContext(
    val clientId: String,
    val projectId: String?,
)
