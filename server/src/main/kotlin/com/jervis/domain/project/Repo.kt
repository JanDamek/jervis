package com.jervis.domain.project

data class Repo(
    val primaryUrl: String,
    val extraUrls: List<String> = emptyList(),
    val credentialsRef: String? = null,
)
