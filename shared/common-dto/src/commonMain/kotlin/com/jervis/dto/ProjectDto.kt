package com.jervis.dto

import com.jervis.common.Constants
import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.language.LanguageEnum
import com.jervis.dto.connection.ConnectionCapability
import kotlinx.serialization.Serializable

@Serializable
data class ProjectDto(
    val id: String = Constants.GLOBAL_ID_STRING,
    val clientId: String?,
    val name: String,
    val description: String? = null,
    val communicationLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    // Project-specific resource identifiers within client's connections
    // These refer to resources in the parent client's connections
    val gitRepositoryConnectionId: String? = null, // Which connection has the Git repo
    val gitRepositoryIdentifier: String? = null, // repo name, URL, or path within that connection
    val bugtrackerConnectionId: String? = null,
    val bugtrackerProjectKey: String? = null,
    val wikiConnectionId: String? = null,
    val wikiSpaceKey: String? = null,
    // Git commit configuration override (inherits from client if null)
    val gitCommitMessageFormat: String? = null,
    val gitCommitAuthorName: String? = null,
    val gitCommitAuthorEmail: String? = null,
    val gitCommitCommitterName: String? = null,
    val gitCommitCommitterEmail: String? = null,
    val gitCommitGpgSign: Boolean? = null, // null = inherit from client
    val gitCommitGpgKeyId: String? = null,
    // Connection capabilities
    val connectionCapabilities: List<ProjectConnectionCapabilityDto> = emptyList(),
)

/**
 * Connection capability configuration at project level.
 * Overrides client-level defaults when specified.
 */
@Serializable
data class ProjectConnectionCapabilityDto(
    /** The connection providing this capability */
    val connectionId: String,
    /** The capability type (BUGTRACKER, WIKI, REPOSITORY, EMAIL, GIT) */
    val capability: ConnectionCapability,
    /** Whether this capability is enabled for this project */
    val enabled: Boolean = true,
    /** Resource identifier specific to this project (e.g., project key, repo name) */
    val resourceIdentifier: String? = null,
    /** Specific resources to index for this project (overrides client's selectedResources) */
    val selectedResources: List<String> = emptyList(),
)
