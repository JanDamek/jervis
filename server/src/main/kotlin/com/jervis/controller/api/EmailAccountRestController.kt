package com.jervis.controller.api

import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.email.ValidateResponse
import com.jervis.service.IEmailAccountService
import com.jervis.service.email.EmailAccountService
import mu.KotlinLogging
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
class EmailAccountRestController(
    private val emailAccountService: EmailAccountService,
) : IEmailAccountService {
    override suspend fun createEmailAccount(
        @RequestBody request: CreateOrUpdateEmailAccountRequestDto,
    ): EmailAccountDto {
        logger.info { "Creating email account for client ${request.clientId}" }
        val account = emailAccountService.createEmailAccount(request)

        val validation = emailAccountService.validateEmailAccount(account.id ?: "")
        if (!validation.ok) {
            logger.warn { "Created account ${account.id} failed validation: ${validation.message}" }
        }

        return account
    }

    override suspend fun updateEmailAccount(
        @PathVariable accountId: String,
        @RequestBody request: CreateOrUpdateEmailAccountRequestDto,
    ): EmailAccountDto {
        logger.info { "Updating email account $accountId" }
        val account = emailAccountService.updateEmailAccount(accountId, request)

        val validation = emailAccountService.validateEmailAccount(accountId)
        if (!validation.ok) {
            logger.warn { "Updated account $accountId failed validation: ${validation.message}" }
        }

        return account
    }

    override suspend fun getEmailAccount(
        @PathVariable accountId: String,
    ): EmailAccountDto? = emailAccountService.getEmailAccount(accountId)

    override suspend fun listEmailAccounts(
        @RequestParam(required = false) clientId: String?,
        @RequestParam(required = false) projectId: String?,
    ): List<EmailAccountDto> = emailAccountService.listEmailAccounts(clientId, projectId)

    override suspend fun deleteEmailAccount(
        @PathVariable accountId: String,
    ) {
        logger.info { "Deleting email account $accountId" }
        emailAccountService.deleteEmailAccount(accountId)
    }

    override suspend fun validateEmailAccount(
        @PathVariable accountId: String,
    ): ValidateResponse = emailAccountService.validateEmailAccount(accountId)
}
