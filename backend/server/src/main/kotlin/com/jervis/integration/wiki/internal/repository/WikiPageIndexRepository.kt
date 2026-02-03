package com.jervis.integration.wiki.internal.repository

import com.jervis.domain.PollingStatusEnum
import com.jervis.integration.wiki.internal.entity.WikiPageIndexDocument
import com.jervis.types.ConnectionId
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface WikiPageIndexRepository : CoroutineCrudRepository<WikiPageIndexDocument, ObjectId> {
    suspend fun existsByConnectionDocumentIdAndPageIdAndVersionNumber(
        connectionId: ConnectionId,
        pageId: String,
        versionNumber: Int?,
    ): Boolean

    fun findAllByStatusOrderByWikiUpdatedAtDesc(status: PollingStatusEnum = PollingStatusEnum.NEW): Flow<WikiPageIndexDocument>

    suspend fun findByConnectionDocumentIdAndPageId(
        connectionDocumentId: ConnectionId,
        pageId: String,
    ): WikiPageIndexDocument?
}
