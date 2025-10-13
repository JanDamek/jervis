package com.jervis.domain.client

import kotlinx.serialization.Serializable

@Serializable
data class GitConn(
    val provider: String? = null, // github/gitlab/bitbucket
    val baseUrl: String? = null,
    val authType: String? = null, // pat/ssh/oauth
    val credentialsRef: String? = null,
)
