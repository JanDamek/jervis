package com.jervis.service

import com.jervis.dto.confluence.ConfluenceAccountCreateDto
import com.jervis.dto.confluence.ConfluenceAccountDto
import com.jervis.dto.confluence.ConfluenceAccountUpdateDto
import com.jervis.dto.confluence.ConfluencePageDto
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.DELETE
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.PATCH
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.Path
import de.jensklingenberg.ktorfit.http.Query

interface IConfluenceService {
    @POST("api/v1/confluence/accounts")
    suspend fun createAccount(
        @Body request: ConfluenceAccountCreateDto,
    ): ConfluenceAccountDto

    @PATCH("api/v1/confluence/accounts/{accountId}")
    suspend fun updateAccount(
        @Path accountId: String,
        @Body request: ConfluenceAccountUpdateDto,
    ): ConfluenceAccountDto

    @GET("api/v1/confluence/accounts/{accountId}")
    suspend fun getAccount(
        @Path accountId: String,
    ): ConfluenceAccountDto?

    @GET("api/v1/confluence/accounts")
    suspend fun listAccounts(
        @Query clientId: String? = null,
        @Query projectId: String? = null,
    ): List<ConfluenceAccountDto>

    @DELETE("api/v1/confluence/accounts/{accountId}")
    suspend fun deleteAccount(
        @Path accountId: String,
    )

    @POST("api/v1/confluence/accounts/{accountId}/sync")
    suspend fun triggerSync(
        @Path accountId: String,
    )

    @GET("api/v1/confluence/accounts/{accountId}/pages")
    suspend fun listPages(
        @Path accountId: String,
        @Query spaceKey: String? = null,
        @Query state: String? = null,
    ): List<ConfluencePageDto>

    @GET("api/v1/confluence/accounts/{accountId}/stats")
    suspend fun getAccountStats(
        @Path accountId: String,
    ): ConfluenceAccountDto

    @POST("api/v1/confluence/accounts/{accountId}/test-connection")
    suspend fun testConnection(
        @Path accountId: String,
    ): ConfluenceAccountDto
}
