package com.jervis.entity.knowledge

import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "knowledge_documents")
@CompoundIndexes(
    CompoundIndex(
        name = "client_source_hash_unique",
        def = "{'clientId': 1, 'sourceUrn': 1, 'contentHash': 1}",
        unique = true,
    ),
    CompoundIndex(name = "client_source_idx", def = "{'clientId': 1, 'sourceUrn': 1}"),
    CompoundIndex(name = "client_project_idx", def = "{'clientId': 1, 'projectId': 1}"),
)
data class KnowledgeDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val clientId: ClientId,
    @Indexed
    val projectId: ProjectId? = null,
    @Indexed
    val sourceUrn: SourceUrn,
    @Indexed
    val sourceType: String,
    val title: String? = null,
    val content: String,
    val rawContent: String? = null,
    @Indexed
    val contentHash: String,
    val attachments: List<KnowledgeAttachment> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    val observedAt: Instant = Instant.now(),
    val lastSeenAt: Instant = Instant.now(),
    val seenCount: Long = 1,
)

data class KnowledgeAttachment(
    val assetId: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val description: String? = null,
)
