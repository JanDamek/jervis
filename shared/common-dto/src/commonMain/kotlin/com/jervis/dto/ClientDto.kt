package com.jervis.dto

import com.jervis.common.Constants
import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitProviderEnum
import com.jervis.domain.language.LanguageEnum
import com.jervis.dto.connection.ConnectionCapability
import kotlinx.serialization.Serializable

@Serializable
data class ClientDto(
    val id: String = Constants.GLOBAL_ID_STRING,
    val name: String,
    val description: String? = null,
    val defaultLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    val lastSelectedProjectId: String? = null,
    // External service connections assigned to this client
    val connectionIds: List<String> = emptyList(),
    // Default Git commit configuration for all projects under this client
    val gitCommitMessageFormat: String? = null,
    val gitCommitAuthorName: String? = null,
    val gitCommitAuthorEmail: String? = null,
    val gitCommitCommitterName: String? = null,
    val gitCommitCommitterEmail: String? = null,
    val gitCommitGpgSign: Boolean = false,
    val gitCommitGpgKeyId: String? = null,
    // Connection capabilities (defaults for all projects)
    val connectionCapabilities: List<ClientConnectionCapabilityDto> = emptyList(),
)

@Serializable
data class ClientConnectionCapabilityDto(
    val connectionId: String,
    val capability: ConnectionCapability,
    val resourceIdentifier: String? = null,
)
