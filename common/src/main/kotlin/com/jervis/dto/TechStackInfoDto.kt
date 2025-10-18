package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class TechStackInfoDto(
    val framework: String,
    val language: String,
    val version: String?,
    val securityFramework: String?,
    val databaseType: String?,
    val buildTool: String?,
)
