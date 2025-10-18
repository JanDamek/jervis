package com.jervis.dto

data class CreateContextRequestDto(
    val clientId: String?,
    val projectId: String?,
    val quick: Boolean = false,
    val contextName: String = "New Context",
)
