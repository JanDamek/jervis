package com.jervis.rpc

import com.jervis.service.error.ErrorLogService
import mu.KotlinLogging
import org.bson.types.ObjectId

/**
 * Base class for all RPC implementations providing error handling.
 * All RPC errors are logged to ErrorLogService and re-thrown to client.
 */
abstract class BaseRpcImpl(
    protected val errorLogService: ErrorLogService,
) {
    protected val logger = KotlinLogging.logger {}

    /**
     * Execute RPC call with error logging.
     * Logs error, records to ErrorLog, and re-throws to client.
     */
    protected suspend fun <T> executeWithErrorHandling(
        operation: String,
        clientId: ObjectId? = null,
        projectId: ObjectId? = null,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: Exception) {
            logger.error(e) { "RPC Error in $operation" }
            errorLogService.recordError(
                throwable = e,
                clientId = clientId,
                projectId = projectId,
                correlationId = operation,
            )
            throw e
        }
}
