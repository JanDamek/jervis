package com.jervis.entity

import com.jervis.domain.language.LanguageEnum
import com.jervis.domain.project.IndexingRules
import com.jervis.domain.project.ProjectOverrides
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document representing a project.
 *
 * Git Configuration Precedence:
 * 1. Project Override (overrides.gitRemoteUrl) - highest priority, project has its own repository
 * 2. Client Mono-Repo (monoRepoId references client.monoRepos[].id) - project uses client's mono-repo
 * 3. None - project has no Git configuration, skip indexing
 *
 * Credential Precedence:
 * 1. Project Override (overrides.gitConfig) - highest priority
 * 2. Mono-Repo Override (client.monoRepos[monoRepoId].credentialsOverride) - mono-repo specific
 * 3. Client Global (client.gitConfig) - default credentials
 * 4. None - public repository, no authentication
 *
 * Mono-repo projects:
 * - Set monoRepoId to reference client's mono-repo
 * - Set projectPath to subdirectory within mono-repo (e.g., "services/backend-api")
 * - Can query entire mono-repo via RAG (cross-project code discovery)
 */
@Document(collection = "projects")
data class ProjectDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val clientId: ObjectId,
    @Indexed(unique = true)
    val name: String,
    val description: String? = null,
    val shortDescription: String? = null,
    val fullDescription: String? = null,
    val monoRepoId: String? = null,
    val projectPath: String? = null,
    val documentationUrls: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val communicationLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    val dependsOnProjects: List<ObjectId> = emptyList(),
    val inspirationOnly: Boolean = false,
    val indexingRules: IndexingRules = IndexingRules(),
    val isDisabled: Boolean = false,
    val isActive: Boolean = false,
    val overrides: ProjectOverrides? = null,
    // Last Git sync timestamp.
    val lastGitSyncAt: Instant? = null,
    // Atlassian integration (Jira + Confluence) - overrides client-level if set
    val atlassianConnectionId: ObjectId? = null,
    // Optional: Filter to specific Jira projects/Confluence spaces (project-level)
    val atlassianJiraProjects: List<String> = emptyList(),
    val atlassianConfluenceSpaces: List<String> = emptyList(),
    // Audit timestamps.
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
