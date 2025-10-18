package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class InspirationPolicyDto(
    val allowCrossClientInspiration: Boolean = true,
    val allowedClientSlugs: List<String> = emptyList(),
    val disallowedClientSlugs: List<String> = emptyList(),
    val enforceFullAnonymization: Boolean = true,
    val maxSnippetsPerForeignClient: Int = 5,
)
