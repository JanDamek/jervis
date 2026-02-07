package com.jervis.entity

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectGroupId
import com.jervis.dto.connection.ConnectionCapability
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * MongoDB document representing a project group.
 *
 * Groups allow organizing projects within a client for shared KB cross-visibility
 * and shared connections/resources. Projects in the same group can see each other's
 * knowledge base data.
 *
 * Connection/resource inheritance: Client -> Group -> Project
 * - Group inherits client-level connections
 * - Group can define its own connections/resources (shared by all projects in group)
 * - Projects in group inherit group resources, and can define their own overrides
 */
@Document(collection = "project_groups")
data class ProjectGroupDocument(
    @Id
    val id: ProjectGroupId = ProjectGroupId.generate(),
    @Indexed
    val clientId: ClientId,
    @Indexed(unique = true)
    val name: String,
    val description: String? = null,
    /**
     * Connection capabilities at group level.
     * Inherited from client, can be extended.
     * Projects in this group inherit these + can override.
     */
    val connectionCapabilities: List<ProjectConnectionCapability> = emptyList(),
    /** Group-level shared resources (visible to all projects in group) */
    val resources: List<ProjectResource> = emptyList(),
    /** N:M links between group-level resources */
    val resourceLinks: List<ResourceLink> = emptyList(),
)
