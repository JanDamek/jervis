package com.jervis.service.notification.domain

import com.jervis.domain.plan.StepStatusEnum
import org.bson.types.ObjectId
import java.time.Instant

data class StepCompletionEvent(
    val contextId: ObjectId,
    val planId: ObjectId,
    val stepId: ObjectId,
    val stepName: String,
    val stepStatus: StepStatusEnum,
    val timestamp: Instant,
)
