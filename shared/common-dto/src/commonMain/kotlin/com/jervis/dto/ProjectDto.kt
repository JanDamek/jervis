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

@Serializable
data class ProjectConnectionCapabilityDto(
    val connectionId: String,
    val capability: ConnectionCapability,
    val resourceIdentifier: String? = null,
)
