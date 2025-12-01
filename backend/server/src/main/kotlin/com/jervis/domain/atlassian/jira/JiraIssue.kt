package com.jervis.domain.atlassian.jira

import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * Jira issue domain model.
 */
@Serializable
data class JiraIssue(
    val id: String,
    val key: String,
    val summary: String,
    @Contextual val updated: Instant,
    val issueType: String,
    val status: String,
)
