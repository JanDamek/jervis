package com.jervis.service

import com.jervis.dto.confluence.ConfluenceAccountCreateDto
import com.jervis.dto.confluence.ConfluenceAccountDto
import com.jervis.dto.confluence.ConfluenceAccountUpdateDto
import com.jervis.dto.confluence.ConfluencePageDto
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.DeleteExchange
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PatchExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange("/api/v1/confluence")
interface IConfluenceService {
    @PostExchange("/accounts")
    suspend fun createAccount(
        @RequestBody request: ConfluenceAccountCreateDto,
    ): ConfluenceAccountDto

    @PatchExchange("/accounts/{accountId}")
    suspend fun updateAccount(
        @PathVariable accountId: String,
        @RequestBody request: ConfluenceAccountUpdateDto,
    ): ConfluenceAccountDto

    @GetExchange("/accounts/{accountId}")
    suspend fun getAccount(
        @PathVariable accountId: String,
    ): ConfluenceAccountDto?

    @GetExchange("/accounts")
    suspend fun listAccounts(
        @RequestParam(required = false) clientId: String? = null,
        @RequestParam(required = false) projectId: String? = null,
    ): List<ConfluenceAccountDto>

    @DeleteExchange("/accounts/{accountId}")
    suspend fun deleteAccount(
        @PathVariable accountId: String,
    )

    @PostExchange("/accounts/{accountId}/sync")
    suspend fun triggerSync(
        @PathVariable accountId: String,
    )

    @GetExchange("/accounts/{accountId}/pages")
    suspend fun listPages(
        @PathVariable accountId: String,
        @RequestParam(required = false) spaceKey: String? = null,
        @RequestParam(required = false) state: String? = null,
    ): List<ConfluencePageDto>

    @GetExchange("/accounts/{accountId}/stats")
    suspend fun getAccountStats(
        @PathVariable accountId: String,
    ): ConfluenceAccountDto
}
