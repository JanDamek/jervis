package com.jervis.common.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.bson.types.ObjectId

@Serializable(with = SourceUrnSerializer::class)
@JvmInline
value class SourceUrn(
    val value: String,
) {
    companion object {
        fun unknownSource(): SourceUrn = SourceUrn("unknown")

        fun link(url: String): SourceUrn = SourceUrn("link::url:${encodeValue(url)}")

        fun confluence(
            connectionId: ObjectId,
            pageId: String,
        ): SourceUrn = SourceUrn("confluence::conn:${connectionId.toHexString()},pageId:${encodeValue(pageId)}")

        fun jira(
            connectionId: ObjectId,
            issueKey: String,
        ): SourceUrn = SourceUrn("jira::conn:${connectionId.toHexString()},issueKey:${encodeValue(issueKey)}")

        fun githubIssue(
            connectionId: ObjectId,
            issueKey: String,
        ): SourceUrn = SourceUrn("github-issue::conn:${connectionId.toHexString()},issueKey:${encodeValue(issueKey)}")

        fun gitlabIssue(
            connectionId: ObjectId,
            issueKey: String,
        ): SourceUrn = SourceUrn("gitlab-issue::conn:${connectionId.toHexString()},issueKey:${encodeValue(issueKey)}")

        fun email(
            connectionId: ConnectionId,
            messageId: String,
            subject: String,
        ): SourceUrn =
            SourceUrn(
                "email::conn:$connectionId,msgId:${encodeValue(messageId)},subject:${
                    encodeValue(subject)
                }",
            )

        fun chat(clientId: ClientId): SourceUrn = SourceUrn("chat::clientId:${clientId.value.toHexString()}")

        fun scheduled(taskName: String): SourceUrn = SourceUrn("scheduled::task:${encodeValue(taskName)}")

        fun document(documentId: String): SourceUrn = SourceUrn("doc::id:${encodeValue(documentId)}")

        fun meeting(
            meetingId: String,
            title: String? = null,
        ): SourceUrn =
            SourceUrn(
                "meeting::id:${encodeValue(meetingId)}${
                    title?.let { ",title:${encodeValue(it)}" } ?: ""
                }",
            )

        fun git(
            projectId: ProjectId,
            commitHash: String,
            filePath: String? = null,
        ): SourceUrn =
            SourceUrn(
                "git::proj:${projectId.value.toHexString()},hash:$commitHash${
                    filePath?.let { ",path:${encodeValue(it)}" } ?: ""
                }",
            )

        fun emailAttachment(
            connectionId: ConnectionId,
            messageId: String,
            filename: String,
        ): SourceUrn =
            SourceUrn(
                "email-attachment::conn:$connectionId,msgId:${encodeValue(messageId)},file:${encodeValue(filename)}",
            )

        fun jiraAttachment(
            connectionId: ObjectId,
            issueKey: String,
            filename: String,
        ): SourceUrn =
            SourceUrn(
                "jira-attachment::conn:${connectionId.toHexString()},issueKey:${encodeValue(issueKey)},file:${encodeValue(filename)}",
            )

        fun confluenceAttachment(
            connectionId: ObjectId,
            pageId: String,
            filename: String,
        ): SourceUrn =
            SourceUrn(
                "confluence-attachment::conn:${connectionId.toHexString()},pageId:${encodeValue(pageId)},file:${encodeValue(filename)}",
            )

        fun teams(
            connectionId: ConnectionId,
            messageId: String,
            channelId: String? = null,
            chatId: String? = null,
        ): SourceUrn =
            SourceUrn(
                "teams::conn:$connectionId,msgId:${encodeValue(messageId)}${
                    channelId?.let { ",channelId:${encodeValue(it)}" } ?: ""
                }${
                    chatId?.let { ",chatId:${encodeValue(it)}" } ?: ""
                }",
            )

        fun slack(
            connectionId: ConnectionId,
            messageId: String,
            channelId: String,
        ): SourceUrn =
            SourceUrn(
                "slack::conn:$connectionId,msgId:${encodeValue(messageId)},channelId:${encodeValue(channelId)}",
            )

        fun discord(
            connectionId: ConnectionId,
            messageId: String,
            channelId: String,
            guildId: String? = null,
        ): SourceUrn =
            SourceUrn(
                "discord::conn:$connectionId,msgId:${encodeValue(messageId)},channelId:${encodeValue(channelId)}${
                    guildId?.let { ",guildId:${encodeValue(it)}" } ?: ""
                }",
            )

        fun whatsapp(
            connectionId: ConnectionId,
            messageId: String,
            chatName: String? = null,
        ): SourceUrn =
            SourceUrn(
                "whatsapp::conn:$connectionId,msgId:${encodeValue(messageId)}${
                    chatName?.let { ",chat:${encodeValue(it)}" } ?: ""
                }",
            )

        fun calendar(
            connectionId: ConnectionId,
            eventId: String,
            calendarId: String? = null,
        ): SourceUrn =
            SourceUrn(
                "calendar::conn:$connectionId,eventId:${encodeValue(eventId)}${
                    calendarId?.let { ",calId:${encodeValue(it)}" } ?: ""
                }",
            )

        fun mergeRequest(
            projectId: ProjectId,
            provider: String,
            mrId: String,
        ): SourceUrn =
            SourceUrn(
                "merge-request::proj:${projectId.value.toHexString()},provider:$provider,mr:${encodeValue(mrId)}",
            )

        private fun encodeValue(value: String): String =
            value
                .replace(",", "%2C")
                .replace(":", "%3A")
    }

    /**
     * Provider/scheme prefix of the URN (the part before "::").
     * e.g. "email" for `email::conn:abc,msgId:42`.
     */
    fun scheme(): String = value.substringBefore("::", missingDelimiterValue = "unknown")

    /**
     * Wire-format identifier sent to the Knowledge Base service for ingestion.
     * Mapped from [scheme]; the KB Python service has its own [SourceType] enum
     * (see backend/service-knowledgebase/app/api/models.py) which we must match.
     *
     * Multiple URN schemes can collapse to the same KB source type — e.g. both
     * `github-issue::` and `gitlab-issue::` map to "jira" because the KB only
     * tracks "issue tracker" as a credibility tier, not the specific vendor.
     */
    fun kbSourceType(): String =
        when (scheme()) {
            "email" -> "email"
            "jira", "github-issue", "gitlab-issue" -> "jira"
            "confluence" -> "confluence"
            "git", "merge-request" -> "git"
            "meeting" -> "meeting"
            "chat" -> "chat"
            "scheduled" -> "scheduled"
            "teams" -> "teams"
            "slack" -> "slack"
            "discord" -> "discord"
            "whatsapp" -> "whatsapp"
            "calendar" -> "calendar"
            "doc", "link" -> "link"
            "idle-review" -> "idle_review"
            "user-task" -> "user_task"
            else -> "user_task"
        }

    /**
     * Short Czech UI label for this source — used in queue display, K reakci,
     * task lists, etc. Replaces the previous TaskTypeEnum-based label switches.
     */
    fun uiLabel(): String =
        when (scheme()) {
            "email" -> "Email"
            "jira", "github-issue", "gitlab-issue" -> "Bug Tracker"
            "confluence" -> "Wiki"
            "git" -> "Git"
            "merge-request" -> "Merge Request"
            "meeting" -> "Schůzka"
            "chat" -> "Asistent"
            "scheduled" -> "Naplánovaná úloha"
            "teams" -> "Teams"
            "slack" -> "Slack"
            "discord" -> "Discord"
            "whatsapp" -> "WhatsApp"
            "calendar" -> "Kalendář"
            "doc", "link" -> "Dokument"
            "idle-review" -> "Pravidelný přehled"
            "user-task" -> "Uživatelská úloha"
            else -> "Úloha"
        }
}

object SourceUrnSerializer : KSerializer<SourceUrn> {
    override val descriptor = PrimitiveSerialDescriptor("SourceUrn", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: SourceUrn) = encoder.encodeString(value.value)
    override fun deserialize(decoder: Decoder): SourceUrn = SourceUrn(decoder.decodeString())
}
