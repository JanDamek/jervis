package com.jervis.common.client

import com.jervis.common.dto.wiki.WikiPageRequest
import com.jervis.common.dto.wiki.WikiPageResponse
import com.jervis.common.dto.wiki.WikiSearchRequest
import com.jervis.common.dto.wiki.WikiSearchResponse
import com.jervis.common.dto.wiki.WikiUserDto
import com.jervis.common.dto.wiki.WikiUserRequest
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IWikiClient {
    suspend fun getUser(request: WikiUserRequest): WikiUserDto
    suspend fun searchPages(request: WikiSearchRequest): WikiSearchResponse
    suspend fun getPage(request: WikiPageRequest): WikiPageResponse
}
