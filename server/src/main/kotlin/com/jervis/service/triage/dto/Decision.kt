package com.jervis.service.triage.dto

import kotlinx.serialization.Serializable

@Serializable
data class Decision(
    val schemaVersion: String = "1.0",
    val classification: Classification,
    val routing: Routing,
    val rag: Rag,
    val action: Action,
    val indexing: Indexing,
) {
    @Serializable
    data class Classification(
        val isActionable: Boolean,
        val intent: String,
        val entities: List<String> = emptyList(),
        val sensitivity: String = "LOW",
        val confidence: Double? = null,
    )

    @Serializable
    data class Routing(
        val clientId: String? = null,
        val projectId: String? = null,
        val threadKey: String? = null,
        val assignees: List<String> = emptyList(),
        val dedupe: Dedupe = Dedupe(),
    ) {
        @Serializable
        data class Dedupe(
            val isDuplicate: Boolean = false,
            val reason: String? = null,
        )
    }

    @Serializable
    data class Rag(
        val needRag: Boolean,
        val why: String? = null,
        val queries: List<Query> = emptyList(),
        val expectedFacts: List<String> = emptyList(),
    ) {
        @Serializable
        data class Query(
            val query: String,
            val filters: Map<String, String?> = emptyMap(),
            val k: Int = 5,
        )
    }

    @Serializable
    data class Action(
        val mode: Mode,
        val userNotification: UserNotification? = null,
        val responseDraft: ResponseDraft? = null,
    ) {
        @Serializable
        enum class Mode { STORE_ONLY, NOTIFY_USER_WITH_OPTIONS, AUTO_RESPOND, ESCALATE, IGNORE_DUPLICATE }

        @Serializable
        data class UserNotification(
            val title: String,
            val summary: String,
            val severity: String = "INFO",
        )

        @Serializable
        data class ResponseDraft(
            val channel: String,
            val to: List<String> = emptyList(),
            val subject: String? = null,
            val text: String? = null,
            val requiresUserApproval: Boolean = true,
        )
    }

    @Serializable
    data class Indexing(
        val shouldIndex: Boolean = false,
        val records: List<Record> = emptyList(),
    ) {
        @Serializable
        data class Record(
            val kind: String,
            val primaryKey: String,
            val text: String,
            val metadata: Map<String, String?> = emptyMap(),
        )
    }
}
