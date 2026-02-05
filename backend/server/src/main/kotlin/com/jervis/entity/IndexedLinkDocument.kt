package com.jervis.entity

import com.jervis.common.types.ClientId
import com.jervis.common.types.TaskId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Tracks downloaded/indexed links to prevent duplicate processing.
 * When a link is downloaded and indexed (via delegateLinkProcessing), it's recorded here.
 *
 * NOTE: No projectId - links can lead anywhere and aren't project-specific.
 * Graph relationships will handle linking to specific projects/pages.
 */
@Document(collection = "indexed_links")
@CompoundIndexes(
    CompoundIndex(name = "url_client_idx", def = "{'url': 1, 'clientId': 1}", unique = true),
    CompoundIndex(name = "indexed_at_idx", def = "{'lastIndexedAt': -1}"),
)
data class IndexedLinkDocument(
    @Id val id: ObjectId = ObjectId.get(),
    val url: String,
    val clientId: ClientId,
    val lastIndexedAt: Instant = Instant.now(),
    val contentHash: String? = null,
    val correlationId: String? = null,
    val taskId: TaskId? = null,
)
