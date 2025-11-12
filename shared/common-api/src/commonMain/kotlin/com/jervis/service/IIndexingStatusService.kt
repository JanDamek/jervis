package com.jervis.service

import com.jervis.dto.indexing.IndexingOverviewDto
import com.jervis.dto.indexing.IndexingToolDetailDto
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Path

/**
 * Indexing Status API for UI
 */
interface IIndexingStatusService {

    @GET("api/indexing/status")
    suspend fun getOverview(): IndexingOverviewDto

    @GET("api/indexing/status/{toolKey}")
    suspend fun getToolDetail(@Path("toolKey") toolKey: String): IndexingToolDetailDto
}
