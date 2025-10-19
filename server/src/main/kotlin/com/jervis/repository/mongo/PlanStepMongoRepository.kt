package com.jervis.repository.mongo

import com.jervis.domain.plan.StepStatusEnum
import com.jervis.entity.mongo.PlanStepDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PlanStepMongoRepository : CoroutineCrudRepository<PlanStepDocument, String> {
    fun findByPlanId(planId: ObjectId): Flow<PlanStepDocument>

    suspend fun findFirstByPlanIdAndStatusOrderByOrder(
        planId: ObjectId,
        status: StepStatusEnum,
    ): PlanStepDocument?
}
