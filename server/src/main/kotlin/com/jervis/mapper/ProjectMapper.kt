package com.jervis.mapper

import com.jervis.dto.ProjectDto
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
        overrides = this.overrides,
        inspirationOnly = this.inspirationOnly,
        indexingRules = this.indexingRules,
        dependsOnProjects = this.dependsOnProjects.map { it.toHexString() },
        isDisabled = this.isDisabled,
        isActive = this.isActive,
        createdAt = this.createdAt.toString(),
        updatedAt = this.updatedAt.toString(),
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
        overrides = this.overrides,
        inspirationOnly = this.inspirationOnly,
        indexingRules = this.indexingRules,
        dependsOnProjects = this.dependsOnProjects.map { ObjectId(it) },
        isDisabled = this.isDisabled,
        isActive = this.isActive,
    )
