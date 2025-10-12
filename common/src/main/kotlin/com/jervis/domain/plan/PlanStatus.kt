package com.jervis.domain.plan

import kotlinx.serialization.Serializable

@Serializable
enum class PlanStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FINALIZED,
    FAILED,
}
