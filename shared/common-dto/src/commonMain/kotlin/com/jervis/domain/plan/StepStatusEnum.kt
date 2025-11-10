package com.jervis.domain.plan

import kotlinx.serialization.Serializable

@Serializable
enum class StepStatusEnum {
    PENDING,
    DONE,
    FAILED,
}
