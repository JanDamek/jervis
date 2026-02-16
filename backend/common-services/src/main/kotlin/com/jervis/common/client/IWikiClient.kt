package com.jervis.common.client

import com.jervis.common.dto.wiki.WikiCreatePageRpcRequest
import com.jervis.common.dto.wiki.WikiPageRequest
import com.jervis.common.dto.wiki.WikiPageResponse
import com.jervis.common.dto.wiki.WikiSearchRequest
import com.jervis.common.dto.wiki.WikiSearchResponse
import com.jervis.common.dto.wiki.WikiSpacesRequest
import com.jervis.common.dto.wiki.WikiSpacesResponse
import com.jervis.common.dto.wiki.WikiUpdatePageRpcRequest
import com.jervis.common.dto.wiki.WikiUserDto
import com.jervis.common.dto.wiki.WikiUserRequest
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IWikiClient {
    // Read operations
    suspend fun getUser(request: WikiUserRequest): WikiUserDto
    suspend fun searchPages(request: WikiSearchRequest): WikiSearchResponse
    suspend fun getPage(request: WikiPageRequest): WikiPageResponse
    suspend fun listSpaces(request: WikiSpacesRequest): WikiSpacesResponse

    // Write operations
    suspend fun createPage(request: WikiCreatePageRpcRequest): WikiPageResponse
    suspend fun updatePage(request: WikiUpdatePageRpcRequest): WikiPageResponse
}
