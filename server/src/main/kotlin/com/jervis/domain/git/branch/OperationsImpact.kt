package com.jervis.domain.git.branch

/** Operational impact hints. */
data class OperationsImpact(
    val requiresMigration: Boolean,
    val migrationFiles: List<String>,
    val configTouched: Boolean,
)
