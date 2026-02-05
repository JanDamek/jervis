package com.jervis.common.client

import com.jervis.common.dto.CodingRequest
import com.jervis.common.dto.CodingResult
import kotlinx.rpc.annotations.Rpc

/**
 * RPC interface for coding services (Aider, OpenHands, Junie).
 * Each service implements this interface and provides code generation/modification capabilities.
 */
@Rpc
interface ICodingClient {
    /**
     * Execute a coding task.
     *
     * @param request The coding task request with instructions and context
     * @return The result including generated/modified code and execution status
     */
    suspend fun execute(request: CodingRequest): CodingResult
}
