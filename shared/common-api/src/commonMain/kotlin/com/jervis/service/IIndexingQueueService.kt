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

    /** Get dashboard with hierarchical connection→capability→client groups + full pipeline view. */
    suspend fun getIndexingDashboard(
        search: String,
        kbPage: Int,
        kbPageSize: Int,
        clientFilter: String = "",
        projectFilter: String = "",
    ): IndexingDashboardDto

    /** Trigger immediate polling for a specific connection+capability. */
    suspend fun triggerIndexNow(connectionId: String, capability: String): Boolean

    /** Reorder a task in the KB qualification queue (drag & drop). */
    suspend fun reorderKbQueueItem(taskId: String, newPosition: Int): Boolean

    /** Move a task to the front of the KB qualification queue. */
    suspend fun prioritizeKbQueueItem(taskId: String): Boolean

    /** Process a specific KB queue item immediately (bypass queue). Only one item can be processed at a time. */
    suspend fun processKbItemNow(taskId: String): Boolean
}
