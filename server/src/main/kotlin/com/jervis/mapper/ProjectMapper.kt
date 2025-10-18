package com.jervis.mapper

import com.jervis.domain.project.IndexingRules
import com.jervis.domain.project.ProjectOverrides
import com.jervis.dto.IndexingRulesDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectOverridesDto
import com.jervis.entity.mongo.ProjectDocument
import org.bson.types.ObjectId

fun ProjectDocument.toDto(): ProjectDto =
    ProjectDto(
        id = this.id.toHexString(),
        clientId = this.clientId.toHexString(),
        name = this.name,
        description = this.description,
        shortDescription = this.shortDescription,
        fullDescription = this.fullDescription,
        documentationUrls = this.documentationUrls,
        languages = this.languages,
        communicationLanguage = this.communicationLanguage,
        primaryUrl = this.primaryUrl,
        extraUrls = this.extraUrls,
        credentialsRef = this.credentialsRef,
        defaultBranch = this.defaultBranch,
        overrides = this.overrides.toDto(),
        inspirationOnly = this.inspirationOnly,
        indexingRules = this.indexingRules.toDto(),
        dependsOnProjects = this.dependsOnProjects.map { it.toHexString() },
        isDisabled = this.isDisabled,
        isActive = this.isActive,
    )

fun ProjectDto.toDocument(): ProjectDocument =
    ProjectDocument(
        id = ObjectId(this.id),
        clientId = ObjectId(this.clientId),
        name = this.name,
        description = this.description,
        shortDescription = this.shortDescription,
        fullDescription = this.fullDescription,
        documentationUrls = this.documentationUrls,
        languages = this.languages,
        communicationLanguage = this.communicationLanguage,
        primaryUrl = this.primaryUrl,
        extraUrls = this.extraUrls,
        credentialsRef = this.credentialsRef,
        defaultBranch = this.defaultBranch,
        overrides = this.overrides.toDomain(),
        inspirationOnly = this.inspirationOnly,
        indexingRules = this.indexingRules.toDomain(),
        dependsOnProjects = this.dependsOnProjects.map { ObjectId(it) },
        isDisabled = this.isDisabled,
        isActive = this.isActive,
    )

fun IndexingRules.toDto(): IndexingRulesDto =
    IndexingRulesDto(
        includeGlobs = this.includeGlobs,
        excludeGlobs = this.excludeGlobs,
        maxFileSizeMB = this.maxFileSizeMB,
    )

fun IndexingRulesDto.toDomain(): IndexingRules =
    IndexingRules(
        includeGlobs = this.includeGlobs,
        excludeGlobs = this.excludeGlobs,
        maxFileSizeMB = this.maxFileSizeMB,
    )

fun ProjectOverrides.toDto(): ProjectOverridesDto =
    ProjectOverridesDto(
        codingGuidelines = this.codingGuidelines?.toDto(),
        reviewPolicy = this.reviewPolicy?.toDto(),
        formatting = this.formatting?.toDto(),
        secretsPolicy = this.secretsPolicy?.toDto(),
        anonymization = this.anonymization?.toDto(),
        inspirationPolicy = this.inspirationPolicy?.toDto(),
        tools = this.tools?.toDto(),
        audioMonitoring = this.audioMonitoring?.toDto(),
    )

fun ProjectOverridesDto.toDomain(): ProjectOverrides =
    ProjectOverrides(
        codingGuidelines = this.codingGuidelines?.toDomain(),
        reviewPolicy = this.reviewPolicy?.toDomain(),
        formatting = this.formatting?.toDomain(),
        secretsPolicy = this.secretsPolicy?.toDomain(),
        anonymization = this.anonymization?.toDomain(),
        inspirationPolicy = this.inspirationPolicy?.toDomain(),
        tools = this.tools?.toDomain(),
        audioMonitoring = this.audioMonitoring?.toDomain(),
    )
