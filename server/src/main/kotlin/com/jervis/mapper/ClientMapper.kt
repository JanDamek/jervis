package com.jervis.mapper

import com.jervis.dto.ClientDto
import com.jervis.entity.mongo.ClientDocument
import org.bson.types.ObjectId

fun ClientDocument.toDto(): ClientDto =
    ClientDto(
        id = this.id.toHexString(),
        name = this.name,
        description = this.description,
        shortDescription = this.shortDescription,
        fullDescription = this.fullDescription,
        audioPath = this.audioPath,
        defaultCodingGuidelines = this.defaultCodingGuidelines,
        defaultReviewPolicy = this.defaultReviewPolicy,
        defaultFormatting = this.defaultFormatting,
        defaultSecretsPolicy = this.defaultSecretsPolicy,
        defaultAnonymization = this.defaultAnonymization,
        defaultInspirationPolicy = this.defaultInspirationPolicy,
        tools = this.tools,
        defaultLanguage = this.defaultLanguage,
        dependsOnProjects = this.dependsOnProjects.map { it.toHexString() },
        isDisabled = this.isDisabled,
        disabledProjects = this.disabledProjects.map { it.toHexString() },
        createdAt = this.createdAt.toString(),
        updatedAt = this.updatedAt.toString(),
    )

fun ClientDto.toDocument(): ClientDocument =
    ClientDocument(
        id = ObjectId(this.id),
        name = this.name,
        description = this.description,
        shortDescription = this.shortDescription,
        fullDescription = this.fullDescription,
        audioPath = this.audioPath,
        defaultCodingGuidelines = this.defaultCodingGuidelines,
        defaultReviewPolicy = this.defaultReviewPolicy,
        defaultFormatting = this.defaultFormatting,
        defaultSecretsPolicy = this.defaultSecretsPolicy,
        defaultAnonymization = this.defaultAnonymization,
        defaultInspirationPolicy = this.defaultInspirationPolicy,
        tools = this.tools,
        defaultLanguage = this.defaultLanguage,
        dependsOnProjects = this.dependsOnProjects.map { ObjectId(it) },
        isDisabled = this.isDisabled,
        disabledProjects = this.disabledProjects.map { ObjectId(it) },
    )
