package com.jervis.domain.qualifier

import com.jervis.dto.PendingTaskTypeEnum
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Qualifier rule for pre-filtering tasks before GPU analysis.
 * Agent creates these rules when it identifies patterns.
 *
 * Example: "Discard all Jira resolved notifications"
 */
@Document(collection = "qualifierRules")
data class QualifierRule(
    @Id val id: ObjectId = ObjectId(),
    val qualifierType: PendingTaskTypeEnum,
    val ruleText: String,
)
