package com.jervis.common.client

import com.jervis.common.dto.WhisperRequestDto
import com.jervis.common.dto.WhisperResultDto
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange("/api/whisper")
interface IWhisperClient {
    @PostExchange("/transcribe")
    suspend fun transcribe(request: WhisperRequestDto): WhisperResultDto
}
