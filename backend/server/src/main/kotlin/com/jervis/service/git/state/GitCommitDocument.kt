package com.jervis.service.git.state

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

enum class GitCommitState {
    NEW,
    INDEXED,
}

@Document(collection = "git_commits")
@CompoundIndexes(
    CompoundIndex(
        name = "client_monorepo_commitHash_idx",
        def = "{'clientId':1,'monoRepoId':1,'commitHash':1}",
        unique = true,
    ),
    CompoundIndex(
        name = "project_commitHash_idx",
        def = "{'projectId':1,'commitHash':1}",
        unique = true,
    ),
    CompoundIndex(
        name = "client_monorepo_state_idx",
        def = "{'clientId':1,'monoRepoId':1,'state':1,'commitDate':1}",
    ),
    CompoundIndex(
        name = "project_state_idx",
        def = "{'projectId':1,'state':1,'commitDate':1}",
    ),
)
data class GitCommitDocument(
    @Id val id: ObjectId = ObjectId(),
    val clientId: ObjectId,
    val projectId: ObjectId? = null,
    val monoRepoId: String? = null,
    val commitHash: String,
    val state: GitCommitState,
    val author: String? = null,
    val message: String? = null,
    val commitDate: Instant? = null,
    val branch: String = "main",
)
