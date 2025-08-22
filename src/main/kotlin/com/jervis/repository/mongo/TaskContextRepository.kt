package com.jervis.repository.mongo

import com.jervis.domain.agent.TaskStatus
import com.jervis.entity.mongo.TaskContextDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface TaskContextMongoRepository : CoroutineCrudRepository<TaskContextDocument, String> {
    suspend fun findByContextId(contextId: ObjectId): TaskContextDocument?

    fun findByClientIdAndProjectIdAndStatusIn(
        clientId: ObjectId,
        projectId: ObjectId,
        statuses: List<TaskStatus>
    ): Flow<TaskContextDocument>
}