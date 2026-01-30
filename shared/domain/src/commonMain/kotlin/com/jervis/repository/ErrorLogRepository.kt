package com.jervis.repository

import com.jervis.dto.error.ErrorLogDto
import com.jervis.service.IErrorLogService

/**
 * Repository for ErrorLog operations
 * Wraps IErrorLogService with additional logic (caching, error handling, etc.)
 */
class ErrorLogRepository(
    private val errorLogService: IErrorLogService
) : BaseRepository() {

    suspend fun recordUiError(
        message: String,
        stackTrace: String? = null,
        causeType: String? = null,
        clientId: String? = null,
        projectId: String? = null,
        correlationId: String? = null,
    ) {
        safeRpcCall("recordUiError") {
            errorLogService.add(
                com.jervis.dto.error.ErrorLogCreateRequestDto(
                    clientId = clientId,
                    projectId = projectId,
                    correlationId = correlationId,
                    message = message,
                    stackTrace = stackTrace,
                    causeType = causeType,
                ),
            )
        }
    }

    /**
     * List all error logs (global, no client filter)
     */
    suspend fun listAllErrorLogs(limit: Int = 500): List<ErrorLogDto> = safeRpcListCall("listAllErrorLogs") {
        errorLogService.listAll(limit)
    }

    /**
     * List error logs for a client
     */
    suspend fun listErrorLogs(clientId: String, limit: Int = 500): List<ErrorLogDto> = safeRpcListCall("listErrorLogs") {
        errorLogService.list(clientId, limit)
    }

    /**
     * Get error log by ID
     */
    suspend fun getErrorLog(id: String): ErrorLogDto = safeRpcCall("getErrorLog") {
        errorLogService.get(id)
    }

    /**
     * Delete error log
     */
    suspend fun deleteErrorLog(id: String) {
        safeRpcCall("deleteErrorLog") {
            errorLogService.delete(id)
        }
    }

    /**
     * Delete all error logs for a client
     */
    suspend fun deleteAllForClient(clientId: String) {
        safeRpcCall("deleteAllForClient") {
            errorLogService.deleteAll(clientId)
        }
    }
}
