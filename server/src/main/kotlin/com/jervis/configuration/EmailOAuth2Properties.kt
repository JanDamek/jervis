package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "email.oauth")
data class EmailOAuth2Properties(
    val google: GoogleOAuth2Config,
    val microsoft: MicrosoftOAuth2Config,
) {
    data class GoogleOAuth2Config(
        val clientId: String,
        val clientSecret: String,
        val redirectUri: String? = null,
    )

    data class MicrosoftOAuth2Config(
        val clientId: String,
        val clientSecret: String,
        val redirectUri: String? = null,
    )
}
