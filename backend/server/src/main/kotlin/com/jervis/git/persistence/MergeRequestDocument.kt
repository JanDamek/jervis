package com.jervis.git.persistence

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

enum class MergeRequestState {
    NEW,
    KB_INDEXING,
    REVIEW_DISPATCHED,
    DONE,
    FAILED,
}

@Document(collection = "merge_requests")
@CompoundIndexes(
    CompoundIndex(
        name = "project_mrId_provider_idx",
        def = "{'projectId':1,'mergeRequestId':1,'provider':1}",
        unique = true,
    ),
    CompoundIndex(
        name = "state_idx",
        def = "{'state':1,'createdAt':1}",
    ),
)
data class MergeRequestDocument(
    @Id val id: ObjectId = ObjectId(),
    val clientId: ObjectId,
    val projectId: ObjectId,
    val connectionId: ObjectId,
    val provider: String,
    val mergeRequestId: String,
    val title: String,
    val description: String? = null,
    val author: String? = null,
    val sourceBranch: String,
    val targetBranch: String,
    val url: String,
    val state: MergeRequestState = MergeRequestState.NEW,
    val reviewTaskId: ObjectId? = null,
    val createdAt: Instant = Instant.now(),
)
