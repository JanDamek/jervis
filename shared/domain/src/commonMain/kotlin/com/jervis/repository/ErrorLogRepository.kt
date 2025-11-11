package com.jervis.repository

import com.jervis.dto.error.ErrorLogDto
import com.jervis.service.IErrorLogService

/**
 * Repository for ErrorLog operations
 * Wraps IErrorLogService with additional logic (caching, error handling, etc.)
 */
class ErrorLogRepository(
    private val errorLogService: IErrorLogService
) {

    /**
     * List all error logs (global, no client filter)
     */
    suspend fun listAllErrorLogs(limit: Int = 500): List<ErrorLogDto> {
        return errorLogService.listAll(limit)
    }

    /**
     * List error logs for a client
     */
    suspend fun listErrorLogs(clientId: String, limit: Int = 500): List<ErrorLogDto> {
        return errorLogService.list(clientId, limit)
    }

    /**
     * Get error log by ID
     */
    suspend fun getErrorLog(id: String): ErrorLogDto {
        return errorLogService.get(id)
    }

    /**
     * Delete error log
     */
    suspend fun deleteErrorLog(id: String) {
        errorLogService.delete(id)
    }

    /**
     * Delete all error logs for a client
     */
    suspend fun deleteAllForClient(clientId: String) {
        errorLogService.deleteAll(clientId)
    }
}
