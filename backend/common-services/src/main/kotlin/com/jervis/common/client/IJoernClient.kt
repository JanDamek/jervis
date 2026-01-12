package com.jervis.common.client

import com.jervis.common.dto.JoernQueryDto
import com.jervis.common.dto.JoernResultDto
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.POST

interface IJoernClient {
    @POST("api/joern/query")
    suspend fun run(
        @Body request: JoernQueryDto,
    ): JoernResultDto
}
