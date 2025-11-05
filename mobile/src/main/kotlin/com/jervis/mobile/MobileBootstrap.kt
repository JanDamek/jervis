package com.jervis.mobile

import kotlinx.serialization.Serializable

/**
 * Minimal immutable bootstrap configuration for Mobile skeleton.
 * Mirrors the Desktop concept: UI -> API Client -> Server.
 */
@Serializable
data class MobileBootstrap(
    val serverBaseUrl: String,
    val clientId: String,
    val defaultProjectId: String? = null,
)
