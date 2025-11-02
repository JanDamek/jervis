package com.jervis.joern.api

import com.jervis.joern.service.JoernProcessException
import com.jervis.joern.service.JoernTimeoutException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class JoernExceptionHandler {
    data class ErrorResponse(
        val error: String,
        val detail: String? = null,
    )

    @ExceptionHandler(JoernTimeoutException::class)
    fun handleTimeout(ex: JoernTimeoutException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.GATEWAY_TIMEOUT)
            .body(ErrorResponse(error = "Joern timeout", detail = ex.message))

    @ExceptionHandler(JoernProcessException::class)
    fun handleProcess(ex: JoernProcessException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse(error = "Joern failed", detail = ex.message))

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(error = "Internal error", detail = ex.message))
}
