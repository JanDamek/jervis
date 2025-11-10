package com.jervis.controller.api

import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.email.ValidateResponseDto
import com.jervis.mapper.toCreateDomain
import com.jervis.mapper.toDto
import com.jervis.mapper.toUpdateDomain
import com.jervis.service.IEmailAccountService
import com.jervis.service.email.EmailAccountService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/email")
class EmailAccountRestController(
    private val emailAccountService: EmailAccountService,
) : IEmailAccountService {
    @PostMapping("/accounts")
    override suspend fun createEmailAccount(
        @RequestBody request: CreateOrUpdateEmailAccountRequestDto,
    ): EmailAccountDto {
        logger.info { "Creating email account for client ${request.clientId}" }

        // DTO → Domain conversion happens in controller
        val domainRequest = request.toCreateDomain()
        val account = emailAccountService.createEmailAccount(domainRequest)

        // Validate and log warning if invalid
        val validation = emailAccountService.validateEmailAccount(account.id)
        if (!validation.isValid) {
            logger.warn { "Created account ${account.id} failed validation: ${validation.message}" }
        }

        // Domain → DTO conversion for response
        return account.toDto()
    }

    @PutMapping("/accounts/{accountId}")
    override suspend fun updateEmailAccount(
        @PathVariable accountId: String,
        @RequestBody request: CreateOrUpdateEmailAccountRequestDto,
    ): EmailAccountDto {
        logger.info { "Updating email account $accountId" }

        // DTO → Domain conversion happens in controller
        val objectId = ObjectId(accountId)
        val domainRequest = request.toUpdateDomain(objectId)
        val account = emailAccountService.updateEmailAccount(domainRequest)

        // Validate and log warning if invalid
        val validation = emailAccountService.validateEmailAccount(objectId)
        if (!validation.isValid) {
            logger.warn { "Updated account $accountId failed validation: ${validation.message}" }
        }

        // Domain → DTO conversion for response
        return account.toDto()
    }

    @GetMapping("/accounts/{accountId}")
    override suspend fun getEmailAccount(
        @PathVariable accountId: String,
    ): EmailAccountDto? {
        val objectId = ObjectId(accountId)
        // Service returns Domain, map to DTO
        return emailAccountService.getEmailAccount(objectId)?.toDto()
    }

    @GetMapping("/accounts")
    override suspend fun listEmailAccounts(
        @RequestParam(required = false) clientId: String?,
        @RequestParam(required = false) projectId: String?,
    ): List<EmailAccountDto> {
        val clientObjectId = clientId?.let { ObjectId(it) }
        val projectObjectId = projectId?.let { ObjectId(it) }

        // Service returns Flow<Domain>, map to List<DTO>
        return emailAccountService
            .listEmailAccounts(clientObjectId, projectObjectId)
            .map { it.toDto() }
            .toList()
    }

    @DeleteMapping("/accounts/{accountId}")
    override suspend fun deleteEmailAccount(
        @PathVariable accountId: String,
    ) {
        logger.info { "Deleting email account $accountId" }
        val objectId = ObjectId(accountId)
        emailAccountService.deleteEmailAccount(objectId)
    }

    @PostMapping("/accounts/{accountId}/validate")
    override suspend fun validateEmailAccount(
        @PathVariable accountId: String,
    ): ValidateResponseDto {
        val objectId = ObjectId(accountId)
        // Service returns Domain, map to DTO
        val result = emailAccountService.validateEmailAccount(objectId)
        return result.toDto()
    }
}
