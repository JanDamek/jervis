package com.jervis.service

import com.jervis.dto.ClientDto
import de.jensklingenberg.ktorfit.http.*

/**
 * Client Service API
 */
interface IClientService {

    @GET("api/clients")
    suspend fun getAllClients(): List<ClientDto>

    @GET("api/clients/{id}")
    suspend fun getClientById(@Path("id") id: String): ClientDto?

    @POST("api/clients")
    suspend fun createClient(@Body client: ClientDto): ClientDto

    @PUT("api/clients/{id}")
    suspend fun updateClient(
        @Path("id") id: String,
        @Body client: ClientDto
    ): ClientDto

    @DELETE("api/clients/{id}")
    suspend fun deleteClient(@Path("id") id: String)

    @PATCH("api/clients/{id}/last-selected-project")
    suspend fun updateLastSelectedProject(
        @Path("id") id: String,
        @Query projectId: String?
    ): ClientDto
}
