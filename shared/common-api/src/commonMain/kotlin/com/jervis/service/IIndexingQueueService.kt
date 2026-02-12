package com.jervis.service

import com.jervis.dto.indexing.IndexingDashboardDto
import com.jervis.dto.indexing.IndexingQueuePageDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IIndexingQueueService {
    /** Get items waiting for indexing (state = NEW), paginated with search. */
    suspend fun getPendingItems(page: Int, pageSize: Int, search: String): IndexingQueuePageDto

    /** Get items already sent to KB (state = INDEXED), paginated with search. */
    suspend fun getIndexedItems(page: Int, pageSize: Int, search: String): IndexingQueuePageDto

    /** Get dashboard with items grouped by connection + KB queue. */
    suspend fun getIndexingDashboard(search: String, kbPageSize: Int): IndexingDashboardDto
}
