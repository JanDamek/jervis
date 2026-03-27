package com.jervis.git.model.branch

/** Testing overview. */
data class TestingSummary(
    val testsTouched: Boolean,
    val addedTests: Int,
    val coverageHint: String?,
)
