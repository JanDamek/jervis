package com.jervis.entity

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.domain.language.LanguageEnum
import com.jervis.dto.connection.ConnectionCapability
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * MongoDB document representing a project.
 *
 * Git configuration can be defined at project level or inherited from the client.
 * If no Git is configured, Git indexing is skipped for this project.
 */
@Document(collection = "projects")
data class ProjectDocument(
    @Id
    val id: ProjectId = ProjectId.generate(),
    @Indexed
    val clientId: ClientId,
    /** Optional group membership. Null = ungrouped project. */
    @Indexed
    val groupId: ProjectGroupId? = null,
    @Indexed(unique = true)
    val name: String,
    val description: String? = null,
    val communicationLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    val buildConfig: ProjectBuildConfig? = null,
    val cloudModelPolicy: CloudModelPolicy? = null, // null = inherit from client
    val gitCommitConfig: GitCommitConfig? = null, // Overrides client's config
    /**
     * Connection capabilities assigned to this project.
     * Allows mixing capabilities from different connections.
     * Example: GitHub for repository, Atlassian for bugtracker, GitLab for wiki
     */
    val connectionCapabilities: List<ProjectConnectionCapability> = emptyList(),
    /** Multi-resource model: all resources in this project */
    val resources: List<ProjectResource> = emptyList(),
    /** N:M links between resources (e.g., repo ↔ issue tracker) */
    val resourceLinks: List<ResourceLink> = emptyList(),
    /** JERVIS internal project for orchestrator planning. Max 1 per client. */
    val isJervisInternal: Boolean = false,
    /** Local workspace path where git repository is cloned. Null if not cloned yet. */
    val workspacePath: String? = null,
    /** Workspace clone/readiness status. Null if no git connection. */
    val workspaceStatus: WorkspaceStatus? = null,
    /** Last time workspace status was checked. */
    val lastWorkspaceCheck: java.time.Instant? = null,
)

/**
 * Build and verification configuration for a project.
 *
 * Used by Brain Agent when delegating CODING_VERIFY tasks to OpenHands.
 * If null, Brain Agent will attempt auto-detection based on files in workspace.
 */
data class ProjectBuildConfig(
    /** Commands to run for building (e.g., ["./gradlew build"]) */
    val buildCommands: List<String> = emptyList(),
    /** Commands to run for testing (e.g., ["./gradlew test"]) */
    val testCommands: List<String> = emptyList(),
    /** Maximum time to wait for verification (in seconds) */
    val verifyTimeoutSeconds: Long = 600, // 10 minutes default
)

/**
 * Git commit configuration for a project.
 *
 * Defines how commits should be formatted and signed when the agent makes changes.
 */
data class GitCommitConfig(
    /** Template for commit messages, e.g., "[{project}] {message}" */
    val messageFormat: String? = null,
    /** Pattern with placeholders, e.g., "[$project] $message" */
    val messagePattern: String? = null,
    /** Git author name for commits */
    val authorName: String? = null,
    /** Git author email for commits */
    val authorEmail: String? = null,
    /** Git committer name (if different from author) */
    val committerName: String? = null,
    /** Git committer email (if different from author) */
    val committerEmail: String? = null,
    /** Whether to GPG sign commits */
    val gpgSign: Boolean = false,
    /** GPG key ID to use for signing */
    val gpgKeyId: String? = null,
)

/**
 * A specific resource assigned to a project.
 * Multiple resources of the same capability type are allowed.
 */
data class ProjectResource(
    val id: String,
    val connectionId: ObjectId,
    val capability: ConnectionCapability,
    val resourceIdentifier: String,
    val displayName: String = "",
)

/**
 * N:M link between project resources.
 */
data class ResourceLink(
    val sourceId: String,
    val targetId: String,
)

/**
 * Represents a capability assignment from a specific connection to a project.
 * Allows projects to use specific capabilities from different connections.
 */
data class ProjectConnectionCapability(
    /** The connection providing this capability */
    val connectionId: ObjectId,
    /** The capability type (BUGTRACKER, WIKI, REPOSITORY, EMAIL, GIT) */
    val capability: ConnectionCapability,
    /** Whether this capability is enabled for this project */
    val enabled: Boolean = true,
    /** Resource identifier specific to this capability (e.g., project key, repo name, space key) */
    val resourceIdentifier: String? = null,
    /** Specific resources to index for this project (overrides client's selectedResources) */
    val selectedResources: List<String> = emptyList(),
)

/**
 * Status of project workspace (git clone) readiness.
 */
enum class WorkspaceStatus {
    /** Project has no git connection, workspace not needed */
    NOT_NEEDED,
    /** Git clone in progress */
    CLONING,
    /** Workspace ready for use */
    READY,
    /** Clone failed (auth error, network error, etc.) */
    CLONE_FAILED,
}
