package com.jervis.service

import com.jervis.dto.rag.RagSearchRequestDto
import com.jervis.dto.rag.RagSearchResponseDto
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.POST

interface IRagSearchService {
    @POST("api/rag/search")
    suspend fun search(
        @Body request: RagSearchRequestDto,
    ): RagSearchResponseDto
}
