package com.jervis.dto

data class FailIndexingRequest(
    val projectId: String,
    val errorMessage: String,
)
