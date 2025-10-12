package com.jervis.domain.plan

import kotlinx.serialization.Serializable

@Serializable
enum class StepStatus {
    PENDING,
    DONE,
    FAILED,
}
