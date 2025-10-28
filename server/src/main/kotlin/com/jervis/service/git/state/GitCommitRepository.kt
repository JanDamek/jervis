package com.jervis.service.git.state

import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface GitCommitRepository : ReactiveMongoRepository<GitCommitDocument, ObjectId> {
    fun findByProjectId(projectId: ObjectId): Flux<GitCommitDocument>

    fun findByProjectIdAndStateOrderByCommitDateAsc(
        projectId: ObjectId,
        state: GitCommitState,
    ): Flux<GitCommitDocument>

    fun findByProjectIdAndCommitHash(
        projectId: ObjectId,
        commitHash: String,
    ): Mono<GitCommitDocument>
}
