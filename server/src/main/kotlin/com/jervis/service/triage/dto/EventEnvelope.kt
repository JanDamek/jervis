package com.jervis.service.triage.dto

import kotlinx.serialization.Serializable

@Serializable
data class EventEnvelope(
    val envelopeVersion: String = "1.0",
    val source: Source,
    val context: Context,
    val threading: Threading? = null,
    val artifact: Artifact,
) {
    @Serializable
    data class Source(
        val kind: Kind,
        val channel: Channel,
        val provider: String? = null,
        val receivedAt: String? = null,
    ) {
        @Serializable
        enum class Kind { USER, NOTIFICATION }

        @Serializable
        enum class Channel { email, slack, teams, web, vcs, cicd, calendar, other }
    }

    @Serializable
    data class Context(
        val clientId: String? = null,
        val projectId: String? = null,
        val policy: Policy? = null,
    ) {
        @Serializable
        data class Policy(
            val allowAutoRespond: Boolean = false,
            val allowBackgroundIndexOnly: Boolean = true,
            val notifyOnActionable: Boolean = true,
            val language: String = "en",
        )
    }

    @Serializable
    data class Threading(
        val threadKey: String? = null,
        val inReplyTo: String? = null,
        val dedupeHash: String? = null,
    )

    @Serializable
    data class Artifact(
        val type: String,
        val metadata: Map<String, String?> = emptyMap(),
        val text: String? = null,
        val html: String? = null,
        val attachments: List<Attachment> = emptyList(),
    ) {
        @Serializable
        data class Attachment(
            val id: String,
            val name: String,
            val mime: String,
            val size: Long? = null,
        )
    }
}
