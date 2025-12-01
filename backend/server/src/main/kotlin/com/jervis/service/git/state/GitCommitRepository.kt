package com.jervis.service.git.state

import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface GitCommitRepository : CoroutineCrudRepository<GitCommitDocument, ObjectId> {
    fun findByProjectId(projectId: ObjectId): Flow<GitCommitDocument>

    fun findByProjectIdAndStateOrderByCommitDateAsc(
        projectId: ObjectId,
        state: GitCommitState,
    ): Flow<GitCommitDocument>

    fun findByStateOrderByCommitDateDesc(state: GitCommitState): Flow<GitCommitDocument>

    // Global counts for UI overview
    suspend fun countByState(state: GitCommitState): Long
}
