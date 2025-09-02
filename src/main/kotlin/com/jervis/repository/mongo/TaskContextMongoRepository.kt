package com.jervis.repository.mongo

import com.jervis.entity.mongo.TaskContextDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface TaskContextMongoRepository : CoroutineCrudRepository<TaskContextDocument, ObjectId> {
    fun findByClientId(clientId: ObjectId): Flow<TaskContextDocument>

    fun findByClientIdAndProjectId(
        clientId: ObjectId,
        projectId: ObjectId,
    ): Flow<TaskContextDocument>
}
