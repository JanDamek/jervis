package com.jervis.task

import com.jervis.task.ApprovalStatisticsDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ApprovalStatisticsRepository : CoroutineCrudRepository<ApprovalStatisticsDocument, ObjectId> {

    /** Find statistics for a client (all action types). */
    fun findByClientId(clientId: String): Flow<ApprovalStatisticsDocument>

    /** Find specific client+action statistics. */
    suspend fun findByClientIdAndAction(clientId: String, action: String): ApprovalStatisticsDocument?
}
