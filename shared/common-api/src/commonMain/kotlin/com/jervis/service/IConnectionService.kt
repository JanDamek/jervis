package com.jervis.service

import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import de.jensklingenberg.ktorfit.http.*

/**
 * Connection Service API
 * Manages all connection types (HTTP, IMAP, POP3, SMTP, OAuth2)
 */
interface IConnectionService {

    @GET("api/connections")
    suspend fun getAllConnections(): List<ConnectionResponseDto>

    @GET("api/connections/{id}")
    suspend fun getConnectionById(@Path("id") id: String): ConnectionResponseDto?

    @POST("api/connections")
    suspend fun createConnection(@Body request: ConnectionCreateRequestDto): ConnectionResponseDto

    @PUT("api/connections/{id}")
    suspend fun updateConnection(
        @Path("id") id: String,
        @Body request: ConnectionUpdateRequestDto
    ): ConnectionResponseDto

    @DELETE("api/connections/{id}")
    suspend fun deleteConnection(@Path("id") id: String)

    @POST("api/connections/{id}/test")
    suspend fun testConnection(@Path("id") id: String): ConnectionTestResultDto
}
