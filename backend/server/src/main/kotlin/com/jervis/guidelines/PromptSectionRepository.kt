package com.jervis.guidelines

import com.jervis.dto.selfevolution.PromptSectionType
import com.jervis.guidelines.LearnedBehaviorDocument
import com.jervis.guidelines.PromptSectionDocument
import com.jervis.guidelines.PromptVersionHistoryDocument
import com.jervis.guidelines.UserCorrectionDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PromptSectionRepository : CoroutineCrudRepository<PromptSectionDocument, ObjectId> {
    fun findByClientId(clientId: String): Flow<PromptSectionDocument>
    suspend fun findByClientIdAndType(clientId: String, type: PromptSectionType): PromptSectionDocument?
}

@Repository
interface LearnedBehaviorRepository : CoroutineCrudRepository<LearnedBehaviorDocument, ObjectId> {
    fun findByClientId(clientId: String): Flow<LearnedBehaviorDocument>
    fun findByClientIdAndApprovedByUser(clientId: String, approvedByUser: Boolean): Flow<LearnedBehaviorDocument>
}

@Repository
interface UserCorrectionRepository : CoroutineCrudRepository<UserCorrectionDocument, ObjectId> {
    fun findByClientId(clientId: String): Flow<UserCorrectionDocument>
    fun findByClientIdAndProjectId(clientId: String, projectId: String?): Flow<UserCorrectionDocument>
}

@Repository
interface PromptVersionHistoryRepository : CoroutineCrudRepository<PromptVersionHistoryDocument, ObjectId> {
    fun findByClientIdAndSectionTypeOrderByVersionDesc(
        clientId: String,
        sectionType: PromptSectionType,
    ): Flow<PromptVersionHistoryDocument>
}
