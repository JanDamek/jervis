package com.jervis.common.client

import com.jervis.common.dto.WhisperRequestDto
import com.jervis.common.dto.WhisperResultDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IWhisperClient {
    suspend fun transcribe(
        request: WhisperRequestDto,
    ): WhisperResultDto
}
