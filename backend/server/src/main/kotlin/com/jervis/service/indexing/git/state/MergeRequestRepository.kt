package com.jervis.service.indexing.git.state

import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface MergeRequestRepository : CoroutineCrudRepository<MergeRequestDocument, ObjectId> {
    suspend fun findByProjectIdAndMergeRequestIdAndProvider(
        projectId: ObjectId,
        mergeRequestId: String,
        provider: String,
    ): MergeRequestDocument?

    fun findByStateOrderByCreatedAtAsc(state: MergeRequestState): Flow<MergeRequestDocument>
}
