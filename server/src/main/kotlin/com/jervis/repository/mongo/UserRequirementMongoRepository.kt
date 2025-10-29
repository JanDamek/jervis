package com.jervis.repository.mongo

import com.jervis.domain.requirement.RequirementStatus
import com.jervis.entity.UserRequirementDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface UserRequirementMongoRepository : CoroutineCrudRepository<UserRequirementDocument, ObjectId> {
    fun findByClientIdAndStatus(
        clientId: ObjectId,
        status: RequirementStatus,
    ): Flow<UserRequirementDocument>

    fun findByClientIdAndProjectIdAndStatus(
        clientId: ObjectId,
        projectId: ObjectId,
        status: RequirementStatus,
    ): Flow<UserRequirementDocument>
}
