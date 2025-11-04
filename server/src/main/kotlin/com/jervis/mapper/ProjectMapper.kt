package com.jervis.mapper

import com.jervis.dto.IndexingRulesDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectOverridesDto
import com.jervis.entity.ProjectDocument
import org.bson.types.ObjectId

fun ProjectDocument.toDto(): ProjectDto =
    ProjectDto(
        id = this.id.toHexString(),
        clientId = this.clientId.toHexString(),
        name = this.name,
        description = this.description,
        shortDescription = this.shortDescription,
        fullDescription = this.fullDescription,
        projectPath = this.projectPath,
        documentationUrls = this.documentationUrls,
        languages = this.languages,
        communicationLanguageEnum = this.communicationLanguageEnum,
        dependsOnProjects = this.dependsOnProjects.map { it.toHexString() },
        inspirationOnly = this.inspirationOnly,
        indexingRules =
            IndexingRulesDto(
                includeGlobs = this.indexingRules.includeGlobs,
                excludeGlobs = this.indexingRules.excludeGlobs,
                maxFileSizeMB = this.indexingRules.maxFileSizeMB,
            ),
        isDisabled = this.isDisabled,
        isActive = this.isActive,
        overrides = this.overrides?.toDto(),
    )

fun ProjectDto.toDocument(): ProjectDocument {
    val resolvedId = if (ObjectId.isValid(this.id)) ObjectId(this.id) else ObjectId.get()
    val resolvedClientId = requireNotNull(this.clientId) { "clientId is required" }
    return ProjectDocument(
        id = resolvedId,
        clientId = ObjectId(resolvedClientId),
        name = this.name,
        description = this.description,
        shortDescription = this.shortDescription,
        fullDescription = this.fullDescription,
        projectPath = this.projectPath,
        documentationUrls = this.documentationUrls,
        languages = this.languages,
        communicationLanguageEnum = this.communicationLanguageEnum,
        dependsOnProjects = this.dependsOnProjects.filter { ObjectId.isValid(it) }.map { ObjectId(it) },
        inspirationOnly = this.inspirationOnly,
        indexingRules =
            com.jervis.domain.project.IndexingRules(
                includeGlobs = this.indexingRules.includeGlobs,
                excludeGlobs = this.indexingRules.excludeGlobs,
                maxFileSizeMB = this.indexingRules.maxFileSizeMB,
            ),
        isDisabled = this.isDisabled,
        isActive = this.isActive,
        overrides = this.overrides?.toDomain(),
    )
}

fun com.jervis.domain.project.ProjectOverrides.toDto(): ProjectOverridesDto =
    ProjectOverridesDto(
        gitRemoteUrl = this.gitRemoteUrl,
        gitAuthType = this.gitAuthType,
        gitConfig = this.gitConfig?.toDto(),
        jiraProjectKey = this.jiraProjectKey,
        confluenceSpaceKey = this.confluenceSpaceKey,
        confluenceRootPageId = this.confluenceRootPageId,
    )

fun ProjectOverridesDto.toDomain(): com.jervis.domain.project.ProjectOverrides =
    com.jervis.domain.project.ProjectOverrides(
        gitRemoteUrl = this.gitRemoteUrl,
        gitAuthType = this.gitAuthType,
        gitConfig = this.gitConfig?.toDomain(),
        jiraProjectKey = this.jiraProjectKey,
        confluenceSpaceKey = this.confluenceSpaceKey,
        confluenceRootPageId = this.confluenceRootPageId,
    )
