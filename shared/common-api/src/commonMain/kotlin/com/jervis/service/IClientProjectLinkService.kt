package com.jervis.service

import com.jervis.dto.ClientProjectLinkDto
import de.jensklingenberg.ktorfit.http.*

/**
 * Client-Project Link Service API
 */
interface IClientProjectLinkService {

    @GET("api/client-project-links/client/{clientId}")
    suspend fun listForClient(@Path("clientId") clientId: String): List<ClientProjectLinkDto>

    @GET("api/client-project-links/client/{clientId}/project/{projectId}")
    suspend fun get(
        @Path("clientId") clientId: String,
        @Path("projectId") projectId: String
    ): ClientProjectLinkDto?

    @POST("api/client-project-links/client/{clientId}/project/{projectId}")
    suspend fun upsert(
        @Path("clientId") clientId: String,
        @Path("projectId") projectId: String,
        @Query isDisabled: Boolean? = null,
        @Query anonymizationEnabled: Boolean? = null,
        @Query historical: Boolean? = null
    ): ClientProjectLinkDto

    @PUT("api/client-project-links/client/{clientId}/project/{projectId}/toggle-disabled")
    suspend fun toggleDisabled(
        @Path("clientId") clientId: String,
        @Path("projectId") projectId: String
    ): ClientProjectLinkDto

    @PUT("api/client-project-links/client/{clientId}/project/{projectId}/toggle-anonymization")
    suspend fun toggleAnonymization(
        @Path("clientId") clientId: String,
        @Path("projectId") projectId: String
    ): ClientProjectLinkDto

    @PUT("api/client-project-links/client/{clientId}/project/{projectId}/toggle-historical")
    suspend fun toggleHistorical(
        @Path("clientId") clientId: String,
        @Path("projectId") projectId: String
    ): ClientProjectLinkDto

    @DELETE("api/client-project-links/client/{clientId}/project/{projectId}")
    suspend fun delete(
        @Path("clientId") clientId: String,
        @Path("projectId") projectId: String
    )
}
