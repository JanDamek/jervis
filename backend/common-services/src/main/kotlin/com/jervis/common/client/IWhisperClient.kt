package com.jervis.common.client

import com.jervis.common.dto.WhisperRequestDto
import com.jervis.common.dto.WhisperResultDto
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.POST

interface IWhisperClient {
    @POST("api/whisper/transcribe")
    suspend fun transcribe(
        @Body request: WhisperRequestDto,
    ): WhisperResultDto
}
