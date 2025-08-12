package com.jervis.domain.dependency

/**
 * Types of dependencies between classes.
 */
enum class DependencyType {
    IMPORT,
    EXTENDS,
    IMPLEMENTS,
    USES,
}
