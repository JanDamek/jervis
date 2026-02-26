package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * EPIC 10: MongoDB document for filtering rules.
 *
 * Rules control how incoming items (emails, JIRA tickets, git events, etc.)
 * are processed by the qualifier pipeline.
 */
@Document(collection = "filtering_rules")
data class FilteringRuleDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val scope: String = "CLIENT",
    @Indexed val sourceType: String,
    val conditionType: String,
    val conditionValue: String,
    @Indexed val action: String = "IGNORE",
    val description: String? = null,
    @Indexed val clientId: String? = null,
    val projectId: String? = null,
    @Indexed val enabled: Boolean = true,
    val createdBy: String = "user",
    @Indexed val createdAt: Instant = Instant.now(),
)
