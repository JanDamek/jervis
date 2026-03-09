package com.jervis.service

import com.jervis.dto.JobLogEventDto
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * IJobLogsService — live K8s Job log streaming.
 *
 * Provides real-time output from coding agent K8s Jobs.
 * UI subscribes when a task enters CODING state.
 */
@Rpc
interface IJobLogsService {

    /**
     * Subscribe to live log events from a coding agent Job.
     *
     * Streams parsed log lines (text, tool calls, results) as SSE events.
     * Flow completes when the job finishes.
     *
     * @param taskId The task ID in CODING state
     */
    fun subscribeToJobLogs(taskId: String): Flow<JobLogEventDto>
}
