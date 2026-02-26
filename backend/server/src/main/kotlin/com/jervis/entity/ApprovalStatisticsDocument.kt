package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * EPIC 4-S5: Approval analytics persistence.
 *
 * Tracks approve/deny ratios per client per action for trust building.
 * When a client consistently approves an action type (>=10 approvals, 0 denials),
 * the system can suggest enabling auto-approve.
 */
@Document(collection = "approval_statistics")
@CompoundIndex(name = "idx_client_action", def = "{'clientId': 1, 'action': 1}", unique = true)
data class ApprovalStatisticsDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val clientId: String,
    @Indexed val action: String,
    var approvedCount: Int = 0,
    var deniedCount: Int = 0,
    var lastDecisionAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
)
