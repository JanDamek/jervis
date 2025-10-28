package com.jervis.repository.mongo

import com.jervis.domain.requirement.RequirementStatus
import com.jervis.entity.UserRequirementDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.stereotype.Repository

@Repository
interface UserRequirementMongoRepository : ReactiveMongoRepository<UserRequirementDocument, ObjectId> {
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
