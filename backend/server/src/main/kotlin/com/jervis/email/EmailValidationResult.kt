package com.jervis.email

/**
 * Domain model for email account validation result.
 * Represents the outcome of connection validation.
 */
data class EmailValidationResult(
    val isValid: Boolean,
    val message: String?,
)
