package com.jervis.domain.background

import org.bson.types.ObjectId
import java.time.Instant

data class CoverageSnapshot(
    val id: ObjectId = ObjectId(),
    val projectKey: String,
    val docs: Double,
    val tasks: Double,
    val code: Double,
    val meetings: Double,
    val overall: Double,
    val weights: CoverageWeights = CoverageWeights(),
    val createdAt: Instant = Instant.now(),
)
