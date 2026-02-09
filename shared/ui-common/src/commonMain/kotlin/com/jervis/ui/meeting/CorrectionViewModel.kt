package com.jervis.ui.meeting

import com.jervis.dto.meeting.TranscriptCorrectionDto
import com.jervis.dto.meeting.TranscriptCorrectionSubmitDto
import com.jervis.service.ITranscriptCorrectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CorrectionViewModel(
    private val correctionService: ITranscriptCorrectionService,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _corrections = MutableStateFlow<List<TranscriptCorrectionDto>>(emptyList())
    val corrections: StateFlow<List<TranscriptCorrectionDto>> = _corrections.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadCorrections(clientId: String, projectId: String?) {
        scope.launch {
            _isLoading.value = true
            try {
                _corrections.value = correctionService.listCorrections(clientId, projectId)
            } catch (e: Exception) {
                _error.value = "Nepodarilo se nacist korekce: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun submitCorrection(submit: TranscriptCorrectionSubmitDto, clientId: String, projectId: String?) {
        scope.launch {
            try {
                correctionService.submitCorrection(
                    submit.copy(clientId = clientId, projectId = projectId),
                )
                loadCorrections(clientId, projectId)
            } catch (e: Exception) {
                _error.value = "Nepodarilo se ulozit korekci: ${e.message}"
            }
        }
    }

    fun deleteCorrection(sourceUrn: String, clientId: String, projectId: String?) {
        scope.launch {
            try {
                correctionService.deleteCorrection(sourceUrn)
                loadCorrections(clientId, projectId)
            } catch (e: Exception) {
                _error.value = "Nepodarilo se smazat korekci: ${e.message}"
            }
        }
    }
}
