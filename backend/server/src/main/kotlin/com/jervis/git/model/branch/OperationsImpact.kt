package com.jervis.git.model.branch

/** Operational impact hints. */
data class OperationsImpact(
    val requiresMigration: Boolean,
    val migrationFiles: List<String>,
    val configTouched: Boolean,
)
