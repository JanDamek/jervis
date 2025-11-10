package com.jervis.joern.service

/**
 * Domain-specific exceptions for Joern failures.
 */
sealed class JoernException(
    message: String,
) : RuntimeException(message)

class JoernTimeoutException(
    message: String,
) : JoernException(message)

class JoernProcessException(
    message: String,
) : JoernException(message)
