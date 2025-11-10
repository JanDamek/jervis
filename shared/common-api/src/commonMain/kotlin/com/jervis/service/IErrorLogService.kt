package com.jervis.service

import com.jervis.dto.error.ErrorLogDto
import de.jensklingenberg.ktorfit.http.DELETE
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Path
import de.jensklingenberg.ktorfit.http.Query

interface IErrorLogService {
    @GET("api/error-logs")
    suspend fun list(
        @Query("clientId") clientId: String,
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
