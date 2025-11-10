package com.jervis.domain.git.branch

/** Testing overview. */
data class TestingSummary(
    val testsTouched: Boolean,
    val addedTests: Int,
    val coverageHint: String?,
)
