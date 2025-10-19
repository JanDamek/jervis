package com.jervis.domain.git

import kotlinx.serialization.Serializable

/**
 * Git authentication mechanism type
 */
@Serializable
enum class GitAuthTypeEnum {
    SSH_KEY,
    HTTPS_PAT,
    HTTPS_BASIC,
    NONE,
}
