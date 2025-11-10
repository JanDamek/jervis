package com.jervis.service

import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.email.ValidateResponseDto
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.DELETE
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.PUT
import de.jensklingenberg.ktorfit.http.Path
import de.jensklingenberg.ktorfit.http.Query

interface IEmailAccountService {
    @POST("api/v1/email/accounts")
    suspend fun createEmailAccount(
        @Body request: CreateOrUpdateEmailAccountRequestDto,
    ): EmailAccountDto

    @PUT("api/v1/email/accounts/{accountId}")
    suspend fun updateEmailAccount(
        @Path accountId: String,
        @Body request: CreateOrUpdateEmailAccountRequestDto,
    ): EmailAccountDto

    @GET("api/v1/email/accounts/{accountId}")
    suspend fun getEmailAccount(
        @Path accountId: String,
    ): EmailAccountDto?

    @GET("api/v1/email/accounts")
    suspend fun listEmailAccounts(
        @Query clientId: String? = null,
        @Query projectId: String? = null,
    ): List<EmailAccountDto>

    @DELETE("api/v1/email/accounts/{accountId}")
    suspend fun deleteEmailAccount(
        @Path accountId: String,
    )

    @POST("api/v1/email/accounts/{accountId}/validate")
    suspend fun validateEmailAccount(
        @Path accountId: String,
    ): ValidateResponseDto
}
