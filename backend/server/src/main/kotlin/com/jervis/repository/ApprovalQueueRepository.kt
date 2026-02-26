package com.jervis.repository

import com.jervis.entity.ApprovalQueueDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ApprovalQueueRepository : CoroutineCrudRepository<ApprovalQueueDocument, ObjectId> {

    /** Find pending approvals for a client (for UI display). */
    fun findByClientIdAndStatusOrderByCreatedAtDesc(
        clientId: String,
        status: String,
    ): Flow<ApprovalQueueDocument>

    /** Find pending approval by taskId (for approval response handling). */
    suspend fun findByTaskId(taskId: String): ApprovalQueueDocument?

    /** Count pending approvals for a client. */
    suspend fun countByClientIdAndStatus(clientId: String, status: String): Long

    /** Find all pending approvals (for admin display). */
    fun findByStatusOrderByCreatedAtDesc(status: String): Flow<ApprovalQueueDocument>
}
