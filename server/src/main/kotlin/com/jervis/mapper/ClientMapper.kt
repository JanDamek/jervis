package com.jervis.mapper

import com.jervis.dto.ClientDto
import com.jervis.entity.mongo.ClientDocument
import org.bson.types.ObjectId

fun ClientDocument.toDto(): ClientDto =
    ClientDto(
        id = this.id.toHexString(),
        name = this.name,
        shortDescription = this.shortDescription,
        fullDescription = this.fullDescription,
        defaultCodingGuidelines = this.defaultCodingGuidelines.toDto(),
        defaultReviewPolicy = this.defaultReviewPolicy.toDto(),
        defaultFormatting = this.defaultFormatting.toDto(),
        defaultSecretsPolicy = this.defaultSecretsPolicy.toDto(),
        defaultAnonymization = this.defaultAnonymization.toDto(),
        defaultInspirationPolicy = this.defaultInspirationPolicy.toDto(),
        tools = this.tools.toDto(),
        defaultLanguage = this.defaultLanguage,
        dependsOnProjects = this.dependsOnProjects.map { it.toHexString() },
        isDisabled = this.isDisabled,
        disabledProjects = this.disabledProjects.map { it.toHexString() },
    )

fun ClientDto.toDocument(): ClientDocument =
    ClientDocument(
        id = ObjectId(this.id),
        name = this.name,
        shortDescription = this.shortDescription,
        fullDescription = this.fullDescription,
        defaultCodingGuidelines = this.defaultCodingGuidelines.toDomain(),
        defaultReviewPolicy = this.defaultReviewPolicy.toDomain(),
        defaultFormatting = this.defaultFormatting.toDomain(),
        defaultSecretsPolicy = this.defaultSecretsPolicy.toDomain(),
        defaultAnonymization = this.defaultAnonymization.toDomain(),
        defaultInspirationPolicy = this.defaultInspirationPolicy.toDomain(),
        tools = this.tools.toDomain(),
        defaultLanguage = this.defaultLanguage,
        dependsOnProjects = this.dependsOnProjects.map { ObjectId(it) },
        isDisabled = this.isDisabled,
        disabledProjects = this.disabledProjects.map { ObjectId(it) },
    )
