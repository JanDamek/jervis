package com.jervis.repository.mongo

import com.jervis.domain.plan.StepStatus
import com.jervis.entity.mongo.PlanStep
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PlanStepMongoRepository : CoroutineCrudRepository<PlanStep, String> {
    fun findByPlanId(planId: ObjectId): Flow<PlanStep>

    suspend fun findFirstByPlanIdAndStatusOrderByOrder(
        planId: ObjectId,
        status: StepStatus,
    ): PlanStep?
}
