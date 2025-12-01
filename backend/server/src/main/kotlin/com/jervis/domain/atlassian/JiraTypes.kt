package com.jervis.domain.atlassian

import kotlinx.serialization.Serializable

/**
 * Simple value classes for Jira identifiers.
 * No sealed classes or complex hierarchy - just type-safe wrappers.
 */

@Serializable
@JvmInline
value class JiraAccountId(
    val value: String,
)

@Serializable
@JvmInline
value class JiraBoardId(
    val value: Int,
)

@Serializable
@JvmInline
value class JiraProjectKey(
    val value: String,
)

@Serializable
@JvmInline
value class JiraTenant(
    val value: String,
)

@Serializable
@JvmInline
value class JiraUserId(
    val value: String,
)
