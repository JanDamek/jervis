package com.jervis.knowledgebase.internal

import com.jervis.types.ClientId
import java.util.UUID

internal object KnowledgeChunkId {
    fun from(
        clientId: ClientId,
        sourceKey: String,
        contentHash: String,
    ): String =
        UUID.nameUUIDFromBytes("$clientId|$sourceKey|$contentHash".toByteArray()).toString()
}
