package com.jervis.service

import com.jervis.dto.rag.RagSearchRequestDto
import com.jervis.dto.rag.RagSearchResponseDto
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange("/api/rag")
interface IRagSearchService {
    @PostExchange("/search")
    suspend fun search(
        @RequestBody request: RagSearchRequestDto,
    ): RagSearchResponseDto
}
