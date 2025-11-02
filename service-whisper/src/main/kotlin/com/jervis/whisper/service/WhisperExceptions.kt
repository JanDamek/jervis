package com.jervis.whisper.service

/**
 * Domain-specific exceptions for Whisper failures.
 */
sealed class WhisperException(
    message: String,
) : RuntimeException(message)

class WhisperTimeoutException(
    message: String,
) : WhisperException(message)

class WhisperProcessException(
    message: String,
) : WhisperException(message)
