package com.jervis.service

import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.email.ValidateResponse
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.DeleteExchange
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.annotation.PutExchange

@HttpExchange("/api/v1/email")
interface IEmailAccountService {
    @PostExchange("/accounts")
    suspend fun createEmailAccount(
        @RequestBody request: CreateOrUpdateEmailAccountRequestDto,
    ): EmailAccountDto

    @PutExchange("/accounts/{accountId}")
    suspend fun updateEmailAccount(
        @PathVariable accountId: String,
        @RequestBody request: CreateOrUpdateEmailAccountRequestDto,
    ): EmailAccountDto

    @GetExchange("/accounts/{accountId}")
    suspend fun getEmailAccount(
        @PathVariable accountId: String,
    ): EmailAccountDto?

    @GetExchange("/accounts")
    suspend fun listEmailAccounts(
        @RequestParam(required = false) clientId: String? = null,
        @RequestParam(required = false) projectId: String? = null,
    ): List<EmailAccountDto>

    @DeleteExchange("/accounts/{accountId}")
    suspend fun deleteEmailAccount(
        @PathVariable accountId: String,
    )

    @PostExchange("/accounts/{accountId}/validate")
    suspend fun validateEmailAccount(
        @PathVariable accountId: String,
    ): ValidateResponse
}
