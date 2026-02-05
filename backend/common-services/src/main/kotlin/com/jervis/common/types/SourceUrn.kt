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

        private fun encodeValue(value: String): String =
            value
                .replace(",", "%2C")
                .replace(":", "%3A")
    }
}

object SourceUrnSerializer : KSerializer<SourceUrn> {
    override val descriptor = PrimitiveSerialDescriptor("SourceUrn", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: SourceUrn) = encoder.encodeString(value.value)
    override fun deserialize(decoder: Decoder): SourceUrn = SourceUrn(decoder.decodeString())
}
