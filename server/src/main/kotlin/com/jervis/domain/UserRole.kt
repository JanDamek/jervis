package com.jervis.domain

/**
 * Fixed set of roles available on the initial persona screen.
 * This enum is used for validation and stored in the HTTP session.
 */
enum class UserRole {
    DEVELOPER,
    DESIGNER,
    MANAGER;
}
