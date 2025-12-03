package com.jervis.service

import com.jervis.dto.error.ErrorLogDto
import com.jervis.dto.error.ErrorLogCreateRequestDto
import de.jensklingenberg.ktorfit.http.DELETE
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Path
import de.jensklingenberg.ktorfit.http.Query

interface IErrorLogService {
    @de.jensklingenberg.ktorfit.http.POST("api/error-logs")
    suspend fun add(
        @de.jensklingenberg.ktorfit.http.Body request: ErrorLogCreateRequestDto,
    ): ErrorLogDto
    @GET("api/error-logs")
    suspend fun list(
        @Query("clientId") clientId: String,
        @Query("limit") limit: Int = 200,
    ): List<ErrorLogDto>

    @GET("api/error-logs/all")
    suspend fun listAll(
        @Query("limit") limit: Int = 200,
    ): List<ErrorLogDto>

    @GET("api/error-logs/{id}")
    suspend fun get(
        @Path("id") id: String,
    ): ErrorLogDto

    @DELETE("api/error-logs/{id}")
    suspend fun delete(
        @Path("id") id: String,
    )

    @DELETE("api/error-logs")
    suspend fun deleteAll(
        @Query("clientId") clientId: String,
    )
}
