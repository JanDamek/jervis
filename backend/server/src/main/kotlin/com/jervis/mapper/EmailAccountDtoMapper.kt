package com.jervis.mapper

import com.jervis.domain.email.CreateEmailAccountRequest
import com.jervis.domain.email.EmailAccount
import com.jervis.domain.email.EmailValidationResult
import com.jervis.domain.email.UpdateEmailAccountRequest
import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.email.ValidateResponseDto
import org.bson.types.ObjectId

/**
 * Mappers for EmailAccount DTOs ↔ Domain objects.
 * Used in Controller layer at API boundary.
 */

// DTO → Domain (for CREATE requests)
fun CreateOrUpdateEmailAccountRequestDto.toCreateDomain(): CreateEmailAccountRequest =
    CreateEmailAccountRequest(
        clientId = ObjectId(this.clientId),
        projectId = this.projectId?.let { ObjectId(it) },
        provider = this.provider,
        displayName = this.displayName,
        description = this.description,
        email = this.email,
        username = this.username,
        password = this.password,
        serverHost = this.serverHost,
        serverPort = this.serverPort,
        useSsl = this.useSsl,
    )

// DTO → Domain (for UPDATE requests)
fun CreateOrUpdateEmailAccountRequestDto.toUpdateDomain(accountId: ObjectId): UpdateEmailAccountRequest =
    UpdateEmailAccountRequest(
        accountId = accountId,
        provider = this.provider,
        displayName = this.displayName,
        description = this.description,
        email = this.email,
        username = this.username,
        password = this.password,
        serverHost = this.serverHost,
        serverPort = this.serverPort,
        useSsl = this.useSsl,
    )

// Domain → DTO (for responses)
fun EmailAccount.toDto(): EmailAccountDto =
    EmailAccountDto(
        id = this.id.toString(),
        clientId = this.clientId.toString(),
        projectId = this.projectId?.toString(),
        provider = this.provider,
        displayName = this.displayName,
        description = this.description,
        email = this.email,
        username = this.username,
        password = this.password,
        serverHost = this.serverHost,
        serverPort = this.serverPort,
        useSsl = this.useSsl,
        hasPassword = this.password != null,
        isActive = this.isActive,
        lastPolledAt = this.lastPolledAt?.toString(),
    )

// Domain → DTO (for validation results)
fun EmailValidationResult.toDto(): ValidateResponseDto =
    ValidateResponseDto(
        ok = this.isValid,
        message = this.message,
    )
