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
    val groupId: String? = null,
    val name: String,
    val description: String? = null,
    val communicationLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    // Git commit configuration override (inherits from client if null)
    val gitCommitMessageFormat: String? = null,
    val gitCommitMessagePattern: String? = null, // null = inherit from client
    val gitCommitAuthorName: String? = null,
    val gitCommitAuthorEmail: String? = null,
    val gitCommitCommitterName: String? = null,
    val gitCommitCommitterEmail: String? = null,
    val gitCommitGpgSign: Boolean? = null, // null = inherit from client
    val gitCommitGpgKeyId: String? = null,
    // Connection capabilities (legacy - used by polling)
    val connectionCapabilities: List<ProjectConnectionCapabilityDto> = emptyList(),
    // Multi-resource model with N:M linking
    val resources: List<ProjectResourceDto> = emptyList(),
    val resourceLinks: List<ResourceLinkDto> = emptyList(),
    // Cloud model policy override (null = inherit from client)
    val autoUseAnthropic: Boolean? = null,
    val autoUseOpenai: Boolean? = null,
    val autoUseGemini: Boolean? = null,
    // Workspace status (read-only, mapped from server)
    val workspaceStatus: String? = null,       // READY, CLONING, CLONE_FAILED_AUTH/NETWORK/NOT_FOUND/OTHER, NOT_NEEDED
    val workspaceError: String? = null,        // Last error message if CLONE_FAILED_*
    val workspaceRetryCount: Int = 0,
    val nextWorkspaceRetryAt: String? = null,  // ISO timestamp of next retry
    // Internal project flag (hidden from UI lists)
    val isJervisInternal: Boolean = false,
)

/**
 * Connection capability configuration at project level.
 * Overrides client-level defaults when specified.
 */
/** Filter out internal projects from user-facing lists. */
fun List<ProjectDto>.filterVisible() = filter { !it.isJervisInternal }

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

/**
 * A specific resource assigned to a project.
 * Multiple resources of the same capability type are allowed (e.g., 5 repos, 3 bug trackers).
 */
@Serializable
data class ProjectResourceDto(
    /** Unique ID within project (empty = new, server generates) */
    val id: String = "",
    /** Connection providing this resource */
    val connectionId: String,
    /** Resource capability type */
    val capability: ConnectionCapability,
    /** Provider-specific identifier (Jira project key, repo name, Confluence space key) */
    val resourceIdentifier: String,
    /** Human-readable display name */
    val displayName: String = "",
)

/**
 * N:M link between project resources.
 * Typically links REPOSITORY ↔ BUGTRACKER or REPOSITORY ↔ WIKI.
 * Resources without links are project-level (e.g., overall Jira project).
 */
@Serializable
data class ResourceLinkDto(
    /** Source resource ID (typically REPOSITORY) */
    val sourceId: String,
    /** Target resource ID (typically BUGTRACKER, WIKI) */
    val targetId: String,
)
