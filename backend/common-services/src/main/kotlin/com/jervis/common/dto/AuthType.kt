package com.jervis.common.dto

import kotlinx.serialization.Serializable

/**
 * Authentication type for API connections.
 * Shared between server and microservices.
 */
@Serializable
enum class AuthType {
    NONE,
    BASIC,
    BEARER,
    OAUTH2
}
