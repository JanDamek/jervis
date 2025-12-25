package com.jervis.entity

import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitConfig
import com.jervis.domain.language.LanguageEnum
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
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
    @Indexed(unique = true)
    val name: String,
    val description: String? = null,
    val communicationLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    val connectionIds: List<ObjectId> = emptyList(),
    val gitRemoteUrl: String? = null,
    val gitAuthType: GitAuthTypeEnum? = null,
    val gitConfig: GitConfig? = null,
    val jiraProjectKey: String? = null,
    val jiraBoardId: Long? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
)
