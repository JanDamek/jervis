package com.jervis.common.client

import com.jervis.common.dto.TikaProcessRequest
import com.jervis.common.dto.TikaProcessResult
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.POST

interface ITikaClient {
    @POST("api/tika/process")
    suspend fun process(
        @Body request: TikaProcessRequest,
    ): TikaProcessResult
}
