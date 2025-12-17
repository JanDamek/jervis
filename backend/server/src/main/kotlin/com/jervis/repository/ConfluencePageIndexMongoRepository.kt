package com.jervis.repository

import com.jervis.domain.PollingStatusEnum
import com.jervis.entity.confluence.ConfluencePageIndexDocument
import com.jervis.types.ConnectionId
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ConfluencePageIndexMongoRepository : CoroutineCrudRepository<ConfluencePageIndexDocument, ObjectId> {
    suspend fun existsByConnectionDocumentIdAndPageIdAndVersionNumber(
        connectionId: ConnectionId,
        pageId: String,
        versionNumber: Int?,
    ): Boolean

    fun findAllByStatusOrderByConfluenceUpdatedAtDesc(status: PollingStatusEnum = PollingStatusEnum.NEW): Flow<ConfluencePageIndexDocument>
}
