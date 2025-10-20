package com.jervis.domain.background

data class CoverageWeights(
    val docs: Double = 0.3,
    val tasks: Double = 0.2,
    val code: Double = 0.4,
    val meetings: Double = 0.1,
)
