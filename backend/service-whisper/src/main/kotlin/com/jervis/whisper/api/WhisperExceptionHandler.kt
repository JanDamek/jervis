package com.jervis.whisper.api

import com.jervis.whisper.service.WhisperProcessException
import com.jervis.whisper.service.WhisperTimeoutException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class WhisperExceptionHandler {
    data class ErrorResponse(
        val error: String,
        val detail: String? = null,
    )

    @ExceptionHandler(WhisperTimeoutException::class)
    fun handleTimeout(ex: WhisperTimeoutException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.GATEWAY_TIMEOUT)
            .body(ErrorResponse(error = "Whisper timeout", detail = ex.message))

    @ExceptionHandler(WhisperProcessException::class)
    fun handleProcess(ex: WhisperProcessException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse(error = "Whisper failed", detail = ex.message))

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(error = "Internal error", detail = ex.message))
}
