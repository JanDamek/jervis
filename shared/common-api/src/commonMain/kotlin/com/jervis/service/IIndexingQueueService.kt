package com.jervis.service

import com.jervis.dto.indexing.IndexingQueuePageDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IIndexingQueueService {
    /** Get items waiting for indexing (state = NEW), paginated with search. */
    suspend fun getPendingItems(page: Int, pageSize: Int, search: String): IndexingQueuePageDto

    /** Get items already sent to KB (state = INDEXED), paginated with search. */
    suspend fun getIndexedItems(page: Int, pageSize: Int, search: String): IndexingQueuePageDto
}
