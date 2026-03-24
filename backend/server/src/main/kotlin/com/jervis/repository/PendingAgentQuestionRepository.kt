package com.jervis.repository

import com.jervis.entity.PendingAgentQuestionDocument
import com.jervis.entity.QuestionState
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PendingAgentQuestionRepository : CoroutineCrudRepository<PendingAgentQuestionDocument, ObjectId> {

    fun findByState(state: QuestionState): Flow<PendingAgentQuestionDocument>

    fun findByTaskId(taskId: String): Flow<PendingAgentQuestionDocument>

    fun findByClientId(clientId: String): Flow<PendingAgentQuestionDocument>

    suspend fun countByState(state: QuestionState): Long

    fun findByStateIn(states: List<QuestionState>): Flow<PendingAgentQuestionDocument>

    fun findByClientIdAndStateIn(clientId: String, states: List<QuestionState>): Flow<PendingAgentQuestionDocument>

    suspend fun countByClientIdAndStateIn(clientId: String, states: List<QuestionState>): Long
}
