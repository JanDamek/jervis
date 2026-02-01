package com.jervis.types

import org.bson.types.ObjectId

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
