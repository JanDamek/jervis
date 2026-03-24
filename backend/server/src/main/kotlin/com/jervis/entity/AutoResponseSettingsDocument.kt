package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * AutoResponseSettingsDocument — per-channel/project/client auto-response configuration.
 *
 * Controls whether the agent should auto-respond to incoming messages on a given channel,
 * or require human review before sending. Default is OFF (no auto-response).
 *
 * Cascading resolution: channel → project → client → default(OFF).
 */
@Document(collection = "auto_response_settings")
data class AutoResponseSettingsDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val clientId: String? = null,
    val projectId: String? = null,
    val channelType: String? = null,  // teams, slack, email, portal
    val channelId: String? = null,
    val enabled: Boolean = false,  // DEFAULT OFF
    val neverAutoResponse: Boolean = false,  // true = block forever
    val responseRules: List<ResponseRule> = emptyList(),
    val learningEnabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

data class ResponseRule(
    val trigger: String,  // job_offer, direct_question, newsletter, etc.
    val action: String,   // accept_and_notify, draft_and_wait, ignore, auto_respond
)
