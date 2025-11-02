package com.jervis.entity

import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitConfig
import com.jervis.domain.git.GitProviderEnum
import com.jervis.domain.git.MonoRepoConfig
import com.jervis.domain.language.LanguageEnum
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Client entity representing an organization or team.
 *
 * Git Configuration:
 * - monoRepos: List of mono-repositories for this client (optional)
 * - monoRepoUrl: Deprecated, will be auto-migrated to monoRepos
 * - gitConfig: Optional global credentials used by all projects/mono-repos unless overridden
 * - Projects can reference mono-repos or have standalone repositories via ProjectOverrides
 *
 * Mono-repo indexing:
 * - Mono-repos are cloned once per client (not per project)
 * - Indexed with clientId only (no projectId) to enable cross-project code discovery
 */
@Document(collection = "clients")
data class ClientDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed(unique = true)
    val name: String,
    val gitProvider: GitProviderEnum? = null,
    val gitAuthType: GitAuthTypeEnum? = null,
    val monoRepoUrl: String? = null,
    val monoRepos: List<MonoRepoConfig> = emptyList(),
    val defaultBranch: String = "main",
    val gitConfig: GitConfig? = null,
    val description: String? = null,
    val shortDescription: String? = null,
    val fullDescription: String? = null,
    // Integration defaults for Confluence (client-level)
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
    val defaultLanguageEnum: LanguageEnum = LanguageEnum.getDefault(), // Default communication language for this client
    val audioPath: String? = null,
    val dependsOnProjects: List<ObjectId> = emptyList(),
    val isDisabled: Boolean = false,
    val disabledProjects: List<ObjectId> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
