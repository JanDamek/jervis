package com.jervis.repository

import com.jervis.dto.indexing.IndexingOverviewDto
import com.jervis.dto.indexing.IndexingToolDetailDto
import com.jervis.service.IIndexingStatusService

class IndexingStatusRepository(
    private val service: IIndexingStatusService,
) {
    suspend fun overview(): IndexingOverviewDto = service.getOverview()

    suspend fun detail(toolKey: String): IndexingToolDetailDto = service.getToolDetail(toolKey)

    suspend fun runJiraNow() {
        service.runJiraNow()
    }

    suspend fun runEmailNow() {
        service.runEmailNow()
    }

    suspend fun runGitNow() {
        service.runGitNow()
    }

    suspend fun runConfluenceNow() {
        service.runConfluenceNow()
    }
}
