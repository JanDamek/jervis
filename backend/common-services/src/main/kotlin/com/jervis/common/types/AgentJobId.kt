package com.jervis.common.types

import org.bson.types.ObjectId

/**
 * Identifier of a record in the `agent_job_records` collection.
 *
 * Every Claude CLI K8s Job the orchestrator dispatches on behalf of the
 * Jervis chat session — coding, analysis, research, scheduled audits,
 * future meeting substitutes — is tracked through exactly one
 * AgentJobRecord. This id is its primary key.
 */
@JvmInline
value class AgentJobId(
    val value: ObjectId,
) {
    override fun toString(): String = value.toHexString()

    companion object {
        fun fromString(hex: String): AgentJobId = AgentJobId(ObjectId(hex))

        fun generate(): AgentJobId = AgentJobId(ObjectId())
    }
}
