package com.jervis.repository

import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.email.ValidateResponseDto
import com.jervis.service.IEmailAccountService

/**
 * Repository for Email Account operations.
 * Wraps IEmailAccountService for use by UI.
 */
class EmailAccountRepository(
    private val service: IEmailAccountService,
) {
    suspend fun createEmailAccount(request: CreateOrUpdateEmailAccountRequestDto): EmailAccountDto =
        service.createEmailAccount(request)

    suspend fun updateEmailAccount(accountId: String, request: CreateOrUpdateEmailAccountRequestDto): EmailAccountDto =
        service.updateEmailAccount(accountId, request)

    suspend fun getEmailAccount(accountId: String): EmailAccountDto? =
        service.getEmailAccount(accountId)

    suspend fun listEmailAccounts(clientId: String? = null, projectId: String? = null): List<EmailAccountDto> =
        service.listEmailAccounts(clientId, projectId)

    suspend fun deleteEmailAccount(accountId: String) =
        service.deleteEmailAccount(accountId)

    suspend fun validateEmailAccount(accountId: String): ValidateResponseDto =
        service.validateEmailAccount(accountId)
}
