package com.jervis.service.notification.domain

import com.jervis.domain.plan.PlanStatusEnum
import org.bson.types.ObjectId
import java.time.Instant

data class PlanStatusChangeEvent(
    val contextId: ObjectId,
    val planId: ObjectId,
    val planStatusEnum: PlanStatusEnum,
    val timestamp: Instant,
)
