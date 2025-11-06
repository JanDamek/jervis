package com.jervis.controller.api

import com.jervis.dto.confluence.ConfluenceAccountCreateDto
import com.jervis.dto.confluence.ConfluenceAccountDto
import com.jervis.dto.confluence.ConfluenceAccountUpdateDto
import com.jervis.dto.confluence.ConfluencePageDto
import com.jervis.entity.ConfluenceAccountDocument
import com.jervis.mapper.toDto
import com.jervis.repository.mongo.ConfluenceAccountMongoRepository
import com.jervis.repository.mongo.ConfluencePageMongoRepository
import com.jervis.service.confluence.ConfluencePollingScheduler
import com.jervis.service.confluence.state.ConfluencePageStateManager
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

private val logger = KotlinLogging.logger {}

@RestController
class ConfluenceRestController(
    private val accountRepository: ConfluenceAccountMongoRepository,
    private val pageRepository: ConfluencePageMongoRepository,
    private val stateManager: ConfluencePageStateManager,
    private val pollingScheduler: ConfluencePollingScheduler,
) : com.jervis.service.IConfluenceService {
    override suspend fun createAccount(request: ConfluenceAccountCreateDto): ConfluenceAccountDto {
        logger.info { "Creating Confluence account for client ${request.clientId}" }

        val account =
            ConfluenceAccountDocument(
                clientId = ObjectId(request.clientId),
                projectId = request.projectId?.let { ObjectId(it) },
                cloudId = request.cloudId,
                siteName = request.siteName,
                siteUrl = request.siteUrl,
                accessToken = request.accessToken,
                refreshToken = request.refreshToken,
                tokenExpiresAt = request.tokenExpiresAt?.let { Instant.parse(it) },
                spaceKeys = request.spaceKeys,
                isActive = true,
            )

        val saved = accountRepository.save(account)
        logger.info { "Created Confluence account ${saved.id} for ${saved.siteName}" }

        return saved.toDto()
    }

    override suspend fun updateAccount(
        accountId: String,
        request: ConfluenceAccountUpdateDto,
    ): ConfluenceAccountDto {
        val account =
            accountRepository.findById(ObjectId(accountId))
                ?: throw IllegalArgumentException("Account not found: $accountId")

        val updated =
            account.copy(
                spaceKeys = request.spaceKeys ?: account.spaceKeys,
                isActive = request.isActive ?: account.isActive,
                updatedAt = Instant.now(),
            )

        val saved = accountRepository.save(updated)
        logger.info { "Updated Confluence account ${saved.id}" }

        return saved.toDto()
    }

    override suspend fun getAccount(accountId: String): ConfluenceAccountDto? {
        val account = accountRepository.findById(ObjectId(accountId)) ?: return null
        return account.toDto()
    }

    override suspend fun listAccounts(
        clientId: String?,
        projectId: String?,
    ): List<ConfluenceAccountDto> =
        when {
            clientId != null -> accountRepository.findActiveByClientId(ObjectId(clientId))
            projectId != null -> accountRepository.findActiveByProjectId(ObjectId(projectId))
            else -> accountRepository.findAll().toList()
        }.map { it.toDto() }

    override suspend fun deleteAccount(accountId: String) {
        accountRepository.deleteById(ObjectId(accountId))
        logger.info { "Deleted Confluence account $accountId" }
    }

    override suspend fun triggerSync(accountId: String) {
        logger.info { "Manual sync triggered for Confluence account $accountId" }
        pollingScheduler.triggerManualPoll(accountId)
    }

    override suspend fun listPages(
        accountId: String,
        spaceKey: String?,
        state: String?,
    ): List<ConfluencePageDto> {
        val accountObjectId = ObjectId(accountId)

        val pages =
            when {
                spaceKey != null -> pageRepository.findByAccountIdAndSpaceKey(accountObjectId, spaceKey).toList()
                else -> emptyList()
            }

        return pages
            .filter { state == null || it.state.name == state }
            .map { it.toDto() }
    }

    override suspend fun getAccountStats(accountId: String): ConfluenceAccountDto {
        val account =
            accountRepository.findById(ObjectId(accountId))
                ?: throw IllegalArgumentException("Account not found: $accountId")

        val stats = stateManager.getStats(account.id)

        return account.toDto(stats)
    }
}
