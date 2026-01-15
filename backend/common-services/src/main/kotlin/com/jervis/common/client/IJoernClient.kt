package com.jervis.common.client

import com.jervis.common.dto.JoernQueryDto
import com.jervis.common.dto.JoernResultDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IJoernClient {
    suspend fun run(request: JoernQueryDto): JoernResultDto
}
