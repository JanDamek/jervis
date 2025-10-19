package com.jervis.domain.git

import kotlinx.serialization.Serializable

/**
 * Git provider type enumeration.
 * Defines supported Git hosting platforms.
 */
@Serializable
enum class GitProviderEnum {
    GITHUB,
    GITLAB,
    BITBUCKET,
    AZURE_DEVOPS,
    GITEA,
    CUSTOM,
}
