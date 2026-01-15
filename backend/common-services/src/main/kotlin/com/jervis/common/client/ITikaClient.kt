package com.jervis.common.client

import com.jervis.common.dto.TikaProcessRequest
import com.jervis.common.dto.TikaProcessResult
import kotlinx.rpc.annotations.Rpc

@Rpc
interface ITikaClient {
    suspend fun process(
        request: TikaProcessRequest,
    ): TikaProcessResult
}
