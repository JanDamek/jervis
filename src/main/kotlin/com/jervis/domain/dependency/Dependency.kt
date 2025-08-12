package com.jervis.domain.dependency

/**
 * Represents a dependency between two classes.
 */
data class Dependency(
    val sourceClass: String,
    val targetClass: String,
    val type: DependencyType,
    val sourceFile: String,
)
