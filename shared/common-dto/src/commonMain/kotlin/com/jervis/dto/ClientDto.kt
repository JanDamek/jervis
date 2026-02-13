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
    val archived: Boolean = false,
    val defaultLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    val lastSelectedProjectId: String? = null,
    // External service connections assigned to this client
    val connectionIds: List<String> = emptyList(),
    // Default Git commit configuration for all projects under this client
    val gitCommitMessageFormat: String? = null,
    val gitCommitMessagePattern: String? = null, // Pattern with placeholders (e.g., "[$project] $message")
    val gitCommitAuthorName: String? = null,
    val gitCommitAuthorEmail: String? = null,
    val gitCommitCommitterName: String? = null,
    val gitCommitCommitterEmail: String? = null,
    val gitCommitGpgSign: Boolean = false,
    val gitCommitGpgKeyId: String? = null,
    val gitTopCommitters: List<String> = emptyList(), // Top committers from git history (name <email>)
    // Connection capabilities (defaults for all projects)
    val connectionCapabilities: List<ClientConnectionCapabilityDto> = emptyList(),
    // Cloud model policy (defaults for all projects)
    val autoUseAnthropic: Boolean = false,
    val autoUseOpenai: Boolean = false,
    val autoUseGemini: Boolean = false,
)

/**
 * Connection capability configuration at client level.
 * Serves as defaults for all projects under this client.
 */
@Serializable
data class ClientConnectionCapabilityDto(
    /** The connection providing this capability */
    val connectionId: String,
    /** The capability type (BUGTRACKER, WIKI, REPOSITORY, EMAIL, GIT) */
    val capability: ConnectionCapability,
    /** Whether this capability is enabled for indexing */
    val enabled: Boolean = true,
    /** Resource identifier (e.g., Jira project key, Confluence space key) */
    val resourceIdentifier: String? = null,
    /** If true, index all resources; if false, only index selectedResources */
    val indexAllResources: Boolean = true,
    /** Specific resources to index (folders, projects, spaces) when indexAllResources=false */
    val selectedResources: List<String> = emptyList(),
)
