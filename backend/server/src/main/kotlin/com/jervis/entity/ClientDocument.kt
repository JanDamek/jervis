package com.jervis.entity

import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitConfig
import com.jervis.domain.git.GitProviderEnum
import com.jervis.domain.language.LanguageEnum
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
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
    val gitProvider: GitProviderEnum? = null,
    val gitAuthType: GitAuthTypeEnum? = null,
    val gitConfig: GitConfig? = null,
    val description: String? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
    val defaultLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    val lastSelectedProjectId: ProjectId? = null,
    val connectionIds: List<ObjectId> = emptyList(),
)
