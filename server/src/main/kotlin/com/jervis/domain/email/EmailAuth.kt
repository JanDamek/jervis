package com.jervis.domain.email

/** Authentication methods supported by email providers. */
sealed interface EmailAuth {
    data class OAuth2(val authorizationServer: String) : EmailAuth
    data class Password(val username: String) : EmailAuth
}
