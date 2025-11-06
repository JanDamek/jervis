package com.jervis.domain.authentication

import kotlinx.serialization.Serializable

/**
 * Represents the type of external service
 */
@Serializable
enum class ServiceTypeEnum {
    EMAIL,
    SLACK,
    TEAMS,
    DISCORD,
    JIRA,
    GIT,
}
