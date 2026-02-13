package com.jervis.entity

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.domain.language.LanguageEnum
import com.jervis.dto.connection.ConnectionCapability
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Client entity representing an organization or team.
 *
 * Git configuration can be defined at client level (shared defaults) and/or at project level.
 * If a project does not define Git, Git indexing is skipped for that project.
 *
 * Note: Mono-repo feature has been removed. Any previous mono-repo references were deprecated
 * and are no longer used.
 */
@Document(collection = "clients")
data class ClientDocument(
    @Id
    val id: ClientId = ClientId.generate(),
    @Indexed(unique = true)
    val name: String,
    val description: String? = null,
    val archived: Boolean = false,
    val defaultLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    val lastSelectedProjectId: ProjectId? = null,
    val connectionIds: List<ObjectId> = emptyList(),
    val gitCommitConfig: GitCommitConfig? = null,
    /** Top committers from git history analysis (name <email>) */
    val gitTopCommitters: List<String> = emptyList(),
    /**
     * Connection capabilities assigned at client level (defaults for all projects).
     * Projects can override these with their own capability assignments.
     */
    val connectionCapabilities: List<ClientConnectionCapability> = emptyList(),
    val cloudModelPolicy: CloudModelPolicy = CloudModelPolicy(),
)

/**
 * Represents a capability assignment from a specific connection at client level.
 * Serves as default for all projects under this client.
 */
data class ClientConnectionCapability(
    /** The connection providing this capability */
    val connectionId: ObjectId,
    /** The capability type (BUGTRACKER, WIKI, REPOSITORY, EMAIL, GIT) */
    val capability: ConnectionCapability,
    /** Whether this capability is enabled for indexing */
    val enabled: Boolean = true,
    /** Default resource identifier (can be overridden at project level) */
    val resourceIdentifier: String? = null,
    /** If true, index all resources; if false, only index selectedResources */
    val indexAllResources: Boolean = true,
    /** Specific resources to index (folders, projects, spaces) when indexAllResources=false */
    val selectedResources: List<String> = emptyList(),
)
