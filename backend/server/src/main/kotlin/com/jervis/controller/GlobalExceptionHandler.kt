package com.jervis.controller

import com.jervis.service.error.ErrorLogService
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler(
    private val errorLogService: ErrorLogService,
) {
    private val logger = KotlinLogging.logger {}

    data class ErrorResponse(
        val status: Int,
        val error: String,
        val message: String?,
        val timestamp: String = Instant.now().toString(),
    )

    @ExceptionHandler(Throwable::class)
    fun handleAny(throwable: Throwable): ResponseEntity<ErrorResponse> {
        runBlocking {
            errorLogService.recordError(throwable)
        }
        val status = HttpStatus.INTERNAL_SERVER_ERROR
        val body = ErrorResponse(status.value(), status.reasonPhrase, throwable.message)
        logger.error(throwable) { "Unhandled exception captured and published" }
        return ResponseEntity.status(status).body(body)
    }
}
