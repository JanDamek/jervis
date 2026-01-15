package com.jervis.service

import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.email.ValidateResponseDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IEmailAccountService {
    suspend fun createEmailAccount(request: CreateOrUpdateEmailAccountRequestDto): EmailAccountDto

    suspend fun updateEmailAccount(
        accountId: String,
        request: CreateOrUpdateEmailAccountRequestDto,
    ): EmailAccountDto

    suspend fun getEmailAccount(accountId: String): EmailAccountDto?

    suspend fun listEmailAccounts(
        clientId: String? = null,
        projectId: String? = null,
    ): List<EmailAccountDto>

    suspend fun deleteEmailAccount(accountId: String)

    suspend fun validateEmailAccount(accountId: String): ValidateResponseDto
}
