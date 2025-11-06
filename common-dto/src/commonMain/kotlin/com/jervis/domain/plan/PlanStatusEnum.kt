package com.jervis.domain.plan

import kotlinx.serialization.Serializable

@Serializable
enum class PlanStatusEnum {
    CREATED,
    RUNNING,
    COMPLETED,
    FINALIZED,
    FAILED,
}
