package com.jervis.service

import com.jervis.dto.rag.RagSearchRequestDto
import com.jervis.dto.rag.RagSearchResponseDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IRagSearchService {
    suspend fun search(request: RagSearchRequestDto): RagSearchResponseDto
}
