package com.jervis.ui.questions

import com.jervis.dto.agent.AgentQuestionDto
import com.jervis.di.JervisRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for pending agent questions.
 *
 * Polls for count every 10s (for badge updates), full load on screen open.
 * Receives selectedClientId as a read-only StateFlow from MainViewModel.
 */
class PendingQuestionsViewModel(
    private val repository: JervisRepository,
    private val selectedClientId: StateFlow<String?>,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) {
            println("PendingQuestionsViewModel: uncaught exception: ${e::class.simpleName}: ${e.message}")
        }
    })

    private val _questions = MutableStateFlow<List<AgentQuestionDto>>(emptyList())
    val questions: StateFlow<List<AgentQuestionDto>> = _questions.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var countPollingJob: Job? = null

    /** Start polling for pending count every 10s (for badge). */
    fun startCountPolling() {
        if (countPollingJob?.isActive == true) return
        countPollingJob = scope.launch {
            while (true) {
                refreshCount()
                delay(10_000)
            }
        }
    }

    /** Stop polling (e.g. when screen is not visible). */
    fun stopCountPolling() {
        countPollingJob?.cancel()
        countPollingJob = null
    }

    /** Refresh only the count — lightweight call for badge updates. */
    suspend fun refreshCount() {
        try {
            val clientId = selectedClientId.value
            val count = repository.agentQuestions.getPendingCount(clientId)
            _pendingCount.value = count
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("PendingQuestionsViewModel: refreshCount failed: ${e.message}")
        }
    }

    /** Full load of pending questions — called when the panel is opened. */
    fun loadPendingQuestions() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val clientId = selectedClientId.value
                val result = repository.agentQuestions.getPendingQuestions(clientId)
                _questions.value = result
                _pendingCount.value = result.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Nepodařilo se načíst dotazy"
                println("PendingQuestionsViewModel: loadPendingQuestions failed: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Answer a pending question and reload the list. */
    fun answerQuestion(questionId: String, answer: String) {
        scope.launch {
            try {
                repository.agentQuestions.answerQuestion(questionId, answer)
                // Reload after answering
                loadPendingQuestions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Nepodařilo se odeslat odpověď"
                println("PendingQuestionsViewModel: answerQuestion failed: ${e.message}")
            }
        }
    }
}
