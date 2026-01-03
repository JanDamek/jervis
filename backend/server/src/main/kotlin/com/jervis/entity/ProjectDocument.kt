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
    val buildConfig: ProjectBuildConfig? = null,
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
